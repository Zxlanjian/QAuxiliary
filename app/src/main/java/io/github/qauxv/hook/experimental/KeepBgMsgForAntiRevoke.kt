/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the General Public License for more details.
 *
 * You should have received a copy of the General Public License
 * along with this software.
 * If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package io.github.qauxv.hook.experimental

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import cc.ioctl.hook.msg.RevokeMsgHook
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.bridge.AppRuntimeHelper
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.hostInfo
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import mqq.app.AppRuntime
import xyz.nextalone.util.throwOrTrue
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 后台保持消息加载（防撤回保活）并压制后台耗电。
 *
 * 解决痛点：
 * 1. QQ 退到后台一段时间后，`AppRuntime` 进入 `isBackground_Stop = true` 深度停止状态，
 *    或 `GuardManager` / 内存回收机制挂起、杀死主进程，导致收到撤回通知时原消息尚未拉取入库，
 *    防撤回只能提示「撤回了一条消息（没收到）」。
 * 2. 若单纯让 QQ 后台常驻，QQ 会频繁拉起 `:tool`、`:peak`、`:qzone`、`:mini`、`:wxa_container`、
 *    `:video` 等附属子进程，并长时间持有无超时的 `PowerManager.WakeLock` 以及上报后台埋点，导致严重耗电。
 *
 * 实现机制：
 * - 【保活消息链路】：
 *   1) 维持 `AppRuntime.isBackground_Stop = false`（同时保留 `isBackground_Pause = true` 以正常弹出系统通知），
 *      让主进程消息管线（`MessageHandler` / `QQMessageFacade` / NT Kernel）在后台实时接收并落盘消息，确保防撤回命中；
 *   2) 拦截后台状态下 `GuardManager` / 内存清理对主进程的自杀调用（`Process.killProcess` / `Runtime.exit`）；
 *   3) 将后台 `Application.onTrimMemory` 等级上限控制在 `TRIM_MEMORY_UI_HIDDEN`，释放 UI 缓存的同时保护消息运行时不被卸载。
 * - 【压制后台进程与耗电】：
 *   1) 退入后台 15 秒后自动清理除主进程与 `:MSF` 以外的所有附属子进程（如 `:tool`、`:qzone`、`:mini*`、`:peak`、`:video` 等），
 *      并在后台期间拦截非核心业务子进程 Service 的预加载拉起；
 *   2) 限制后台 `PowerManager.WakeLock` 持有时长（将无限期 `acquire()` 转为短超时，并将超长超时截断至 5 秒内），
 *      保证收到消息并入库后 CPU 立即恢复深度休眠；
 *   3) 屏蔽后台状态下的 `StatisticCollector` / `UserAction` 性能与埋点上报，减少后台无意义的 CPU 与网络唤醒。
 */
