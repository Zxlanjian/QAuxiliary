/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2023 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the qwq233 Universal License
 * as published on https://github.com/qwq233/license; either
 * version 2 of the License, or any later version and our EULA as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * qwq233 Universal License for more details.
 *
 * See
 * <https://github.com/qwq233/license>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package com.xiaoniu.hook

import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Log
import io.github.qauxv.util.QQVersion
import io.github.qauxv.util.classExists
import io.github.qauxv.util.dexkit.DexKit
import io.github.qauxv.util.dexkit.DexKitTarget
import io.github.qauxv.util.dexkit.QQSettingMeABTestHelper_isV9ExpGroup
import io.github.qauxv.util.requireMinQQVersion
import xyz.nextalone.util.throwOrTrue

/**
 * QQ 8.8.50(2324) 不可修判定：
 *
 * 「禁止新样式侧滑栏」的全部实现都压在一个 AB 开关上 —— `QQSettingMeABTestHelper.isV9ExpGroup()`。
 * 8.8.50 上这个开关连宿主类都没有：
 *  - `rg QQSettingMeABTestHelper analysis/methods_8.8.50.txt` → 0 命中；
 *  - `rg -a "isV9ExpGroup"` 扫 `apks/dex/` 下 23 个 dex → 0 命中；
 *  - 同家族的 `com.tencent.mobileqq.activity.QQSettingMeViewV9` / `com.tencent.mobileqq.QQSettingMeViewV9`
 *    也 0 命中，说明该版本根本没有 V9 侧滑栏实现，这个功能在 8.8.50 上语义为空。
 *
 * 没有替代 hook 点可找（不存在「新样式」，也就无所谓禁不禁），因此：
 *  1. 预扫描列表在 < 8.9.90 时置空，避免 DexKit 0 命中后 `requireMethodFromCache`
 *     抛 `NoSuchMethodException: DexTarget: QQSettingMeABTestHelper_isV9ExpGroup`；
 *  2. `isAvailable` 用 `classExists` 探测宿主类，不满足时条目直接从设置里消失；
 *  3. `initOnce` 里再兜一层，历史配置残留 `enabled=true` 也不会抛异常。
 */
private fun resolveV9Targets(): Array<DexKitTarget> = try {
    if (requireMinQQVersion(QQVersion.QQ_8_9_90)) arrayOf(QQSettingMeABTestHelper_isV9ExpGroup) else emptyArray()
} catch (t: Throwable) {
    emptyArray()
}

@FunctionHookEntry
@UiItemAgentEntry
object DisableQQSettingMeV9 : CommonSwitchFunctionHook(targets = resolveV9Targets()) {

    override val name = "禁止新样式侧滑栏"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.SLIDING_UI

    // QQ 8.8.50 没有 QQSettingMeABTestHelper，也没有 V9 侧滑栏，功能无落点，直接判不可用。
    override val isAvailable: Boolean = classExists("com.tencent.mobileqq.QQSettingMeABTestHelper")

    override fun initOnce() = throwOrTrue {
        if (!classExists("com.tencent.mobileqq.QQSettingMeABTestHelper")) {
            Log.w("DisableQQSettingMeV9: QQSettingMeABTestHelper 不存在于当前版本，功能跳过")
            return@throwOrTrue
        }
        DexKit.requireMethodFromCache(QQSettingMeABTestHelper_isV9ExpGroup).hookReturnConstant(false)
    }
}
