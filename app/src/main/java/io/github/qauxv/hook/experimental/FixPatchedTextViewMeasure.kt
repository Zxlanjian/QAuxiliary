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

import android.text.Spanned
import android.text.SpannedString
import android.widget.TextView
import cc.ioctl.util.Reflex
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import xyz.nextalone.util.throwOrTrue

/**
 * 重写 QQ 的 `PatchedTextView.onMeasure`。
 *
 * QQ 8.8.50 的实现是：
 * ```
 * try { super.onMeasure(w, h); }
 * catch (Exception e) { QLog.e(...); setText(getText().toString()); super.onMeasure(w, h); }
 * ```
 * 回退分支里的 `getText().toString()` 会把富文本降级成纯文本：行内表情、彩色文字、
 * 自定义 span 全部丢失，多行消息因此塌成一行/被折叠，且后续无法恢复。
 *
 * 本 hook 保持原有语义，但：
 * 1. 测量前把可变的 [Spanned] 文本固化成不可变的 [SpannedString]，避免 span 在测量期间
 *    被并发修改而抛异常（这正是 QQ 当初加 try/catch 的原因）；
 * 2. 回退分支改为 `setText(getText())`，保留 span，而不是 `toString()`；
 * 3. 通过 [Reflex.invokeNonVirtual] 调用 `TextView.onMeasure`，不递归回自身。
 *
 * 该修复最初以独立 LSPosed 模块（build.yml / MainModule.java, libxposed API 102）验证，
 * 现合并进模块本体并放入「实验性功能」。
 */
@FunctionHookEntry
@UiItemAgentEntry
object FixPatchedTextViewMeasure : CommonSwitchFunctionHook("qauxv_fix_patched_text_view_measure") {

    private const val TAG = "PatchedTextView"

    override val name = "修复多行消息显示折叠"

    override val description = "重写 PatchedTextView.onMeasure：测量前固化 Spanned 文本，异常回退时保留富文本，" +
        "避免 QQ 把消息文本降级成纯文本导致样式丢失与多行折叠"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    override fun initOnce(): Boolean = throwOrTrue {
        val clazz = Initiator.loadClass("com.tencent.mobileqq.widget.PatchedTextView")
        val onMeasure = clazz.getDeclaredMethod(
            "onMeasure",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val superOnMeasure = TextView::class.java.getDeclaredMethod(
            "onMeasure",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )

        // 保持 QQ 原本的 QLog 输出格式，反射失败则只走模块自己的日志
        val qLogClass = runCatching { Initiator.loadClass("com.tencent.qphone.base.util.QLog") }.getOrNull()
        val isColorLevel = qLogClass?.let {
            runCatching { it.getDeclaredMethod("isColorLevel") }.getOrNull()
        }
        val qLogE = qLogClass?.let {
            runCatching {
                it.getDeclaredMethod(
                    "e",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Throwable::class.java,
                )
            }.getOrNull()
        }

        onMeasure.hookReplace { param ->
            val self = param.thisObject as TextView
            val widthSpec = param.args[0] as Int
            val heightSpec = param.args[1] as Int
            try {
                val text = self.text
                if (text is Spanned) {
                    self.setText(SpannedString(text))
                }
                invokeSuperOnMeasure(self, superOnMeasure, widthSpec, heightSpec)
            } catch (e: Throwable) {
                logMeasureError(isColorLevel, qLogE, e)
                val text = self.text
                if (text != null) {
                    // 与 QQ 原实现不同：这里保留 span，不降级成 String
                    self.setText(text)
                    invokeSuperOnMeasure(self, superOnMeasure, widthSpec, heightSpec)
                }
            }
            null
        }
    }

    private fun invokeSuperOnMeasure(
        self: TextView,
        superOnMeasure: Method,
        widthSpec: Int,
        heightSpec: Int,
    ) {
        try {
            Reflex.invokeNonVirtual(self, superOnMeasure, widthSpec, heightSpec)
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            if (cause is Exception) {
                throw cause
            }
            throw RuntimeException(cause ?: e)
        }
    }

    private fun logMeasureError(isColorLevel: Method?, qLogE: Method?, e: Throwable) {
        Log.e("$TAG: exception thrown in PatchedTextView.onMeasure", e)
        runCatching {
            if (isColorLevel != null && qLogE != null && isColorLevel.invoke(null) == true) {
                qLogE.invoke(null, TAG, 2, "Error : Exception thrown in PatchedTextView.onMeasure", e)
            }
        }
    }
}
