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

import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.github.kyuubiran.ezxhelper.utils.Hooker
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import org.json.JSONObject
import xyz.nextalone.util.throwOrTrue
import java.lang.reflect.Field

/**
 * Ark 卡片降级为可点击文本。
 *
 * 背景：8.8.50 的 Ark 渲染链路是
 *   MessageForArkApp → ArkAppItemBubbleBuilder.getView
 *     → new ArkAioContainerWrapper().a(appName, appView, appMinVersion, metaList, density, msg, session)
 *     → ArkAppView（native libark 容器）真正渲染
 * Java 层 **没有任何「app 不支持」的判定分支**：`ArkAioContainerWrapper.a(...)` 虽然返回 boolean，
 * 但 getView 在 `0x011b` 调用后没有 move-result，返回值被直接丢弃。也就是说
 * 「未知 app / view 不支持」的降级发生在 native Ark 容器内部，Java 侧无从 hook。
 *
 * 因此本 hook 不去改渲染决策，而是在 getView **进入前**直接换掉返回值：
 * 用 ArkAppMessage 里已有的文本字段拼一个可点击 TextView，
 *   文本：promptText → mText → compatibleText（依次兜底）
 *   点击：metaList 里第一个 view 对象的 jumpUrl → mSourceUrl
 * 这样即使 Ark 容器渲染不出来，用户也能看到分享标题并点开链接。
 */
@FunctionHookEntry
@UiItemAgentEntry
object ArkFallbackToText : CommonSwitchFunctionHook("qauxv_ark_fallback_to_text") {

    private const val TAG = "ArkFallbackToText"
    private const val CLS_BUILDER = "com.tencent.mobileqq.activity.aio.item.ArkAppItemBubbleBuilder"
    private const val FIELD_ARK_MSG = "ark_app_message"

    override val name = "Ark 卡片降级为文本"

    override val description = "Ark 卡片在 8.8.50 渲染不出来时，改用文本气泡显示分享标题，并支持点击打开链接。" +
        "文本取自 ArkAppMessage.promptText / mText / compatibleText，链接取自 metaList 的 jumpUrl 或 mSourceUrl。" +
        "开启后所有 Ark 消息都会变成文本气泡（不再显示卡片）。"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    /** 沿继承链按名找字段，找不到返回 null。 */
    private fun findField(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            findField(target.javaClass, name)?.get(target)
        } catch (t: Throwable) {
            Log.e(t)
            null
        }
    }

    private fun readString(target: Any?, name: String): String? =
        (readField(target, name) as? String)?.takeIf { it.isNotEmpty() }

    /** metaList 形如 {"news":{"title":"…","jumpUrl":"…"}}，取第一个 view 对象的 jumpUrl。 */
    private fun extractJumpUrl(metaList: String?): String? {
        if (metaList.isNullOrEmpty()) return null
        return try {
            val root = JSONObject(metaList)
            val key = root.keys().asSequence().firstOrNull() ?: return null
            root.optJSONObject(key)?.optString("jumpUrl")?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun openUrl(view: View, url: String?) {
        if (url.isNullOrEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            view.context.startActivity(intent)
        } catch (t: Throwable) {
            traceError(t)
        }
    }

    private val replaceWithText: Hooker = { param ->
        try {
            val message = param.args?.getOrNull(0)
            // args[3] 是 BaseChatItemLayout（ViewGroup），用它拿 Context 最稳
            val host = param.args?.getOrNull(3) as? View
            val context = host?.context
            val arkMessage = readField(message, FIELD_ARK_MSG)

            val text = readString(arkMessage, "promptText")
                ?: readString(arkMessage, "mText")
                ?: readString(arkMessage, "compatibleText")

            if (context != null && text != null) {
                val url = extractJumpUrl(readString(arkMessage, "metaList"))
                    ?: readString(arkMessage, "mSourceUrl")
                val textView = TextView(context).apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    gravity = Gravity.CENTER_VERTICAL
                    val pad = (12 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                    maxLines = 4
                    if (url != null) {
                        setOnClickListener { openUrl(this, url) }
                    }
                }
                param.result = textView
                Log.d("$TAG: degraded ark ${readString(arkMessage, "appName")}/${readString(arkMessage, "appView")} to text, url=$url")
            }
        } catch (t: Throwable) {
            traceError(t)
        }
    }

    override fun initOnce(): Boolean = throwOrTrue {
        val clazz = Initiator.loadClass(CLS_BUILDER)

        // getView 被混淆成 a(...)，按「5 个参数 + 返回 View」定位
        val getView = clazz.declaredMethods.firstOrNull {
            it.parameterTypes.size == 5 && it.returnType == View::class.java
        } ?: throw NoSuchMethodException(
            "getView(ChatMessage,ViewHolder,View,BaseChatItemLayout,OnLongClickAndTouchListener) not found in ${clazz.name}"
        )

        getView.hookBefore(replaceWithText)
        Log.d("$TAG: hooked ${clazz.name}.${getView.name}(${getView.parameterTypes.joinToString { it.simpleName }})")
    }
}
