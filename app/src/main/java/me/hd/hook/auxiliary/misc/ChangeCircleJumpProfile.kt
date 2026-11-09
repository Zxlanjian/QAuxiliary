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

package me.hd.hook.auxiliary.misc

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import me.hd.util.hookBeforeIfEnabled
import me.hd.util.name
import me.hd.util.parameters
import me.hd.util.returnType
import me.hd.util.singleMethod
import me.hd.util.toHostClass

@FunctionHookEntry
@UiItemAgentEntry
object ChangeCircleJumpProfile : CommonSwitchFunctionHook() {
    override val name = "更改小世界跳转资料"
    override val description = "忽略作者设置了QQ资料卡不可见,点击头像强制跳转"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.MISC_CATEGORY
    override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_9_88)

    override fun initOnce(): Boolean {
        // QQ 8.8.50: canJumpToQQProfile / isJumpToQQProfileBeated 被 r8 混淆为 a/b/c/d，
        // 原 name(...) 条件导致 singleMethod 抛 NoSuchElementException。
        // 改为按「返回 Boolean + 单个 List 参数」筛候选；名字命中优先，否则按源码顺序取前两个（推测映射）。
        val circleClass = "com.tencent.biz.qqcircle.QCirclePluginUtil".toHostClass()
        // 原条件 returnType(Boolean::class.java) 是 java.lang.Boolean.class，
        // 而 Z 返回值的反射类型是 boolean.class（Boolean.TYPE），恒不相等 —— 改用原始类型比较。
        val candidates = circleClass.declaredMethods.filter { m ->
            (m.returnType == java.lang.Boolean.TYPE || m.returnType == java.lang.Boolean::class.java) &&
                m.parameterTypes.size == 1 && m.parameterTypes[0] == List::class.java
        }
        val canJump = candidates.firstOrNull { it.name == "canJumpToQQProfile" } ?: candidates.getOrNull(0)
        val beated = candidates.firstOrNull { it.name == "isJumpToQQProfileBeated" } ?: candidates.getOrNull(1)
        if (canJump == null || beated == null || canJump == beated) {
            Log.e("ChangeCircleJumpProfile: 8.8.50 找不到 (List)Z 候选方法, candidates=${candidates.joinToString(",") { it.name }}")
            return false
        }
        Log.d("ChangeCircleJumpProfile: hooked ${canJump.name}->true, ${beated.name}->false, candidates=${candidates.joinToString(",") { it.name }}")
        canJump.isAccessible = true
        beated.isAccessible = true
        canJump.hookBeforeIfEnabled(this) { param ->
            param.result = true
        }
        beated.hookBeforeIfEnabled(this) { param ->
            param.result = false
        }
        return true
    }
}
