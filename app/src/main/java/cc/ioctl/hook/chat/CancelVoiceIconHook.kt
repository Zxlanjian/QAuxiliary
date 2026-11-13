/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：移除聊天界面语音图标（从 TimTool 移植）
 *
 * 原实现：TimTool/app/src/main/java/top/sacz/timtool/hook/item/chat/CancelVoiceIcon.kt
 *         @HookItem("辅助功能/聊天/移除聊天界面语音图标")
 *
 * TIM 4.1.0 适配说明（classes18.dex，已用 baksmali 核对）：
 *   Lcom/tencent/tim/aio/inputbar/simpleui/TimAIOInputSimpleUIVBDelegate;
 *     .field private g:Landroid/widget/ImageButton;   // 语音/PTT 按钮
 *     .field private h:Landroid/widget/ImageButton;   // 表情按钮
 *     .field private i:Landroid/widget/ImageButton;   // 加号按钮
 *     private final W(Z)V                             // updateSimpleUI(boolean)
 *
 *   W() 的 smali 里可以看到它按 hasText 决定 pttBtn/funBtnLayout/emoBtnLayout/sendBtn
 *   的可见性（常量池里有 "pttBtn" "updateSimpleUI" "emoBtnLayout" "funBtnLayout" "sendBtn"），
 *   所以 hook W() 的 after，把字段 g 设为 GONE 就是“隐藏语音图标”且不影响发送按钮逻辑。
 *
 * 和 QA 已有的 HideFriendChatVoice 的区别：
 *   HideFriendChatVoice 隐藏的是群聊**标题栏右上角**的语音图标（Right2VB），
 *   本功能隐藏的是**输入栏左侧**的语音切换按钮，两者不重叠。
 */
package cc.ioctl.hook.chat

import android.view.View
import android.widget.ImageButton
import cc.ioctl.util.hookAfterIfEnabled
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.classExists

@FunctionHookEntry
@UiItemAgentEntry
object CancelVoiceIconHook : CommonSwitchFunctionHook("timas_cancel_voice_icon") {

    private const val CLS = "com.tencent.tim.aio.inputbar.simpleui.TimAIOInputSimpleUIVBDelegate"

    override val name: String = "移除聊天界面语音图标"

    override val description: String =
        "隐藏 TIM 聊天输入栏左侧的语音切换按钮，输入栏布局自动让位给文字输入框"

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.CHAT_DECORATION

    override val isAvailable: Boolean = classExists(CLS)

    override fun initOnce(): Boolean {
        return try {
            val clz = Initiator.loadClass(CLS)

            // private final W(Z)V —— updateSimpleUI(boolean)
            val target = clz.declaredMethods.firstOrNull { m ->
                m.name == "W" &&
                        m.returnType == Void.TYPE &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == Boolean::class.javaPrimitiveType
            } ?: clz.declaredMethods.firstOrNull { m ->
                // 兜底：任意 (Z)V 且名字为单字母
                m.returnType == Void.TYPE &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                        m.name.length == 1
            } ?: return false

            // 语音按钮字段 g；找不到就退化到第一个 ImageButton 字段
            val field = clz.declaredFields.firstOrNull {
                it.type == ImageButton::class.java && it.name == "g"
            } ?: clz.declaredFields.firstOrNull {
                it.type == ImageButton::class.java
            } ?: return false
            field.isAccessible = true

            hookAfterIfEnabled(target) { param ->
                try {
                    val btn = field.get(param.thisObject) as? ImageButton
                        ?: return@hookAfterIfEnabled
                    if (btn.visibility != View.GONE) {
                        btn.visibility = View.GONE
                    }
                } catch (_: Throwable) {
                    // 单条消息 UI 更新失败不能影响宿主
                }
            }
            true
        } catch (t: Throwable) {
            traceError(t)
            false
        }
    }
}