@FunctionHookEntry
@UiItemAgentEntry
object KeepBgMsgForAntiRevoke : CommonSwitchFunctionHook(
    hookKey = "qauxv_keep_bg_msg_for_anti_revoke",
    defaultEnabled = false,
    targets = null,
    targetProc = SyncUtils.PROC_MAIN or SyncUtils.PROC_MSF
) {

    private const val TAG = "KeepBgMsgForAntiRevoke"

    /** 退入后台后延迟清理附属子进程的时间（毫秒） */
    private const val BG_CLEANUP_DELAY_MS = 15_000L

    /** 后台低频巡检与维持消息接收状态周期（毫秒） */
    private const val BG_CHECK_INTERVAL_MS = 90_000L

    /** 后台允许单次持有 WakeLock 的最大时长（毫秒），足够完成单次消息推送接收与写库 */
    private const val MAX_BG_WAKELOCK_MS = 5_000L
    private const val DEFAULT_BG_WAKELOCK_MS = 3_000L

    override val name = "后台保持消息加载并压制耗电"

    override val description = "在后台保持主进程消息接收链路活跃，防止防撤回出现「没收到」；" +
        "同时自动清理非必要附属子进程（保留主进程与 MSF）、限制后台唤醒锁时长并屏蔽后台埋点以降低耗电。"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    private val startedActivityCount = AtomicInteger(0)
    private val isAppInBackground = AtomicBoolean(true)
    private val lifecycleRegistered = AtomicBoolean(false)
    private val wakeLockReentrancyGuard = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    @Volatile
    private var mainHandler: Handler? = null

    @Volatile
    private var fIsBackgroundStop: Field? = null

    @Volatile
    private var fIsBackgroundPause: Field? = null

    /** 后台禁止预加载/拉起的非核心组件类名关键字 */
    private val BLOCKED_BG_COMPONENT_KEYWORDS = arrayOf(
        "cooperation.qzone",
        "com.qzone",
        "com.tencent.qqmini",
        "com.tencent.mobileqq.mini",
        "com.tencent.mobileqq.wxa",
        "com.tencent.mobileqq.webview",
        "com.tencent.mobileqq.zplan",
        "com.tencent.mobileqq.peak",
        "com.tencent.mobileqq.apollo",
        "com.tencent.mobileqq.olympic",
        "com.tencent.rmonitor",
        "com.tencent.qqperf",
        "com.tencent.av.service",
        "cooperation.qqfav",
        "cooperation.troop",
        "cooperation.plugin",
    )

    /** 后台必须放行的核心消息与通知服务关键字 */
    private val ALLOWED_CORE_COMPONENT_KEYWORDS = arrayOf(
        "msf",
        "Msf",
        "CoreService",
        "KernelService",
        "MobileQQService",
        "GuardManager",
        "Notification",
        "notification",
        "push",
        "Push",
    )

    /** 崩溃处理或用户主动退出相关的调用栈关键字（不拦截此类退出） */
    private val ALLOW_EXIT_STACK_KEYWORDS = arrayOf(
        "UncaughtException",
        "uncaughtException",
        "CrashHandler",
        "CrashReport",
        "AccountManage",
        "QQSetting",
        "LoginActivity",
    )

    private val bgMaintenanceRunnable = object : Runnable {
        override fun run() {
            if (!isEnabled || !isAppInBackground.get()) return
            try {
                keepAppRuntimeMsgActive()
                suppressAuxiliaryProcesses()
            } catch (t: Throwable) {
                traceError(t)
            }
            mainHandler?.postDelayed(this, BG_CHECK_INTERVAL_MS)
        }
    }

    override fun initOnce(): Boolean = throwOrTrue {
        // 确保防撤回已开启时优先完成初始化
        if (RevokeMsgHook.INSTANCE.isEnabled && !RevokeMsgHook.INSTANCE.isInitialized) {
            runCatching { RevokeMsgHook.INSTANCE.initialize() }
        }

        if (SyncUtils.isMainProcess()) {
            initMainProcessHooks()
        }
        initWakeLockSuppressionHooks()
        initTelemetrySuppressionHooks()
        Log.d("$TAG: initialized in process ${SyncUtils.getProcessName()}")
    }

    private fun initMainProcessHooks() {
        val app = hostInfo.application
        mainHandler = Handler(Looper.getMainLooper())

        // 1. 监听前后台切换生命周期
        if (lifecycleRegistered.compareAndSet(false, true)) {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

                override fun onActivityStarted(activity: Activity) {
                    val count = startedActivityCount.incrementAndGet()
                    if (count == 1) {
                        isAppInBackground.set(false)
                        mainHandler?.removeCallbacks(bgMaintenanceRunnable)
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    isAppInBackground.set(false)
                }

                override fun onActivityPaused(activity: Activity) {}

                override fun onActivityStopped(activity: Activity) {
                    val count = startedActivityCount.decrementAndGet().coerceAtLeast(0)
                    startedActivityCount.set(count)
                    if (count == 0) {
                        isAppInBackground.set(true)
                        if (isEnabled) {
                            keepAppRuntimeMsgActive()
                            mainHandler?.removeCallbacks(bgMaintenanceRunnable)
                            mainHandler?.postDelayed(bgMaintenanceRunnable, BG_CLEANUP_DELAY_MS)
                        }
                    }
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

                override fun onActivityDestroyed(activity: Activity) {}
            })
        }

        // 2. Hook AppRuntime.onRunningBackground，保持消息接收活跃（isBackground_Stop = false）
        runCatching {
            fIsBackgroundStop = AppRuntime::class.java.getDeclaredField("isBackground_Stop").apply {
                isAccessible = true
            }
        }
        runCatching {
            fIsBackgroundPause = AppRuntime::class.java.getDeclaredField("isBackground_Pause").apply {
                isAccessible = true
            }
        }
        runCatching {
            for (method in AppRuntime::class.java.declaredMethods) {
                if (method.name == "onRunningBackground") {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!isEnabled) return
                            val rt = param.thisObject ?: return
                            try {
                                // 保留 isBackground_Pause = true 以正常触发系统状态栏通知，
                                // 但将 isBackground_Stop 置回 false，防止消息拉取与同步管线在后台停摆
                                fIsBackgroundPause?.setBoolean(rt, true)
                                fIsBackgroundStop?.setBoolean(rt, false)
                            } catch (t: Throwable) {
                                traceError(t)
                            }
                        }
                    })
                }
            }
        }

        // 3. 限制后台 onTrimMemory 等级，释放 UI 缓存但防止卸载消息服务
        runCatching {
            val onTrimMemory = Application::class.java.getDeclaredMethod(
                "onTrimMemory",
                Int::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(onTrimMemory, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled || !isAppInBackground.get()) return
                    val level = param.args[0] as? Int ?: return
                    if (level > ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                        param.args[0] = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                    }
                }
            })
        }

        // 4. 拦截后台 GuardManager 等触发的主进程自杀
        initProcessKillProtection()

        // 5. 拦截后台非核心子进程 Service 的启动与绑定
        initBackgroundServiceSuppression()

        // 若当前为后台冷启动，安排一次后台巡检
        mainHandler?.postDelayed(bgMaintenanceRunnable, BG_CLEANUP_DELAY_MS)
    }

    private fun keepAppRuntimeMsgActive() {
        val rt = runCatching { AppRuntimeHelper.getAppRuntime() }.getOrNull() ?: return
        try {
            if (fIsBackgroundStop?.getBoolean(rt) == true) {
                fIsBackgroundStop?.setBoolean(rt, false)
            }
        } catch (_: Throwable) {
        }
    }

    private fun initProcessKillProtection() {
        runCatching {
            val killProcess = Process::class.java.getDeclaredMethod(
                "killProcess",
                Int::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(killProcess, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled || !isAppInBackground.get()) return
                    val pid = param.args[0] as? Int ?: return
                    if (pid == Process.myPid() && shouldBlockSelfKillInBackground()) {
                        Log.d("$TAG: blocked background self-killProcess($pid) to keep message receiver alive")
                        param.result = null
                    }
                }
            })
        }
        runCatching {
            val runtimeExit = Runtime::class.java.getDeclaredMethod(
                "exit",
                Int::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(runtimeExit, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled || !isAppInBackground.get()) return
                    if (shouldBlockSelfKillInBackground()) {
                        Log.d("$TAG: blocked background Runtime.exit() to keep message receiver alive")
                        param.result = null
                    }
                }
            })
        }
    }

    private fun shouldBlockSelfKillInBackground(): Boolean {
        val stack = Throwable().stackTrace
        for (element in stack) {
            val cls = element.className ?: continue
            val method = element.methodName ?: ""
            if (ALLOW_EXIT_STACK_KEYWORDS.any { cls.contains(it, ignoreCase = true) || method.contains(it, ignoreCase = true) }) {
                return false
            }
        }
        return true
    }

    private fun initBackgroundServiceSuppression() {
        runCatching {
            val startService = ContextWrapper::class.java.getDeclaredMethod(
                "startService",
                Intent::class.java
            )
            XposedBridge.hookMethod(startService, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled || !isAppInBackground.get()) return
                    val intent = param.args[0] as? Intent ?: return
                    if (shouldBlockBackgroundServiceIntent(intent)) {
                        Log.d("$TAG: suppressed background startService: ${intent.component?.className}")
                        param.result = null
                    }
                }
            })
        }
        runCatching {
            val bindService = ContextWrapper::class.java.getDeclaredMethod(
                "bindService",
                Intent::class.java,
                ServiceConnection::class.java,
                Int::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(bindService, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isEnabled || !isAppInBackground.get()) return
                    val intent = param.args[0] as? Intent ?: return
                    if (shouldBlockBackgroundServiceIntent(intent)) {
                        Log.d("$TAG: suppressed background bindService: ${intent.component?.className}")
                        param.result = false
                    }
                }
            })
        }
    }

    private fun shouldBlockBackgroundServiceIntent(intent: Intent): Boolean {
        val className = intent.component?.className ?: return false
        if (ALLOWED_CORE_COMPONENT_KEYWORDS.any { className.contains(it) }) {
            return false
        }
        return BLOCKED_BG_COMPONENT_KEYWORDS.any { className.contains(it, ignoreCase = true) }
    }

    /**
     * 清理除主进程（`packageName`）与推送长连接进程（`packageName:MSF`）之外的附属子进程，
     * 避免 `:tool`、`:peak`、`:qzone`、`:mini*`、`:wxa_container*`、`:video` 等常驻后台耗电。
     */
    private fun suppressAuxiliaryProcesses() {
        val app = hostInfo.application
        val pkgName = hostInfo.packageName
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val running = runCatching { am.runningAppProcesses }.getOrNull() ?: return
        val myPid = Process.myPid()
        val childPrefix = "$pkgName:"
        val msfProcessName = "$pkgName:MSF"

        for (proc in running) {
            val procName = proc?.processName ?: continue
            if (proc.pid == myPid) continue
            if (procName.startsWith(childPrefix) && !procName.equals(msfProcessName, ignoreCase = true)) {
                runCatching {
                    Process.killProcess(proc.pid)
                    Log.d("$TAG: killed auxiliary background process $procName (pid=${proc.pid})")
                }
            }
        }
    }

    private fun shouldSuppressPowerInCurrentProcess(): Boolean {
        if (!isEnabled) return false
        if (SyncUtils.isMainProcess()) {
            return isAppInBackground.get()
        }
        // 在 :MSF 进程中，当设备息屏或主进程处于后台时限制唤醒锁超时
        val pm = hostInfo.application.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return if (pm != null) !pm.isInteractive else true
    }

    /**
     * 限制后台状态下的 `PowerManager.WakeLock` 持有时长，防止无限期持有锁导致无法进入深度休眠。
     */
    private fun initWakeLockSuppressionHooks() {
        runCatching {
            val acquireNoArg = PowerManager.WakeLock::class.java.getDeclaredMethod("acquire")
            val acquireTimed = PowerManager.WakeLock::class.java.getDeclaredMethod(
                "acquire",
                Long::class.javaPrimitiveType
            )
            XposedBridge.hookMethod(acquireNoArg, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (wakeLockReentrancyGuard.get() == true) return
                    if (shouldSuppressPowerInCurrentProcess()) {
                        val wl = param.thisObject as? PowerManager.WakeLock ?: return
                        param.result = null
                        wakeLockReentrancyGuard.set(true)
                        try {
                            wl.acquire(DEFAULT_BG_WAKELOCK_MS)
                        } catch (_: Throwable) {
                        } finally {
                            wakeLockReentrancyGuard.set(false)
                        }
                    }
                }
            })
            XposedBridge.hookMethod(acquireTimed, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (shouldSuppressPowerInCurrentProcess()) {
                        val timeout = param.args[0] as? Long ?: return
                        if (timeout <= 0L || timeout > MAX_BG_WAKELOCK_MS) {
                            param.args[0] = MAX_BG_WAKELOCK_MS
                        }
                    }
                }
            })
        }
    }

    /**
     * 屏蔽后台状态下的性能统计与埋点上报，减少后台 CPU 和无线电唤醒。
     */
    private fun initTelemetrySuppressionHooks() {
        runCatching {
            val kStat = Initiator.load("com.tencent.mobileqq.statistics.StatisticCollector") ?: return@runCatching
            for (m in kStat.declaredMethods) {
                if (m.name == "collectPerformance" && m.returnType == Void.TYPE) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldSuppressPowerInCurrentProcess()) {
                                param.result = null
                            }
                        }
                    })
                }
            }
        }
        runCatching {
            val kBeacon = Initiator.load("com.tencent.beacon.event.UserAction") ?: return@runCatching
            for (m in kBeacon.declaredMethods) {
                if (m.name == "onUserAction" && m.returnType == Boolean::class.javaPrimitiveType) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldSuppressPowerInCurrentProcess()) {
                                param.result = true
                            }
                        }
                    })
                }
            }
        }
    }
}
