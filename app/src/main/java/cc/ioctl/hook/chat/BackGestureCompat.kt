/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2026 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is an opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published by
 * QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the General Public License for more details.
 */

package cc.ioctl.hook.chat

import android.app.Activity
import android.os.Build
import android.view.KeyEvent
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import java.util.WeakHashMap

/**
 * 修复 Android 13+ 系统返回手势在 QQ 聊天页失效。
 *
 * 背景：QQ 8.8.98 的 targetSdkVersion 为 26，manifest 无 enableOnBackInvokedCallback，
 * 且整个 APK 中不存在任何 OnBackInvoked* 符号，也没有覆写 Activity.onBackPressed()。
 * 它的返回逻辑全部挂在 KeyEvent 路径上：
 *   SplashActivity.doDispatchKeyEvent(Landroid/view/KeyEvent;)Z
 *   SplashActivity.doOnKeyDown(ILandroid/view/KeyEvent;)Z
 *
 * Android 13+ 对 targetSdk<33 的应用走兼容路径：系统注册兼容 OnBackInvokedCallback，
 * 直接调用 Activity.onBackPressed()，不再合成 KEYCODE_BACK。于是：
 *   系统手势 → onBackPressed() → AndroidX dispatcher 无 callback → Activity 默认实现
 *   → SplashActivity 是 task root，不 finish、Fragment backstack 也为空 → 无任何反应。
 *
 * 本 hook 给 SplashActivity 注册真正的 OnBackInvokedCallback，把返回事件合成
 * KEYCODE_BACK 交给 QQ 自己的 doDispatchKeyEvent，恢复原有返回行为。
 */
@FunctionHookEntry
@UiItemAgentEntry
object BackGestureCompat : CommonSwitchFunctionHook() {

    override val name = "修复系统返回手势"
    override val description = "Android 13+ 下把 OnBackInvoked 返回事件桥接为 KEYCODE_BACK，" +
            "恢复 QQ 聊天页（SplashActivity）的边缘返回手势"
    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Auxiliary.CHAT_CATEGORY

    override val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && requireMinQQVersion(QQVersion.QQ_8_8_50)

    private const val SPLASH_ACTIVITY = "com.tencent.mobileqq.activity.SplashActivity"
    private const val METHOD_DISPATCH_KEY = "doDispatchKeyEvent"

    /** 防止 doOnResume 多次触发导致回调重复注册。 */
    private val registered = WeakHashMap<Activity, Boolean>()

    override fun initOnce(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val clz = Initiator.load(SPLASH_ACTIVITY) ?: return false
        val resume = clz.declaredMethods.firstOrNull {
            it.name == "doOnResume" && it.parameterCount == 0
        } ?: return false
        XposedBridge.hookMethod(resume, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val act = param.thisObject as? Activity ?: return
                installCompatCallback(act)
            }
        })
        return true
    }

    private fun installCompatCallback(act: Activity) {
        if (registered.containsKey(act)) return
        registered[act] = true
        try {
            val dispatcher = act.onBackInvokedDispatcher
            val callback = OnBackInvokedCallback {
                forwardBackAsKeyEvent(act)
            }
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
        } catch (t: Throwable) {
            registered.remove(act)
            XposedBridge.log(t)
        }
    }

    /** 合成 KEYCODE_BACK 的 down/up，交给 QQ 自己的 KeyEvent 返回处理链。 */
    private fun forwardBackAsKeyEvent(act: Activity) {
        try {
            // doDispatchKeyEvent 在 SplashActivity 里是 protected/private，getMethod 找不到，
            // 必须沿继承链 getDeclaredMethod。
            var cls: Class<*>? = act.javaClass
            while (cls != null && cls != Activity::class.java) {
                val m = runCatching {
                    cls.getDeclaredMethod(METHOD_DISPATCH_KEY, KeyEvent::class.java)
                }.getOrNull()
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(act, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                    m.invoke(act, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    return
                }
                cls = cls.superclass
            }
            XposedBridge.log("BackGestureCompat: doDispatchKeyEvent not found on ${act.javaClass.name}")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}
