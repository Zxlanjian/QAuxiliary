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

import android.view.View
import com.github.kyuubiran.ezxhelper.utils.Hooker
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import xyz.nextalone.util.throwOrTrue
import java.io.ObjectInput
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 强制显示高版本分享卡片。
 *
 * 背景：QQ 的 structmsg 二进制体带一个 messageVersion，客户端在
 * AbsStructMsgItem/StructMsgForGeneralShare.readExternal 里按版本号分段读取字段。
 * 当服务端按更高版本的布局下发时，8.8.50 这类旧客户端会读错位，抛出
 * IOException("structmsg_version_error")，被 StructMsgForGeneralShare.readExternal 捕获后
 * 置 mMsgException = true；随后 getView 看到该标记就直接返回
 * getVersionExceptionView()（灰字气泡「当前版本导致消息展示异常，请升级QQ版本」），
 * 不再渲染通用分享卡片。
 *
 * 本 hook 在两个位置把 mMsgException 清回 false：
 *  A) readExternal 之后 —— 覆盖首次解析；
 *  B) getView 之前 —— 覆盖从 DB/缓存反序列化、不再走 readExternal 的对象。
 */
@FunctionHookEntry
@UiItemAgentEntry
object ForceShowHighVersionStructMsg :
    CommonSwitchFunctionHook("qauxv_force_show_high_version_struct_msg") {

    private const val TAG = "ForceShowHighVersionStructMsg"
    private const val CLS_GENERAL_SHARE = "com.tencent.mobileqq.structmsg.StructMsgForGeneralShare"
    private const val FIELD_EXCEPTION = "mMsgException"

    override val name = "强制显示高版本分享卡片"

    override val description = "清掉 StructMsgForGeneralShare 的 mMsgException 标记，" +
        "让 messageVersion 不匹配的二进制结构化消息走正常渲染，而不是只显示「请升级QQ」的灰字气泡。" +
        "注意：只对二进制 structmsg（msgType -2011）生效；app/view/meta 这种 Ark 消息（msgType -5008）" +
        "的渲染在 native libark 里，请配合「Ark 卡片转旧版卡片（兼容渲染）」使用。" +
        "服务端按更高版本布局下发的字段可能残缺，极端情况下会显示不完整的卡片。"

    override val uiItemLocation = FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY

    @Volatile
    private var exceptionField: Field? = null

    /** 沿继承链按名字找字段；找不到返回 null，不抛。 */
    private fun findField(start: Class<*>, name: String): Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    /**
     * 确定性定位 mMsgException。
     *
     * 该字段由 javac/kotlinc 的源码名保留，8.8.50 的 smali 里是明文
     * （`iput-boolean v0, v10, Lcom/tencent/mobileqq/structmsg/StructMsgForGeneralShare;.mMsgException:Z`），
     * 没有被 r8 改名，因此只按名精确取，**不做「唯一 boolean 字段」猜测** ——
     * 该继承链上有多个 boolean（mHasSource / mHasAdSource / mMsgException …），猜错会静默改错字段。
     *
     * 字段真实声明在父类 AbsShareMsg，必须沿继承链向上找。
     * 找不到返回 null，由调用方终止初始化。
     */
    private fun locateExceptionField(clazz: Class<*>): Field? = findField(clazz, FIELD_EXCEPTION)

    /** 沿继承链找 readExternal(Ljava/io/ObjectInput;)V。 */
    private fun findReadExternal(start: Class<*>): Method? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == "readExternal" &&
                    m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == ObjectInput::class.java
                ) {
                    return m.apply { isAccessible = true }
                }
            }
            c = c.superclass
        }
        return null
    }

    /** 沿继承链找 getView(Context,View,OnLongClickAndTouchListener,Bundle)View。 */
    private fun findGetView(start: Class<*>): Method? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name == "getView" &&
                    m.parameterTypes.size == 4 &&
                    m.returnType == View::class.java
                ) {
                    return m.apply { isAccessible = true }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun clearExceptionFlag(target: Any?) {
        if (target == null) return
        val field = exceptionField ?: return
        try {
            if (field.getBoolean(target)) {
                field.setBoolean(target, false)
                Log.d("$TAG: cleared $FIELD_EXCEPTION on ${target.javaClass.name}")
            }
        } catch (t: Throwable) {
            traceError(t)
        }
    }

    private val afterReadExternal: Hooker = { param -> clearExceptionFlag(param.thisObject) }

    private val beforeGetView: Hooker = { param -> clearExceptionFlag(param.thisObject) }

    override fun initOnce(): Boolean = throwOrTrue {
        val clazz = Initiator.loadClass(CLS_GENERAL_SHARE)

        val readExternal = findReadExternal(clazz)
            ?: throw NoSuchMethodException("readExternal(ObjectInput) not found in ${clazz.name}")
        val getView = findGetView(clazz)
            ?: throw NoSuchMethodException(
                "getView(Context,View,OnLongClickAndTouchListener,Bundle) not found in ${clazz.name}"
            )

        // 字段定位本身不抛（见 locateExceptionField），这里拿不到才终止初始化：
        // 抛出后由 BaseFunctionHook.initialize 统一 traceError 并把 initOnce 结果记为 false，
        // 不会静默成功，也不会在 getDeclaredField 处直接崩。
        val field = locateExceptionField(clazz)
        if (field == null) {
            val e = NoSuchFieldException(
                "cannot locate $FIELD_EXCEPTION or any unique boolean field in ${clazz.name}"
            )
            Log.e(e)
            throw e
        }
        exceptionField = field

        readExternal.hookAfter(afterReadExternal)
        getView.hookBefore(beforeGetView)

        Log.d("$TAG: hooked ${clazz.name}, field=${field.name}@${field.declaringClass.name}")
    }
}
