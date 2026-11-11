/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2024 QAuxiliary developers
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package io.github.fusumayuki.hook

import android.view.View
import cc.ioctl.util.HookUtils.hookBeforeIfEnabled
import cc.ioctl.util.hookBeforeIfEnabled
import com.alphi.qhmk.module.HiddenVipIconForSe
import io.github.qauxv.util.xpcompat.XC_MethodHook.MethodHookParam
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log

/**
 * QQ 8.8.50(2324) 兼容：DexKit 的 `QQValueMethod` target 靠特征串 `"能量值:"` 定位
 * `com.tencent.mobileqq.vas.qqvaluecard.view.QQValuePagView` 里的方法。
 * 8.8.50 的 dex 里这个串不存在（`rg -a "能量值"` 扫 `apks/dex/` 下 23 个 dex 只在 classes11.dex 命中 1 次，
 * 且是独立的 `能量值` 词条，与 `QQValuePagView` 无关），所以 target 0 命中，
 * `DexKit.requireMethodFromCache()` 抛 `NoSuchMethodException: DexTarget: QQValueMethod`。
 *
 * 但目标方法本身在 8.8.50 是存在的（`analysis/methods_8.8.50.txt`）：
 *   Lcom/tencent/mobileqq/vas/qqvaluecard/view/QQValuePagView;->a(Ljava/lang/String;Ljava/lang/String;Z)V
 * jadx 反编译确认它就是给能量值 PagView 设数据 + setVisibility 的入口（QQValuePagView.java:231）：
 *   public void a(String str, String str2, boolean z) { ... setVisibility(0/8) ... }
 *
 * 因此改成直接反射：按「返回 void + (String, String, boolean)」签名在 QQValuePagView 里找方法，
 * 不依赖混淆名，也不走 DexKit 缓存。这是「靠其他方式实现」的正解。
 */
@FunctionHookEntry
@UiItemAgentEntry
object HideQQValue : CommonSwitchFunctionHook() {

    override val name = "隐藏QQ能量值"

    override val description = "隐藏侧滑栏与资料卡上的QQ能量值"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.SLIDING_UI

    // 目标类在 8.8.50 存在，但早期版本（< 8.4）没有 QQ 能量值，用类探测兜底。
    override val isAvailable: Boolean = try {
        Initiator.loadClass("com.tencent.mobileqq.vas.qqvaluecard.view.QQValuePagView")
        true
    } catch (t: Throwable) {
        false
    }

    override fun initOnce(): Boolean {
        val qqValueClass = Initiator.loadClass("com.tencent.mobileqq.vas.qqvaluecard.view.QQValuePagView")
        // 8.8.50: a(Ljava/lang/String;Ljava/lang/String;Z)V
        // 8.8.98: v(Ljava/lang/String;Ljava/lang/String;Z)V
        // 混淆名逐版本变，按签名匹配（该签名在类内唯一），旧名 a 优先。
        val qqValueMethod = qqValueClass.declaredMethods.firstOrNull {
            it.name == "a" && it.returnType == Void.TYPE &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == java.lang.Boolean.TYPE
        } ?: qqValueClass.declaredMethods.firstOrNull {
            it.returnType == Void.TYPE &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == java.lang.Boolean.TYPE
        } ?: run {
            Log.w("HideQQValue: QQValuePagView 中未找到 (String, String, boolean) 方法")
            return false
        }
        Log.d("HideQQValue: 命中 ${qqValueClass.name}.${qqValueMethod.name}(String,String,boolean)V")

        hookBeforeIfEnabled(this, qqValueMethod) { param: MethodHookParam ->
            (param.thisObject as View).isClickable = false
            param.setResult(null)
        }

        if (!HiddenVipIconForSe.INSTANCE.isEnabled)
            HiddenVipIconForSe.INSTANCE.optimizeQLevel(10, this)
        return true
    }

}
