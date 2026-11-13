/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 新增功能：让 TIM 显示戳一戳
 *
 * 背景（TIM 4.1.0 反编译结论）：
 *   TIM 的服务端把戳一戳降级成一条普通文本消息，内容固定为：
 *       [戳一戳]请使用最新版本手机QQ查看。
 *       [戳一戳]请使用最新版手机QQ体验新功能。
 *       [比心]/[点赞]/[心碎]/[放大招]/[666] 请使用最新版本手机QQ查看。
 *   客户端 AIOMsgPokeProcessor.i() 本来是负责把这句文本还原成戳一戳元素的，
 *   但它只在 AIOUtil.r()（= isNTRuntime）成立时才跑，而且要求文本完全相等。
 *   结果就是 TIM 聊天记录里看不到戳一戳。
 *
 * 本功能在 AIOMsgPokeProcessor.a(List) 出口兜底：
 *   遍历结果列表 → 文本命中占位文案的项 → 调它自己的私有方法
 *       l(MsgRecord, pokeType, vaspokeId, pokeStrength) -> PokeMsgItem
 *   换成真正的戳一戳元素。
 *
 * pokeType 映射取自 i() 各分支常量：
 *   0=普通戳  1=戳一戳(体验新功能)  2=比心  3=点赞  4=心碎  5=666  6=放大招
 */
package cc.ioctl.hook.chat;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import cc.ioctl.util.HookUtils;
import io.github.qauxv.util.xpcompat.XC_MethodHook;
import io.github.qauxv.util.xpcompat.XposedBridge;
import io.github.qauxv.util.xpcompat.XposedHelpers;
import io.github.qauxv.base.annotation.FunctionHookEntry;
import io.github.qauxv.base.annotation.UiItemAgentEntry;
import io.github.qauxv.dsl.FunctionEntryRouter;
import io.github.qauxv.hook.CommonSwitchFunctionHook;
import io.github.qauxv.util.Initiator;

@FunctionHookEntry
@UiItemAgentEntry
public class ShowPokeMessage extends CommonSwitchFunctionHook {

    public static final ShowPokeMessage INSTANCE = new ShowPokeMessage();

    private static final String CLS_PROC =
            "com.tencent.mobileqq.aio.msglist.holder.component.poke.AIOMsgPokeProcessor";

    private ShowPokeMessage() {
        super("timas_show_poke");
    }

    @NonNull
    @Override
    public String getName() {
        return "显示戳一戳（TIM）";
    }

    @NonNull
    @Override
    public String getDescription() {
        return "把 TIM 里「[戳一戳]请使用最新版本手机QQ查看。」这类占位文本还原成真正的戳一戳";
    }

    @NonNull
    @Override
    public String[] getUiItemLocation() {
        return FunctionEntryRouter.Locations.Simplify.UI_CHAT_MSG;
    }

    @Override
    protected boolean initOnce() {
        try {
            Class<?> kProc = Initiator.loadClass(CLS_PROC);
            Method target = null;
            for (Method m : kProc.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && List.class.isAssignableFrom(ps[0])
                        && List.class.isAssignableFrom(m.getReturnType())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                traceError(new IllegalStateException("未找到 AIOMsgPokeProcessor 的 List->List 方法"));
                return false;
            }
            XposedBridge.log("QA-TimAs: ShowPokeMessage hooked on "
                    + target.getDeclaringClass().getName() + "#" + target.getName());
            HookUtils.hookAfterIfEnabled(this, target, new HookUtils.AfterHookedMethod() {
                @Override
                public void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    try {
                        Object ret = param.getResult();
                        if (!(ret instanceof List)) {
                            return;
                        }
                        List<?> src = (List<?>) ret;
                        // 诊断：把列表里所有消息的 msgType 打出来，便于判断 kernel 有没有下发戳一戳
                        StringBuilder sb = new StringBuilder("QA-TimAs poke: list=" + src.size() + " types=[");
                        for (int k = 0; k < src.size(); k++) {
                            Object r0 = getRecord(src.get(k));
                            sb.append(r0 == null ? "?" : String.valueOf(XposedHelpers.getIntField(r0, "msgType")));
                            if (k != src.size() - 1) sb.append(",");
                        }
                        sb.append("]");
                        XposedBridge.log(sb.toString());
                        ArrayList<Object> out = new ArrayList<>(src.size());
                        boolean changed = false;
                        for (int i = 0; i < src.size(); i++) {
                            Object item = src.get(i);
                            Object rec = getRecord(item);
                            int pokeType = pokeTypeOf(textOf(rec));
                            if (pokeType < 0) {
                                out.add(item);
                                continue;
                            }
                            Object poke = null;
                            try {
                                poke = XposedHelpers.callMethod(param.thisObject, "l",
                                        rec, pokeType, 0, 0);
                            } catch (Throwable t) {
                                XposedBridge.log("QA-TimAs poke: 转换失败 " + t);
                            }
                            if (poke != null) {
                                out.add(poke);
                                changed = true;
                            } else {
                                out.add(item);
                            }
                        }
                        if (changed) {
                            param.setResult(out);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("QA-TimAs poke: " + t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            traceError(t);
            return false;
        }
    }

    private static Object getRecord(Object item) {
        if (item == null) {
            return null;
        }
        try {
            return XposedHelpers.callMethod(item, "getMsgRecord");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 取 MsgRecord.elements[0].textElement.content，拿不到返回 null。 */
    private static String textOf(Object rec) {
        if (rec == null) {
            return null;
        }
        try {
            Object elements = XposedHelpers.getObjectField(rec, "elements");
            if (!(elements instanceof List) || ((List<?>) elements).isEmpty()) {
                return null;
            }
            Object el = ((List<?>) elements).get(0);
            if (el == null) {
                return null;
            }
            Object textEl = XposedHelpers.getObjectField(el, "textElement");
            if (textEl == null) {
                return null;
            }
            Object content = XposedHelpers.getObjectField(textEl, "content");
            return content instanceof String ? (String) content : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 命中占位文案就返回对应的 pokeType，否则 -1。 */
    private static int pokeTypeOf(String text) {
        if (text == null) {
            return -1;
        }
        if (text.contains("[戳一戳]")) {
            return text.contains("体验新功能") ? 1 : 0;
        }
        if (text.contains("[比心]")) {
            return 2;
        }
        if (text.contains("[点赞]")) {
            return 3;
        }
        if (text.contains("[心碎]")) {
            return 4;
        }
        if (text.contains("[666]")) {
            return 5;
        }
        if (text.contains("[放大招]")) {
            return 6;
        }
        return -1;
    }
}
