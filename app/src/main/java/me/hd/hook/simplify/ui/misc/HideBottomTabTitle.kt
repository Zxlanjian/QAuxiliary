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

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.github.kyuubiran.ezxhelper.utils.findViewByCondition
import com.github.kyuubiran.ezxhelper.utils.setViewZeroSize
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import me.hd.util.hookAfterIfEnabled
import me.hd.util.name
import me.hd.util.parameterCount
import me.hd.util.returnType
import me.hd.util.singleMethod
import me.hd.util.toHostClass

@FunctionHookEntry
@UiItemAgentEntry
object HideBottomTabTitle : CommonSwitchFunctionHook() {
    override val name = "隐藏底栏标题"
    override val uiItemLocation = FunctionEntryRouter.Locations.Simplify.UI_MISC
    // 8.8.50 实测命中: Lcom/tencent/mobileqq/activity/home/impl/TabFrameControllerImpl;->generateTabItem(IIIIIII)Landroid/view/View;
    // 参数个数随版本漂移 (8.8.50=7, 8.9.88~9.0.20=11, >=9.0.30=12), 改为只锁返回类型与方法名
    override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_8_50)

    override fun initOnce(): Boolean {
        Log.d("HideBottomTabTitle: 命中 TabFrameControllerImpl.generateTabItem(IIIIIII)Landroid/view/View")
        "com.tencent.mobileqq.activity.home.impl.TabFrameControllerImpl".toHostClass()
            .singleMethod {
                returnType(View::class.java) &&
                    name("generateTabItem")
            }.hookAfterIfEnabled(this) { param ->
                val viewGroup = param.result as ViewGroup
                viewGroup.findViewByCondition {
                    it is TextView
                }?.setViewZeroSize()
            }
        return true
    }
}
