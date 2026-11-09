/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package cc.ioctl.hook.experimental

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getStaticObjectAs
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.TIMVersion
import io.github.qauxv.util.requireMinQQVersion
import io.github.qauxv.util.requireMinTimVersion
import xyz.nextalone.util.throwOrTrue

@FunctionHookEntry
@UiItemAgentEntry
object ForcePhoneMode : CommonSwitchFunctionHook(targetProc = SyncUtils.PROC_ANY) {

    override val name = "强制手机模式"
    override val description = "支持 QQ8.8.50 及以上，未经测试，谨慎使用"
    override val extraSearchKeywords: Array<String> = arrayOf("phone")
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY
    override val isApplicationRestartRequired = true
    // 8.8.50 实测: Lcom/tencent/common/config/AppSetting;->d()I 是唯一无参 Int 方法 (e()/f() 返回 String),
    // 故方法名与静态字段名都做候选链兜底
    override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_8_50) || requireMinTimVersion(TIMVersion.TIM_4_0_95_BETA)

    override fun initOnce() = throwOrTrue {
        check(isAvailable) { "ForcePhoneMode is not available" }
        val appSettingClass = Initiator.loadClass("com.tencent.common.config.AppSetting")
        val appIdMethodName = when {
            requireMinQQVersion(QQVersion.QQ_9_2_30) -> "e"
            requireMinQQVersion(QQVersion.QQ_8_9_15) -> "f"
            else -> "d" // 8.8.50: e()/f() 已改为返回 String, 仅 d()I 为无参 Int 方法
        }
        appSettingClass.findMethod {
            returnType == Int::class.java && name == appIdMethodName
        }.hookAfter {
            val (appIdPhone, appIdPad) = Pair(
                when {
                    requireMinTimVersion(TIMVersion.TIM_4_0_95_BETA) -> "f"
                    requireMinQQVersion(QQVersion.QQ_9_3_20) -> "f"
                    requireMinQQVersion(QQVersion.QQ_9_3_5) -> "a"
                    requireMinQQVersion(QQVersion.QQ_9_2_65) -> "e"
                    requireMinQQVersion(QQVersion.QQ_9_2_30) -> "f"
                    requireMinQQVersion(QQVersion.QQ_9_2_15) -> "g"
                    requireMinQQVersion(QQVersion.QQ_9_1_50) -> "f"
                    else -> "e"
                },
                when {
                    requireMinTimVersion(TIMVersion.TIM_4_0_95_BETA) -> "g"
                    requireMinQQVersion(QQVersion.QQ_9_3_20) -> "g"
                    requireMinQQVersion(QQVersion.QQ_9_3_5) -> "b"
                    requireMinQQVersion(QQVersion.QQ_9_2_65) -> "f"
                    requireMinQQVersion(QQVersion.QQ_9_2_30) -> "g"
                    requireMinQQVersion(QQVersion.QQ_9_2_15) -> "h"
                    requireMinQQVersion(QQVersion.QQ_9_1_50) -> "g"
                    else -> "f"
                },
            )
            // 静态字段名随版本漂移, 按候选链取第一个可读的静态 int
            fun readStaticInt(primary: String): Int? {
                for (candidate in arrayOf(primary, "e", "f", "g", "d")) {
                    val v = runCatching { appSettingClass.getStaticObjectAs<Int>(candidate) }.getOrNull()
                    if (v != null) return v
                }
                return null
            }
            val phoneId = readStaticInt(appIdPhone)
            if (phoneId == null) {
                Log.e("ForcePhoneMode: 无法定位 AppSetting 的 appId 静态字段 (尝试: $appIdPhone/e/f/g/d), 本次不替换")
            } else {
                Log.d("ForcePhoneMode: 命中方法 AppSetting.$appIdMethodName()I, 使用静态字段值 $phoneId (pad 字段候选: $appIdPad)")
                it.result = phoneId
            }
        }
    }

}
