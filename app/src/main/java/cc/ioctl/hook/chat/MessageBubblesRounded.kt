/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：消息气泡圆角（从 TimTool 移植，带自定义圆角设置）
 *
 * 目标类（TIM 4.1.0 classes10.dex / QQ 9.x 同构）：
 *   com.tencent.mobileqq.aio.utils.ai
 *       private final e(Context, TimBubbleStyle, float) -> Drawable   // 对方气泡
 *       private final g(Context, TimBubbleStyle, float) -> Drawable   // 自己气泡
 *       private final c(TimBubbleStyle) -> int                        // 上内边距
 *       private final a(TimBubbleStyle) -> int                        // 下内边距
 *
 * 点击功能项可以选圆角大小（0-32dp），改完立即生效，不用重启。
 */
package cc.ioctl.hook.chat

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import cc.ioctl.util.HookUtils
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItemsSingleChoice
import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.dsl.FunctionEntryRouter
import io.github.qauxv.dsl.uiClickableItem
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedHelpers
import me.ketal.data.ConfigData
import me.ketal.util.ignoreResult
import java.lang.reflect.Method

@FunctionHookEntry
@UiItemAgentEntry
object MessageBubblesRounded : CommonSwitchFunctionHook("timas_bubble_rounded") {

    private val radiiKey = ConfigData<Int>("timas_bubble_radii_dp")

    /** 圆角半径（dp），每次渲染都重新读，改完立即生效 */
    private var radii: Int
        get() = radiiKey.getOrDefault(12)
        set(value) {
            radiiKey.value = value
        }

    private val presets = listOf(0, 2, 4, 6, 8, 10, 12, 16, 20, 24, 32)
    private val presetNames = presets.map { if (it == 0) "0 dp（直角）" else "$it dp" }

    override val name: String = "消息气泡圆角"
    override val description: String
        get() = "统一消息气泡圆角，当前 ${radii}dp（点我改）"

    override val uiItemAgent by lazy {
        uiClickableItem {
            title = "消息气泡圆角"
            summary = "当前 ${radii}dp，点我调整（0-32dp）"
            onClickListener = { _, activity, _ ->
                showDialog(activity)
            }
        }
    }

    override val uiItemLocation: Array<String> = FunctionEntryRouter.Locations.Simplify.CHAT_DECORATION

    private fun showDialog(context: Context) {
        val initial = presets.indexOf(radii).let { if (it < 0) presets.indexOf(12) else it }
        MaterialDialog(context).show {
            title(text = "气泡圆角大小")
            listItemsSingleChoice(
                items = presetNames,
                initialSelection = initial,
                waitForPositiveButton = true,
                selection = { _, index, _ ->
                    radii = presets[index]
                    if (!isEnabled) {
                        isEnabled = true
                    }
                }
            ).ignoreResult()
            positiveButton(text = "保存")
            negativeButton(text = "取消")
        }
    }

    override fun initOnce(): Boolean {
        val kAI = Initiator.loadClass(CLS_AI)
        val kStyle = Initiator.loadClass(CLS_STYLE)

        var guest: Method? = null
        var self: Method? = null
        for (m in kAI.declaredMethods) {
            val ps = m.parameterTypes
            if (m.returnType == Drawable::class.java && ps.size == 3
                && ps[0] == Context::class.java && ps[1] == kStyle && ps[2] == Float::class.javaPrimitiveType
            ) {
                if (guest == null) {
                    guest = m
                } else if (self == null) {
                    self = m
                }
            }
        }
        if (guest == null || self == null) {
            traceError(IllegalStateException("未找到 ai 的气泡生成方法"))
            return false
        }

        val g = guest
        val s = self
        HookUtils.hookBeforeIfEnabled(this, g!!, object : HookUtils.BeforeHookedMethod {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                buildBubble(param, false)
            }
        })
        HookUtils.hookBeforeIfEnabled(this, s!!, object : HookUtils.BeforeHookedMethod {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                buildBubble(param, true)
            }
        })
        return true
    }

    private fun buildBubble(param: XC_MethodHook.MethodHookParam, isSelf: Boolean) {
        try {
            val ctx = param.args[0] as Context
            val style = param.args[1]
            val thisObj = param.thisObject
            var top = 0
            var bottom = 0
            try {
                top = XposedHelpers.callMethod(thisObj, "c", style) as Int
            } catch (_: Throwable) {
            }
            try {
                bottom = XposedHelpers.callMethod(thisObj, "a", style) as Int
            } catch (_: Throwable) {
            }
            param.result = makeBubble(ctx, isSelf, dp(ctx, radii).toFloat(), top, bottom)
        } catch (t: Throwable) {
            // 出错就不改，走原生气泡
            traceError(t)
        }
    }

    private fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private fun isNight(ctx: Context): Boolean {
        val mode = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun makeBubble(ctx: Context, isSelf: Boolean, radii: Float, top: Int, bottom: Int): Drawable {
        val color = if (isSelf) {
            Color.parseColor(if (isNight(ctx)) "#314A77" else "#508FFF")
        } else {
            Color.parseColor(if (isNight(ctx)) "#333333" else "#FFFFFF")
        }
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.RECTANGLE
        gd.setColor(color)
        gd.cornerRadii = floatArrayOf(radii, radii, radii, radii, radii, radii, radii, radii)
        val ld = LayerDrawable(arrayOf<Drawable>(gd))
        val lr = dp(ctx, 10)
        ld.setLayerInset(0, lr, top, lr, bottom)
        return ld
    }

    private const val CLS_AI = "com.tencent.mobileqq.aio.utils.ai"
    private const val CLS_STYLE = "com.tencent.mobileqq.aio.msglist.holder.skin.TimBubbleStyle"
}
