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

package me.hd.hook.simplify.chat.other

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.classExists
import io.github.qauxv.util.requireMinQQVersion
import me.hd.util.hookBeforeIfEnabled
import me.hd.util.name
import me.hd.util.parameters
import me.hd.util.singleMethod
import me.hd.util.toHostClass
import xyz.nextalone.util.isPrivate

@FunctionHookEntry
@UiItemAgentEntry
object DisableSwipeRight : CommonSwitchFunctionHook() {
    override val name = "屏蔽聊天右滑"
    override val description = "屏蔽聊天右滑显示界面[群聊|群应用][好友|亲密关系]"

    // QQ 8.8.50 用的是老架构 activity.aio.drawer.*（TroopAppShortcutDrawer / IntimateInfoChatDrawer
    // 挂在 com.tencent.mobileqq.widget.DrawerFrame 上），功能本体存在，因此下限放宽到 8.8.50。
    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.CHAT_OTHER
    override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_8_50)

    override fun initOnce(): Boolean {
        if (classExists("com.tencent.aio.frame.drawer.DrawerFrameViewGroup")) {
            // NT 架构（9.x）
            "com.tencent.aio.frame.drawer.DrawerFrameViewGroup".toHostClass()
                .singleMethod {
                    isPrivate &&
                        parameters(Int::class.java)
                }.hookBeforeIfEnabled(this) { param ->
                    val scrollX = param.args[0] as Int
                    if (scrollX > 0) {
                        param.args[0] = 0
                    }
                }
        } else {
            // QQ 8.8.50：DrawerFrame 是右滑抽屉容器，setDrawerEnabled(false) 后无法再滑出
            "com.tencent.mobileqq.widget.DrawerFrame".toHostClass()
                .singleMethod {
                    name("setDrawerEnabled") &&
                        parameters(java.lang.Boolean.TYPE)
                }.hookBeforeIfEnabled(this) { param ->
                    param.args[0] = false
                }
        }
        return true
    }
}
