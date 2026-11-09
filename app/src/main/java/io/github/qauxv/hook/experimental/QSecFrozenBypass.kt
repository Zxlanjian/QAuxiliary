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

import android.os.Bundle
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import xyz.nextalone.util.throwOrTrue

/**
 * 屏蔽 QSec / 登录风控下发的「冻结页 → 踢下线」Java 侧执行链（实验性）。
 *
 * 8.8.50 的链路（详见 analysis/qsec_detection_analysis.md）：
 *
 * ```
 * native libQSec / libturingmfa 检测环境（Xposed/Frida/maps）
 *        │ 上报（SSO cmd: MqqSafeDataRpt.MQDun / CSP 通道）
 *        ▼
 * 服务端风控判定 ──► 登录响应 TLV 1347（wlogin loginResultTLVMap）
 *        │
 *        ▼  Lmqq/app/BuiltInServlet;->bundlePutTlv543(FromServiceMsg, Bundle)
 *   Bundle["tlverror"] = TLV1347.data
 *        │  Lmqq/observer/AccountObserver;->onReceiveLoginActions(IZ,Bundle)
 *        ▼  com.tencent.mobileqq.loginregister.NotifyLoginAction;->a(QBaseActivity,AppRuntime,LoginErrorInfo)
 *   Intent{ errorUrl, errorver, tlverror, errortitle, is_need_login_with_mask, ... }
 *        │  RouteUtils → "/base/notification"
 *        ▼
 * com.tencent.mobileqq.activity.NotificationActivity
 *        ├─ FrozenNotifyDlgHelper;->a([B)Z  → GatewayVerify.RspBody.msg_rsp_lft_info.uint32_lft_forbid_area
 *        │                                     && msg_rsp_tips_info.uint32_scene_id ∈ {2,3,4} → mUnifyFrozenStyle
 *        ├─ FrozenNotifyDlgHelper;->b([B)I  → msg_rsp_tips_info.uint32_scene_id → mUnifyFrozenScene
 *        └─ kickToLoginActivity() / exitApp() → finish() + 清密码 + RouteUtils("/base/login")
 *                                              + 广播 mqq.intent.action.ACCOUNT_KICKED
 * ```
 *
 * 本 hook 只在 Java 侧「不认这条通知 / 不执行退出动作」，不改变服务端判定：
 * 会话若已被服务端作废，功能仍不可用。真正的检测绕过必须在 native 层做。
 */
@FunctionHookEntry
@UiItemAgentEntry
object QSecFrozenBypass : CommonSwitchFunctionHook("qauxv_qsec_frozen_bypass") {

    private const val TAG = "QSecFrozenBypass"

    /**
     * 拦截强度：
     * 1 = 只让 FrozenNotifyDlgHelper 判定为「非冻结样式」（页面按普通登录错误显示，仍会退出到登录页）
     * 2 = 额外在 BuiltInServlet 里摘掉 Bundle["tlverror"]（冻结样式与冻结文案整体消失）
     * 3 = 额外拦 NotificationActivity.kickToLoginActivity()（不跳登录页、不清本地密码）
     */
    private const val LEVEL = 1

    private const val CLS_DLG_HELPER = "com.tencent.mobileqq.app.security.FrozenNotifyDlgHelper"
    private const val CLS_BUILTIN_SERVLET = "mqq.app.BuiltInServlet"
    private const val CLS_NOTIFICATION_ACTIVITY = "com.tencent.mobileqq.activity.NotificationActivity"

    override val name = "屏蔽 QSec 冻结页 / 踢下线（实验）"

    override val description = "Java 层不认服务端下发的冻结通知（tlverror / GatewayVerify.RspBody），" +
        "并可选阻止跳转登录页。不改变服务端判定：会话已被作废时功能仍不可用；" +
        "真正的环境检测绕过需要在 native 层处理 libQSec / libturingmfa。"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    override fun initOnce(): Boolean = throwOrTrue {
        hookFrozenDlgHelper()
        if (LEVEL >= 2) {
            hookBuiltInServlet()
        }
        if (LEVEL >= 3) {
            hookKickToLogin()
        }
        Log.d("$TAG: initOnce done, LEVEL=$LEVEL")
    }

    /** FrozenNotifyDlgHelper.a([B)Z / b([B)I：把「是否冻结」「冻结场景」改成否/无效。 */
    private fun hookFrozenDlgHelper() {
        val clazz = Initiator.loadClass(CLS_DLG_HELPER)
        val byteArray = ByteArray::class.java

        // a([B)Z → shouldHandleByDlgHelper
        val shouldHandle = clazz.declaredMethods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == byteArray &&
                it.returnType == java.lang.Boolean.TYPE
        } ?: throw NoSuchMethodException("FrozenNotifyDlgHelper.a([B)Z not found")

        shouldHandle.hookBefore { param ->
            param.result = false
            Log.d("$TAG: shouldHandleByDlgHelper(tlverror) → false")
        }

        // b([B)I → getForbidScene
        val getScene = clazz.declaredMethods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == byteArray &&
                it.returnType == java.lang.Integer.TYPE
        } ?: throw NoSuchMethodException("FrozenNotifyDlgHelper.b([B)I not found")

        getScene.hookBefore { param ->
            param.result = -1
            Log.d("$TAG: getForbidScene(tlverror) → -1")
        }

        Log.d("$TAG: hooked ${clazz.name}.${shouldHandle.name}/${getScene.name}")
    }

    /** BuiltInServlet.bundlePutTlv543(FromServiceMsg, Bundle)：把 TLV1347 写进 Bundle 之后摘掉。 */
    private fun hookBuiltInServlet() {
        val clazz = Initiator.loadClass(CLS_BUILTIN_SERVLET)
        val putTlv = clazz.declaredMethods.firstOrNull {
            it.name == "bundlePutTlv543" && it.parameterTypes.size == 2
        } ?: clazz.declaredMethods.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[1] == Bundle::class.java
        } ?: throw NoSuchMethodException("BuiltInServlet.bundlePutTlv543(FromServiceMsg, Bundle) not found")

        putTlv.hookAfter { param ->
            (param.args.getOrNull(1) as? Bundle)?.let { bundle ->
                if (bundle.containsKey("tlverror")) {
                    bundle.remove("tlverror")
                    Log.d("$TAG: removed Bundle[tlverror] from login response")
                }
            }
        }
        Log.d("$TAG: hooked ${clazz.name}.${putTlv.name}")
    }

    /** NotificationActivity.kickToLoginActivity()：不跳登录页、不清本地密码。 */
    private fun hookKickToLogin() {
        val clazz = Initiator.loadClass(CLS_NOTIFICATION_ACTIVITY)
        val kick = clazz.getDeclaredMethod("kickToLoginActivity")
        kick.hookBefore { param ->
            param.result = null
            Log.d("$TAG: kickToLoginActivity() intercepted")
        }
        Log.d("$TAG: hooked ${clazz.name}.kickToLoginActivity()")
    }
}
