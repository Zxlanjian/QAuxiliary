/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：屏蔽临时会话弹窗（从 TimTool 移植）
 *
 * 原实现：TimTool/app/src/main/java/top/sacz/timtool/hook/item/chat/BlockTempMsgDialog.kt
 *         @HookItem("辅助功能/聊天/屏蔽临时会话弹窗")
 *
 * TIM 4.1.0 适配说明（classes3.dex，已用 baksmali 核对）：
 *   Lcom/tencent/mobileqq/managers/TempMsgManager;
 *     public B(Ljava/lang/String;)Z      // 语义：isTempMsgAllowed(uid)
 *     private v(S)S                      // 注意：4.1.0 的 v 是 (short)short，
 *                                        // 不再是 TimTool 时代的 v(String)Z
 *
 *   B() 的实现（smali 片段）：
 *     iget-object v0, p0, ...->h:Ljava/util/Map;      // 缓存
 *     invoke-interface {v0, p1}, Ljava/util/Map;->containsKey(Ljava/lang/Object;)Z
 *     ...
 *     invoke-direct {p0, p1, v0}, ...->A(Ljava/lang/String;I)Z   // 未命中缓存时读 SP
 *
 *   所以 hook B() 返回 true，等价于“这个 uid 的临时会话是开启的”，
 *   宿主就不会再弹“开启临时会话”对话框。
 *
 * 兜底：如果 B 找不到（未来版本改名），退化为匹配任意 (String)Z 且名字最短的 public 方法。
 */
package cc.ioctl.hook.chat

import cc.ioctl.util.hookBeforeIfEnabled
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.classExists

@FunctionHookEntry
@UiItemAgentEntry
object BlockTempMsgDialogHook : CommonSwitchFunctionHook("timas_block_temp_msg_dialog") {

    private const val CLS = "com.tencent.mobileqq.managers.TempMsgManager"

    override val name: String = "屏蔽临时会话弹窗"

    override val description: String =
        "在没有开启临时会话的情况下查看已删除好友的聊天记录时，不再弹出“开启临时会话”对话框"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.CHAT_OTHER

    override val isAvailable: Boolean = classExists(CLS)

    override fun initOnce(): Boolean {
        return try {
            val clz = Initiator.loadClass(CLS)

            // 首选 TIM 4.1.0 的 B(Ljava/lang/String;)Z
            val target = clz.declaredMethods.firstOrNull { m ->
                m.name == "B" &&
                        m.returnType == Boolean::class.javaPrimitiveType &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == String::class.java
            } ?: clz.declaredMethods
                .filter { m ->
                    m.returnType == Boolean::class.javaPrimitiveType &&
                            m.parameterTypes.size == 1 &&
                            m.parameterTypes[0] == String::class.java
                }
                // 兜底时优先 public，其次名字最短的（混淆后单字母方法最可能是入口）
                .minByOrNull { if (java.lang.reflect.Modifier.isPublic(it.modifiers)) 0 else 1 }

            if (target == null) {
                return false
            }

            hookBeforeIfEnabled(target) { param ->
                // 让宿主认为临时会话已开启 -> 不弹窗
                param.result = true
            }
            true
        } catch (t: Throwable) {
            traceError(t)
            false
        }
    }
}
