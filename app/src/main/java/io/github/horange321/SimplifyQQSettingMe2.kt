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

package io.github.horange321

import com.github.kyuubiran.ezxhelper.utils.field
import com.github.kyuubiran.ezxhelper.utils.findField
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter.Locations.Simplify
import io.github.qauxv.util.Initiator.loadClass
import io.github.qauxv.util.Log
import xyz.nextalone.base.MultiItemDelayableHook
import xyz.nextalone.util.method
import io.github.qauxv.util.classExists
import cc.ioctl.util.HookUtils.hookAfterIfEnabled


@FunctionHookEntry
@UiItemAgentEntry
object SimplifyQQSettingMe2 : MultiItemDelayableHook(
    "SimplifyQQSettingMe2"
) {
    val map = hashMapOf(
        "d_album" to "相册",
        "d_favorite" to "收藏",
        "d_document" to "文件",
        "d_qqwallet" to "钱包",
        "d_vip_identity" to "会员中心",
        "d_decoration" to "个性装扮",
        "d_vip_card" to "免流量"
    )
    override val preferenceTitle = "新版侧滑栏精简"
    override val allItems = map.values.toSet()
    override val uiItemLocation = Simplify.SLIDING_UI

    // 8.8.50 走 legacy 路径（QQSettingMe + QQSettingMeBizBean），新版 Part 类不存在。
    override val isAvailable: Boolean = classExists("com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3") ||
        classExists("com.tencent.mobileqq.activity.QQSettingMe")


    private fun needRemove(bean: Any): Boolean {
        bean::class.java.declaredFields.forEach { f ->
            if (f.type == java.lang.String::class.java) {
                f.isAccessible = true
                if (map[f.get(bean)!! as String] in activeItems) {
                    return true
                }
            }
        }
        return false
    }


    override fun initOnce(): Boolean {
        // 新版侧滑栏（QQSettingMeMenuPanelPartV3）是 9.x 的部件化实现；
        // 8.8.50 里该类整包 0 命中（ClassNotFoundException 的直接来源），走下面的 legacy 分支。
        if (!classExists("com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3")) {
            return initLegacySettingMe()
        }

        val clazz = loadClass("com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3")
        clazz.method("onInitView")!!.hookAfter {
            val obj = it.thisObject
//ArrayList<com.tencent.mobileqq.adapter.u> com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3$bizData
            val bizDataList = clazz.findField {
                type == java.util.ArrayList::class.java
            }.run {
                isAccessible = true
                get(obj)!! as ArrayList<Any>
            }

            // 删掉要去掉的东西
            val i = bizDataList.iterator()
            while (i.hasNext()) {
                val v = i.next()
//com.tencent.mobileqq.activity.qqsettingme.config.QQSettingMeBizBean com.tencent.mobileqq.adapter.u:bean
                // 是唯一的 field，可以直接 hardcode
                val bean = v.field("a").run {
                    isAccessible = true
                    get(v)
                }
                // 有的会有 null 出现。需要特殊处理
                if (bean == null) continue
                if (needRemove(bean))
                    i.remove()
            }

//com.tencent.mobileqq.adapter.t com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3$listItemAdapter
            val listItemAdapter = clazz.findField {
                type.name.contains("adapter")
            }.run {
                isAccessible = true
                get(obj)!!
            }

            // set adapter items
            loadClass("com.tencent.biz.richframework.part.adapter.AsyncListDifferDelegationAdapter")
                .method("setItems")!!.run {
                    isAccessible = true
                    invoke(listItemAdapter, bizDataList)
                }
        }
        return true
    }

    /**
     * QQ 8.8.50 的等价实现（最小可用路径：隐藏指定条目）。
     *
     * 8.8.50 的侧滑栏是 `com.tencent.mobileqq.activity.QQSettingMe`（View 容器，非 Part），
     * 条目由 `QQSettingMeMenuConfigBean.b()` 返回的 `QQSettingMeBizBean[]` 逐个 inflate
     * （jadx: QQSettingMe.java:621-668）。其中关键一行：
     *
     *     inflate2.setVisibility(a(qQSettingMeBizBean) ? 8 : 0);
     *
     * 也就是说私有方法 `boolean QQSettingMe.a(QQSettingMeBizBean)` 返回 true 的条目会被直接
     * setVisibility(8)。这就是 8.8.50 上唯一干净、且不会破坏列表索引的隐藏点
     * （比事后 removeView 安全，也不会像 setViewZeroSize 那样留下占位）。
     *
     * 条目 id 由 `QQSettingMeBizBean.a()` 给出（`d_album` / `d_favorite` / `d_document` /
     * `d_qqwallet` / `d_vip_identity` / `d_decoration` / `d_vip_card`，均已在 8.8.50 dex 中确认存在），
     * 与本 hook 的 map key 完全一致，因此不需要改配置项。
     */
    private fun initLegacySettingMe(): Boolean {
        val settingMeClass = loadClass("com.tencent.mobileqq.activity.QQSettingMe")
        val bizBeanClass = loadClass("com.tencent.mobileqq.activity.qqsettingme.config.QQSettingMeBizBean")

        // QQSettingMeBizBean.a()Ljava/lang/String;  → bizId
        val getBizId = bizBeanClass.declaredMethods.firstOrNull {
            it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == String::class.java
        } ?: run {
            Log.w("SimplifyQQSettingMe2: QQSettingMeBizBean.a()String 未找到")
            return false
        }
        getBizId.isAccessible = true

        // private boolean QQSettingMe.a(QQSettingMeBizBean)
        val isItemHidden = settingMeClass.declaredMethods.firstOrNull {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.size == 1 && it.parameterTypes[0] == bizBeanClass
        } ?: run {
            Log.w("SimplifyQQSettingMe2: QQSettingMe.a(QQSettingMeBizBean)Z 未找到")
            return false
        }
        isItemHidden.isAccessible = true

        hookAfterIfEnabled(this, isItemHidden) { param ->
            val bean = param.args[0] ?: return@hookAfterIfEnabled
            val bizId = getBizId.invoke(bean) as? String ?: return@hookAfterIfEnabled
            if (map[bizId] in activeItems) {
                param.result = true
            }
        }
        return true
    }
}
