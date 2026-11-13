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
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import me.singleneuron.data.StructMsgData
import org.json.JSONObject
import xyz.nextalone.util.throwOrTrue
import java.lang.reflect.Field

/**
 * Ark 卡片转旧版分享卡片（兼容渲染与文本兜底）。
 *
 * 背景：8.8.50 的 Ark 渲染链路是
 *   MessageForArkApp → ArkAppItemBubbleBuilder.getView
 *     → new ArkAioContainerWrapper().a(appName, appView, appMinVersion, metaList, density, msg, session)
 *     → ArkAppView（native libark 容器）真正渲染
 *
 * 旧客户端（如 8.8.50）的 native libark 内置支持 `com.tencent.structmsg` (`view = "news"`, `ver = "0.0.0.1"`)
 * 这种标准结构化图文卡片，但高版本 QQ 下发的 `com.tencent.tuwen.lua`、`com.tencent.miniapp*` 等新版/Lua
 * Ark 卡片在 8.8.50 中不存在对应的本地包，直接渲染会提示版本不支持或白屏。
 *
 * 本 hook 在 `MessageForArkApp.doParse` 之后以及 `ArkAppItemBubbleBuilder.getView` 进入前：
 * 1. 优先参考小程序转卡片（`StructMsgData`）的结构，将 `com.tencent.tuwen.lua` 等 Ark 卡片的
 *    `ark_app_message` 原地转换为 `com.tencent.structmsg` (`view = "news"`) 卡片代码，交由原生
 *    `ArkAppView` 渲染出完整的图文卡片；
 * 2. 仅当某条 Ark 消息不含可转换的 `meta` 结构时，才回退为可点击的文本气泡兜底。
 */
@FunctionHookEntry
@UiItemAgentEntry
object ArkFallbackToText : CommonSwitchFunctionHook("qauxv_ark_fallback_to_text") {

    private const val TAG = "ArkFallbackToText"
    private const val CLS_BUILDER = "com.tencent.mobileqq.activity.aio.item.ArkAppItemBubbleBuilder"
    private const val CLS_ARK_MSG = "com.tencent.mobileqq.data.MessageForArkApp"
    private const val FIELD_ARK_MSG = "ark_app_message"

    override val name = "Ark 卡片转旧版卡片（兼容渲染）"

    override val description = "将 com.tencent.tuwen.lua、小程序等新版 Ark 卡片自动转换为旧版支持的 " +
        "com.tencent.structmsg (news) 卡片代码以正常显示图文卡片；无法转换的特殊 Ark 消息则降级为可点击文本气泡。"

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
            val detail = root.optJSONObject(key) ?: return null
            detail.optString("jumpUrl")
                .ifEmpty { detail.optString("qqdocurl") }
                .ifEmpty { detail.optString("url") }
                .takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
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

    private val afterDoParse: Hooker = { param ->
        if (isEnabled) {
            try {
                val message = param.thisObject
                val arkMessage = readField(message, FIELD_ARK_MSG)
                StructMsgData.tryConvertArkAppMessage(arkMessage, message)
            } catch (t: Throwable) {
                traceError(t)
            }
        }
    }

    private val convertOrFallbackBeforeGetView: Hooker = { param ->
        if (isEnabled) {
            try {
                val message = param.args?.getOrNull(0)
                val arkMessage = readField(message, FIELD_ARK_MSG)

                // 1. 优先尝试转换为 com.tencent.structmsg (view="news") 让原生 ArkAppView 渲染为图文卡片
                if (StructMsgData.tryConvertArkAppMessage(arkMessage, message)) {
                    Log.d("$TAG: converted ark message to com.tencent.structmsg card")
                } else {
                    // 2. 无法转换为 structmsg 卡片时，降级为可点击文本气泡兜底
                    val host = param.args?.getOrNull(3) as? View
                    val context = host?.context
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
                }
            } catch (t: Throwable) {
                traceError(t)
            }
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

        getView.hookBefore(convertOrFallbackBeforeGetView)

        // 同时 hook MessageForArkApp.doParse，使消息首次反序列化时即完成卡片结构转换（复制卡片代码等场景也一并生效）
        runCatching {
            val arkMsgClz = Initiator.loadClass(CLS_ARK_MSG)
            arkMsgClz.getDeclaredMethod("doParse").hookAfter(afterDoParse)
        }

        Log.d("$TAG: hooked ${clazz.name}.${getView.name}(${getView.parameterTypes.joinToString { it.simpleName }})")
    }
}
