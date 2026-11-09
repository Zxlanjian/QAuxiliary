/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * Copyright (C) 2019-2022 qwq233@qwq2333.top
 * https://github.com/cinit/QAuxiliary
 *
 * This software is non-free but opensource software: you can redistribute it
 * and/or modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either
 * version 3 of the License, or any later version and our eula as published
 * by QAuxiliary contributors.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * and eula along with this software.  If not, see
 * <https://www.gnu.org/licenses/>
 * <https://github.com/cinit/QAuxiliary/blob/master/LICENSE.md>.
 */

package me.singleneuron.data

import io.github.qauxv.util.Log
import io.github.qauxv.util.encodeToJson
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

@Serializable
data class StructMsgData(
    var prompt: String,
    var news: StructMsgNewsData,
    var config: StructMsgConfigData,
    var extra: String = "",
) {

    var app: String = "com.tencent.structmsg"
    var desc: String = "新闻"
    var view: String = "news"
    var ver: String = "0.0.0.1"
    var meta: Map<String, StructMsgNewsData> = mapOf(
        "news" to news
    )

    companion object {
        private val convertedArkMessages: MutableSet<Any> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        @Volatile
        private var toAppXmlMethod: Method? = null

        @Volatile
        private var fromAppXmlMethod: Method? = null

        @Volatile
        private var mExtraField: Field? = null

        @Volatile
        private var mExtraFieldResolved: Boolean = false

        @Volatile
        private var arkContainerField: Field? = null

        @Volatile
        private var arkContainerFieldResolved: Boolean = false

        @JvmStatic
        fun fromMiniApp(miniApp: MiniAppArkData): StructMsgData {
            val detail = miniApp.meta["detail_1"]
                ?: miniApp.meta["news"]
                ?: miniApp.meta["miniapp"]
                ?: miniApp.meta.values.firstOrNull()
                ?: MiniAppArkDetailData()
            val jumpUrl = detail.qqdocurl.ifEmpty { detail.jumpUrl.ifEmpty { detail.url } }
            val preview = detail.preview.ifEmpty { detail.icon }
            val title = detail.title.ifEmpty { detail.desc }.ifEmpty { miniApp.prompt }
            val desc = detail.desc.ifEmpty { detail.title }.ifEmpty { miniApp.prompt }
            val tag = detail.tag.ifEmpty { detail.title }.ifEmpty { "QQ小程序" }
            return StructMsgData(
                prompt = miniApp.prompt.ifEmpty { "[QQ小程序]$title" },
                news = StructMsgNewsData(
                    desc = desc,
                    jumpUrl = jumpUrl,
                    preview = preview,
                    tag = tag,
                    title = title,
                ),
                config = StructMsgConfigData(
                    ctime = miniApp.config.ctime
                ),
                extra = ""
            )
        }

        /**
         * 将任意 Ark 卡片 JSON（例如 `com.tencent.tuwen.lua`、`com.tencent.miniapp*` 等）
         * 转换为旧版 QQ（如 8.8.50）内置 libark 原生支持的 `com.tencent.structmsg` (`view = "news"`) 卡片数据。
         */
        @JvmStatic
        fun fromArkJsonObject(jsonObj: JSONObject): StructMsgData? {
            val metaObj = jsonObj.optJSONObject("meta") ?: return null
            val detail1 = metaObj.optJSONObject("detail_1")
            val prompt = jsonObj.optString("prompt")

            val desc: String
            val jumpUrl: String
            val preview: String
            val tag: String
            val title: String
            val detailForCtime: JSONObject

            if (detail1 != null) {
                detailForCtime = detail1
                val rawTitle = detail1.optString("title")
                val rawDesc = detail1.optString("desc")
                jumpUrl = detail1.optString("qqdocurl")
                    .ifEmpty { detail1.optString("jumpUrl") }
                    .ifEmpty { detail1.optString("url") }
                    .ifEmpty { jsonObj.optString("sourceUrl") }
                preview = detail1.optString("preview")
                    .ifEmpty { detail1.optString("icon") }
                title = rawTitle.ifEmpty { rawDesc }.ifEmpty { prompt }
                desc = rawDesc.ifEmpty { rawTitle }.ifEmpty { prompt }
                tag = detail1.optString("tag").ifEmpty { rawTitle }.ifEmpty { "QQ小程序" }
            } else {
                val detail = metaObj.optJSONObject("news")
                    ?: metaObj.optJSONObject("miniapp")
                    ?: metaObj.optJSONObject("music")
                    ?: metaObj.keys().asSequence().mapNotNull { metaObj.optJSONObject(it) }.firstOrNull()
                    ?: return null
                detailForCtime = detail
                val rawTitle = detail.optString("title")
                val rawDesc = detail.optString("desc")
                jumpUrl = detail.optString("jumpUrl")
                    .ifEmpty { detail.optString("qqdocurl") }
                    .ifEmpty { detail.optString("url") }
                    .ifEmpty { detail.optString("musicUrl") }
                    .ifEmpty { jsonObj.optString("sourceUrl") }
                preview = detail.optString("preview")
                    .ifEmpty { detail.optString("tagIcon") }
                    .ifEmpty { detail.optString("icon") }
                    .ifEmpty { detail.optString("cover") }
                title = rawTitle.ifEmpty { rawDesc }.ifEmpty { prompt }
                desc = rawDesc.ifEmpty { rawTitle }.ifEmpty { prompt }
                tag = detail.optString("tag")
                    .ifEmpty { jsonObj.optString("sourceName") }
                    .ifEmpty { rawTitle }
                    .ifEmpty { "分享" }
            }

            if (title.isEmpty() && desc.isEmpty() && jumpUrl.isEmpty()) {
                return null
            }

            val configObj = jsonObj.optJSONObject("config")
            val ctime = configObj?.optLong("ctime")?.takeIf { it > 0L }
                ?: detailForCtime.optLong("ctime").takeIf { it > 0L }
                ?: (System.currentTimeMillis() / 1000L)

            return StructMsgData(
                prompt = prompt.ifEmpty { "[分享]$title" },
                news = StructMsgNewsData(
                    desc = desc,
                    jumpUrl = jumpUrl,
                    preview = preview,
                    tag = tag,
                    title = title,
                ).apply {
                    app_type = 1
                    appid = null
                },
                config = StructMsgConfigData(
                    ctime = ctime
                ).apply {
                    autosize = true
                    forward = true
                    type = "normal"
                },
                extra = ""
            )
        }

        /**
         * 判断指定 Ark JSON 是否需要转换为 `com.tencent.structmsg` (`view = "news"`) 格式。
         */
        @JvmStatic
        fun shouldConvertToStructMsg(jsonObj: JSONObject): Boolean {
            val app = jsonObj.optString("app")
            val view = jsonObj.optString("view")
            if (app != "com.tencent.structmsg" || view != "news") {
                return true
            }
            if (jsonObj.optString("extra").isNotEmpty()) {
                return true
            }
            val newsObj = jsonObj.optJSONObject("meta")?.optJSONObject("news")
            if (newsObj != null && !newsObj.isNull("appid")) {
                return true
            }
            val configObj = jsonObj.optJSONObject("config")
            if (configObj != null && (!configObj.optBoolean("autosize", false) || configObj.has("token"))) {
                return true
            }
            return false
        }

        /**
         * 将 `MessageForArkApp` 内部的 `ark_app_message` 原地转换为 `com.tencent.structmsg` (`view = "news"`) 卡片格式。
         *
         * @return true 表示该消息现已为可由旧版 ArkAppView 渲染的 `com.tencent.structmsg` 卡片（原样可渲染或已成功转换）；
         *         false 表示该 Ark 消息不含可转换的卡片结构。
         */
        @JvmStatic
        fun tryConvertArkAppMessage(arkAppMsg: Any?, messageForArkApp: Any? = null): Boolean {
            if (arkAppMsg == null) return false
            if (convertedArkMessages.contains(arkAppMsg)) return true
            return try {
                val toXml = toAppXmlMethod ?: arkAppMsg.javaClass
                    .getDeclaredMethod("toAppXml")
                    .apply { isAccessible = true }
                    .also { toAppXmlMethod = it }
                val json = toXml.invoke(arkAppMsg) as? String ?: return false
                if (json.isEmpty()) return false
                val jsonObj = JSONObject(json)
                if (!shouldConvertToStructMsg(jsonObj)) {
                    convertedArkMessages.add(arkAppMsg)
                    return true
                }
                val structMsgData = fromArkJsonObject(jsonObj) ?: return false
                val structMsgJson = structMsgData.toString()
                val fromXml = fromAppXmlMethod ?: arkAppMsg.javaClass
                    .getDeclaredMethod("fromAppXml", String::class.java)
                    .apply { isAccessible = true }
                    .also { fromAppXmlMethod = it }
                val ok = fromXml.invoke(arkAppMsg, structMsgJson) as? Boolean ?: false
                if (ok) {
                    clearArkExtraField(arkAppMsg)
                    convertedArkMessages.add(arkAppMsg)
                    if (messageForArkApp != null) {
                        resetCachedArkContainer(messageForArkApp)
                    }
                    true
                } else {
                    false
                }
            } catch (t: Throwable) {
                Log.e(t)
                false
            }
        }

        private fun clearArkExtraField(arkAppMsg: Any) {
            try {
                if (!mExtraFieldResolved) {
                    mExtraField = runCatching {
                        arkAppMsg.javaClass.getDeclaredField("mExtra").apply {
                            isAccessible = true
                        }
                    }.getOrNull()
                    mExtraFieldResolved = true
                }
                mExtraField?.set(arkAppMsg, "")
            } catch (_: Throwable) {
            }
        }

        private fun resetCachedArkContainer(messageForArkApp: Any) {
            try {
                if (!arkContainerFieldResolved) {
                    arkContainerField = runCatching {
                        messageForArkApp.javaClass.getDeclaredField("arkContainer").apply {
                            isAccessible = true
                        }
                    }.getOrNull()
                    arkContainerFieldResolved = true
                }
                arkContainerField?.set(messageForArkApp, null)
            } catch (_: Throwable) {
            }
        }
    }

    override fun toString(): String = this.encodeToJson()
}

@Serializable
data class StructMsgNewsData(
    var desc: String,
    var jumpUrl: String,
    var preview: String,
    var tag: String,
    var title: String,
) {

    var app_type: Int = 1
    var appid: String? = null

}

@Serializable
data class StructMsgConfigData(
    var ctime: Long,
) {

    var autosize: Boolean = true
    var forward: Boolean = true
    var type: String = "normal"
}
