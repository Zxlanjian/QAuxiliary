/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：消息气泡圆角（从 TimTool 移植）
 *
 * 目标类（TIM 4.1.0 classes10.dex / QQ 9.x 同构）：
 *   com.tencent.mobileqq.aio.utils.ai
 *       private final e(Context, TimBubbleStyle, float) -> Drawable   // 对方气泡
 *       private final g(Context, TimBubbleStyle, float) -> Drawable   // 自己气泡
 *       private final c(TimBubbleStyle) -> int                        // 上内边距
 *       private final a(TimBubbleStyle) -> int                        // 下内边距
 *
 * 做法：before 阶段直接 setResult 成自己造的 GradientDrawable + LayerDrawable，
 * 圆角统一；内边距沿用宿主自己的 c()/a()，所以不会错位。
 */
package cc.ioctl.hook.chat;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import cc.ioctl.util.HookUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.qauxv.base.annotation.FunctionHookEntry;
import io.github.qauxv.base.annotation.UiItemAgentEntry;
import io.github.qauxv.dsl.FunctionEntryRouter;
import io.github.qauxv.hook.CommonSwitchFunctionHook;
import io.github.qauxv.util.Initiator;

@FunctionHookEntry
@UiItemAgentEntry
public class MessageBubblesRounded extends CommonSwitchFunctionHook {

    public static final MessageBubblesRounded INSTANCE = new MessageBubblesRounded();

    /** 圆角半径（dp），想改大小直接改这里。 */
    private static final int RADII_DP = 12;

    private static final String CLS_AI = "com.tencent.mobileqq.aio.utils.ai";
    private static final String CLS_STYLE = "com.tencent.mobileqq.aio.msglist.holder.skin.TimBubbleStyle";

    private MessageBubblesRounded() {
        super("timas_bubble_rounded");
    }

    @NonNull
    @Override
    public String getName() {
        return "消息气泡圆角";
    }

    @NonNull
    @Override
    public String getDescription() {
        return "统一消息气泡圆角为 " + RADII_DP + "dp（改源码里的 RADII_DP 可调）";
    }

    @NonNull
    @Override
    public String[] getUiItemLocation() {
        return FunctionEntryRouter.Locations.Simplify.CHAT_DECORATION;
    }

    @Override
    protected boolean initOnce() {
        try {
            Class<?> kAI = Initiator.loadClass(CLS_AI);
            Class<?> kStyle = Initiator.loadClass(CLS_STYLE);

            Method guest = null;
            Method self = null;
            for (Method m : kAI.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (m.getReturnType() == Drawable.class && ps.length == 3
                        && ps[0] == Context.class && ps[1] == kStyle && ps[2] == float.class) {
                    if (guest == null) {
                        guest = m;
                    } else if (self == null) {
                        self = m;
                    }
                }
            }
            if (guest == null || self == null) {
                traceError(new IllegalStateException("未找到 ai 的气泡生成方法"));
                return false;
            }

            HookUtils.hookBeforeIfEnabled(this, guest, new HookUtils.BeforeHookedMethod() {
                @Override
                public void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    buildBubble(param, false);
                }
            });
            HookUtils.hookBeforeIfEnabled(this, self, new HookUtils.BeforeHookedMethod() {
                @Override
                public void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    buildBubble(param, true);
                }
            });
            return true;
        } catch (Throwable t) {
            traceError(t);
            return false;
        }
    }

    private void buildBubble(XC_MethodHook.MethodHookParam param, boolean isSelf) {
        try {
            Context ctx = (Context) param.args[0];
            Object style = param.args[1];
            Object thisObj = param.thisObject;
            int top = 0;
            int bottom = 0;
            try {
                top = (Integer) XposedHelpers.callMethod(thisObj, "c", style);
            } catch (Throwable ignored) {
            }
            try {
                bottom = (Integer) XposedHelpers.callMethod(thisObj, "a", style);
            } catch (Throwable ignored) {
            }
            param.setResult(makeBubble(ctx, isSelf, dp(ctx, RADII_DP), top, bottom));
        } catch (Throwable t) {
            // 出错就不改，走原生气泡
            XposedBridge.log("QA-TimAs bubble: " + t);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private static boolean isNight(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static Drawable makeBubble(Context ctx, boolean isSelf, float radii, int top, int bottom) {
        int color;
        if (isSelf) {
            color = Color.parseColor(isNight(ctx) ? "#314A77" : "#508FFF");
        } else {
            color = Color.parseColor(isNight(ctx) ? "#333333" : "#FFFFFF");
        }
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadii(new float[]{
                radii, radii,
                radii, radii,
                radii, radii,
                radii, radii
        });
        LayerDrawable ld = new LayerDrawable(new Drawable[]{gd});
        int lr = dp(ctx, 10);
        ld.setLayerInset(0, lr, top, lr, bottom);
        return ld;
    }
}
