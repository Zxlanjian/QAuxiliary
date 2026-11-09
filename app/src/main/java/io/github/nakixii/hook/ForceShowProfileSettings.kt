/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2025 QAuxiliary developers
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

package io.github.nakixii.hook

import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.requireMinQQVersion
import xyz.nextalone.util.method

@FunctionHookEntry
@UiItemAgentEntry
object ForceShowProfileSettings : CommonSwitchFunctionHook() {
    override val name = "强制显示资料卡内所有设置"
    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.PROFILE_CATEGORY
    // 目标 Lcom/tencent/mobileqq/profilesetting/api/impl/ProfileSettingApiImpl;->getProfileDisplaySettingStateFromCard(ILcom/tencent/mobileqq/data/Card;Lcom/tencent/common/app/AppInterface;)I
    // 在 8.8.50 已存在, 放宽版本门
    override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_8_50)

    override fun initOnce(): Boolean {
        Log.d("ForceShowProfileSettings: 命中 ProfileSettingApiImpl.getProfileDisplaySettingStateFromCard(I, Card, AppInterface)I")
        Initiator.loadClass("com.tencent.mobileqq.profilesetting.api.impl.ProfileSettingApiImpl")
            .method("getProfileDisplaySettingStateFromCard")?.hookBefore { it.result = 0 }

        return true
    }
}
