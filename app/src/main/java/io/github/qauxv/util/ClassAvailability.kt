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

package io.github.qauxv.util

/**
 * 判断宿主里是否存在某个类，用于 `isAvailable` 门。
 *
 * 背景：`isAvailable` 决定功能是否展示、是否初始化。仅用版本号做门是不够的——同一个版本区间里
 * 也可能有功能依赖的类被整体裁掉（例如 8.8.50 这类 NT 化之前的客户端里，
 * `com.tencent.qqnt.*` / `com.tencent.mobileqq.aio.*` / `com.tencent.qzone.reborn.*` 整包不存在）。
 * 这类 hook 的 `isEnabled` 一旦被写过 `true` 就会绕过默认值，每次启动都执行 `initOnce` 并抛
 * `ClassNotFoundException`，既污染错误面板也浪费启动时间。
 *
 * 用法：
 * ```
 * override val isAvailable = classExists("com.tencent.mobileqq.widget.PatchedTextView")
 * ```
 *
 * 探测本身出任何问题都返回 `false`（视为不可用），因为「类都加载不了」本来就没法 hook。
 */
@JvmOverloads
fun classExists(className: String, classLoader: ClassLoader? = null): Boolean {
    if (className.isEmpty()) {
        return false
    }
    return try {
        if (classLoader != null) {
            Class.forName(className, false, classLoader)
        } else {
            Initiator.loadClass(className)
        }
        true
    } catch (t: Throwable) {
        false
    }
}

/**
 * 同上，但类存在时返回 true 并允许附加条件，便于写成
 * `classExistsAnd("com.xxx") { requireMinQQVersion(QQVersion.QQ_8_2_0) }`。
 */
inline fun classExistsAnd(className: String, condition: () -> Boolean): Boolean =
    classExists(className) && condition()
