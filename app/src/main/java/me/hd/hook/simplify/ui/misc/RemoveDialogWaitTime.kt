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

package me.hd.hook.simplify.ui.misc

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.SyncUtils
import io.github.qauxv.util.requireMinQQVersion
import me.hd.util.hookBeforeIfEnabled
import me.hd.util.name
import me.hd.util.parameters
import me.hd.util.singleMethod
import me.hd.util.toHostClass

@FunctionHookEntry
@UiItemAgentEntry
object RemoveDialogWaitTime : CommonSwitchFunctionHook(
    targetProc = SyncUtils.PROC_ANY
) {
    override val name = "移除弹窗等待时间"
    override val description = "移除弹窗中, 继续按钮的5s, 10s等待时间"
    override val uiItemLocation = FunctionEntryRouter.Locations.Simplify.UI_MISC
    override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_9_88)

    override fun initOnce(): Boolean {
        // QQ 8.8.50: DialogUtil 已无 createCountdownDialog，原 name+parameters 条件导致 singleMethod 抛
        // NoSuchElementException。放宽为「10 个参数且第 7 位为 int」，找不到则记录近似候选并返回 false。
        val dialogUtil = "com.tencent.mobileqq.utils.DialogUtil".toHostClass()
        // 原条件 parameters(..., Int::class.java, ...) 是 java.lang.Integer.class，
        // 而 I 参数的反射类型是 int.class（Integer.TYPE），恒不相等 —— 改用原始类型比较。
        val method = dialogUtil.declaredMethods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 10 && (p[6] == java.lang.Integer.TYPE || p[6] == Int::class.java)
        }
        if (method == null) {
            val near = dialogUtil.declaredMethods
                .filter { it.parameterTypes.contains(Int::class.java) }
                .take(3)
                .joinToString("; ") { it.name + it.parameterTypes.contentToString() }
            Log.e("RemoveDialogWaitTime: 8.8.50 找不到 10 参数且第 7 位为 int 的方法; 近似候选: $near")
            return false
        }
        method.isAccessible = true
        Log.d("RemoveDialogWaitTime: hooked ${method.name}${method.parameterTypes.contentToString()}")
        method.hookBeforeIfEnabled(this) { param ->
            param.args[6] = 0
        }
        return true
    }
}
