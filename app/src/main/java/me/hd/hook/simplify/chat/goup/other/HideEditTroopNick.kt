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

package me.hd.hook.simplify.chat.goup.other

import android.view.ViewGroup
import android.widget.EditText
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import me.hd.util.hookAfterIfEnabled
import me.hd.util.name
import me.hd.util.parameters
import me.hd.util.singleMethod
import me.hd.util.toHostClass

@FunctionHookEntry
@UiItemAgentEntry
object HideEditTroopNick : CommonSwitchFunctionHook() {
    override val name = "隐藏编辑群昵称装扮"
    override val description = "对编辑群昵称界面的装扮布局进行简单隐藏"
    override val uiItemLocation = FunctionEntryRouter.Locations.Simplify.CHAT_GROUP_OTHER
    override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_9_88)

    override fun initOnce(): Boolean {
        // QQ 8.8.50: 方法名由 c 变为 a，原条件 name("c") 导致 singleMethod 匹配为空并抛 NoSuchElementException。
        // 改为只按参数类型匹配（8.8.50 命中 a(Landroid/view/ViewGroup;Landroid/widget/EditText;Landroid/view/ViewGroup;)V）。
        val method = "com.tencent.mobileqq.activity.editservice.EditTroopMemberNickService".toHostClass()
            .declaredMethods.firstOrNull { m ->
                val p = m.parameterTypes
                p.size == 3 && p[0] == ViewGroup::class.java &&
                    p[1] == EditText::class.java && p[2] == ViewGroup::class.java
            }
        if (method == null) {
            Log.e("HideEditTroopNick: 8.8.50 找不到 (ViewGroup,EditText,ViewGroup)V 方法")
            return false
        }
        method.isAccessible = true
        Log.d("HideEditTroopNick: hooked ${method.name}")
        method.hookAfterIfEnabled(this) { param ->
            val viewGroup = param.args[2] as ViewGroup
            viewGroup.visibility = ViewGroup.GONE
        }
        return true
    }
}
