/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：TIM 小程序 / Ark 卡片修复
 *
 * 来自用户原有的 8KB 模块（包名 io.live.timas，入口 io.live.timas.MHook）：
 *   XposedHelpers.findAndHookMethod(
 *       "com.tencent.mobileqq.aio.msglist.holder.component.ark.d",
 *       cl, "a", String.class, String.class,
 *       new XC_MethodHook() {
 *           afterHookedMethod(param) { param.setResult(Boolean.TRUE); }
 *       });
 *
 * 目标方法（TIM 4.1.0 classes9.dex，QQ 侧同名同签名）：
 *   Lcom/tencent/mobileqq/aio/msglist/holder/component/ark/d;
 *       public final a(Ljava/lang/String;Ljava/lang/String;)Z
 */
package cc.ioctl.hook.chat;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import cc.ioctl.util.HookUtils;
import io.github.qauxv.util.xpcompat.XC_MethodHook;
import io.github.qauxv.base.annotation.FunctionHookEntry;
import io.github.qauxv.base.annotation.UiItemAgentEntry;
import io.github.qauxv.dsl.FunctionEntryRouter;
import io.github.qauxv.hook.CommonSwitchFunctionHook;
import io.github.qauxv.util.Initiator;

@FunctionHookEntry
@UiItemAgentEntry
public class FixTimMiniApp extends CommonSwitchFunctionHook {

    public static final FixTimMiniApp INSTANCE = new FixTimMiniApp();

    private static final String CLS_ARK = "com.tencent.mobileqq.aio.msglist.holder.component.ark.d";

    private FixTimMiniApp() {
        super("timas_fix_miniapp");
    }

    @NonNull
    @Override
    public String getName() {
        return "修复小程序卡片";
    }

    @NonNull
    @Override
    public String getDescription() {
        return "让 Ark 卡片（小程序/公众号卡片）可以正常点开";
    }

    @NonNull
    @Override
    public String[] getUiItemLocation() {
        return FunctionEntryRouter.Locations.Simplify.CHAT_OTHER;
    }

    @Override
    protected boolean initOnce() {
        try {
            Class<?> kArk = Initiator.loadClass(CLS_ARK);
            Method target = null;
            for (Method m : kArk.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 2 && ps[0] == String.class && ps[1] == String.class
                        && m.getReturnType() == boolean.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                traceError(new IllegalStateException("未找到 ark.d.a(String,String)"));
                return false;
            }
            HookUtils.hookAfterIfEnabled(this, target, new HookUtils.AfterHookedMethod() {
                @Override
                public void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
            return true;
        } catch (Throwable t) {
            traceError(t);
            return false;
        }
    }
}
