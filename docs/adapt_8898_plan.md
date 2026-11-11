# QAuxiliary 8.8.98 (versionCode 3002) hook 适配报告

索引：`analysis/methods_8.8.98.txt`（1,314,516 条）。所有新签名均已在该索引中逐条核对。
原则：类/方法名逐版本混淆，**按签名（参数类型 + 返回类型）匹配，旧名优先做 fallback**，原始类型一律用 `X.TYPE` 比较。

## 10 个目标逐个结论

| # | 目标 | 8.8.98 现状 | 新签名 | 改动 |
| --- | --- | --- | --- | --- |
| 1 | `ArkAppItemBubbleBuilder;->a(ChatMessage,ViewHolder,View,BaseChatItemLayout,OnLongClickAndTouchListener)View` | 类在，方法改名 `F` | `F(Lcom/tencent/mobileqq/data/ChatMessage;Lcom/tencent/mobileqq/activity/aio/BaseBubbleBuilder$ViewHolder;Landroid/view/View;Lcom/tencent/mobileqq/activity/aio/BaseChatItemLayout;Lcom/tencent/mobileqq/activity/aio/OnLongClickAndTouchListener;)Landroid/view/View;` | `ArkFallbackToText.kt`：`5 参 + 返回 View` 动态匹配，旧名 `a` 优先，加命中日志 |
| 2 | `FrozenNotifyDlgHelper;->a([B)Z` | 类在，**该方法已不存在**（判定内联进 NotificationActivity），类内 byte[] 入参方法只剩 `c([B)I` | 无（`([B)Z` 已删） | `QSecFrozenBypass.kt`：改为按返回类型匹配，`([B)Z` 缺失时打 warning 并降级为 `scene=-1`，不再抛 `NoSuchMethodException` |
| 3 | `FrozenNotifyDlgHelper;->b([B)I` | 类在，改名 `c` | `c([B)I` | 同上：按 `([B)` + 返回 `int` 匹配（不写死名字），命中后 `hookBefore` 返回 `-1` |
| 4 | `TroopGagMgr;->a(IJJJLjava/util/ArrayList;)V` | 类在，改名 `s` | `s(IJJJLjava/util/ArrayList;)V` | `GagInfoDisclosure.kt`：`single{}` → 多候选 `firstOrNull`，按 `(int,long,long,long,ArrayList)void` 匹配，旧名 `a` 优先，加命中日志 |
| 5 | `QQSettingMe;->a(QQSettingMeBizBean)Z` | 类在，改名 `W`；同时 `QQSettingMeBizBean.a()Ljava/lang/String;`（bizId getter）**已删**（现存无参返回 String 的只有 `e/f/g/k/l`，无法判断哪个是 bizId） | `W(Lcom/tencent/mobileqq/activity/qqsettingme/config/QQSettingMeBizBean;)Z` | `SimplifyQQSettingMe2.kt`：保留「1 参 + 参数类型 == QQSettingMeBizBean + 返回 boolean」匹配（类内 `T/U/V` 不会误命中）；隐藏判定改用已有的 `needRemove(bean)`（遍历 bean 的 String 字段比对 map key），彻底绕开被删的 getter |
| 6 | `QQValuePagView;->a(String,String,Z)V` | 类在，改名 `v` | `v(Ljava/lang/String;Ljava/lang/String;Z)V` | `HideQQValue.kt`：按签名匹配（类内唯一），旧名 `a` 优先，`Boolean::class.javaPrimitiveType` → `java.lang.Boolean.TYPE`，加命中日志 |
| 7 | 类 `TroopSecurityTipsBanner` | **整包不存在**：`^Lcom/tencent/mobileqq/troop/tipsbar/` 0 命中；`troop` 下只剩 `jointroopsecuritytips/*`（入群安全提示，语义不同）与 `TroopTipsMsgMgr` | 无等价类 | `RemoveSecurityTipsBanner.kt`：`isAvailable` 保持 `classExists(...)` 探测（原代码已是探测），该类不存在 → 功能从设置里自动消失、不初始化、不抛异常 |
| 8 | `TroopToDoController;->a(Z)V` | 类**搬包**到 `com.tencent.mobileqq.activity.aio.troop.trooptodo.TroopToDoController`，方法改名 `e` | `e(Z)V` | `HideTroopToDo.kt`：删掉 `name == "a"` 硬约束，改按 `(boolean)void` 匹配、旧名优先；`TroopToDoTipsBar` 三参构造 `single{}` → `firstOrNull{}` 并显式抛错 |
| 9 | 类 `MessageForPokeEmo` | 类在，方法集不变（`initMsg/doParse/postRead/prewrite/needVipBubble/isSupportReply/setIsNeedPlayed`） | 不变 | 无需改动 |
| 10 | `PokeEmoItemBuilder;->a(...5 参...)View` | 类在，方法改名 `F` | `F(Lcom/tencent/mobileqq/data/ChatMessage;Lcom/tencent/mobileqq/activity/aio/BaseBubbleBuilder$ViewHolder;Landroid/view/View;Lcom/tencent/mobileqq/activity/aio/BaseChatItemLayout;Lcom/tencent/mobileqq/activity/aio/OnLongClickAndTouchListener;)Landroid/view/View;` | `DisableLightInteraction.kt`：`singleMethod{}` → 多候选，`5 参 + 返回 View` 匹配，旧名 `a` 优先，找不到显式抛错 |

## 关键证据（methods_8.8.98.txt 原文行）

```
Lcom/tencent/mobileqq/activity/aio/item/ArkAppItemBubbleBuilder;->F(Lcom/tencent/mobileqq/data/ChatMessage;...OnLongClickAndTouchListener;)Landroid/view/View;
Lcom/tencent/mobileqq/app/security/FrozenNotifyDlgHelper;->c([B)I
Lcom/tencent/mobileqq/troop/utils/TroopGagMgr;->s(IJJJLjava/util/ArrayList;)V
Lcom/tencent/mobileqq/activity/QQSettingMe;->W(Lcom/tencent/mobileqq/activity/qqsettingme/config/QQSettingMeBizBean;)Z
Lcom/tencent/mobileqq/vas/qqvaluecard/view/QQValuePagView;->v(Ljava/lang/String;Ljava/lang/String;Z)V
Lcom/tencent/mobileqq/activity/aio/troop/trooptodo/TroopToDoController;->e(Z)V
Lcom/tencent/mobileqq/activity/aio/item/PokeEmoItemBuilder;->F(Lcom/tencent/mobileqq/data/ChatMessage;...OnLongClickAndTouchListener;)Landroid/view/View;
```

`FrozenNotifyDlgHelper` 在 8.8.98 的完整 byte[] 入参方法集：只有 `c([B)I`（`a()ISecControllerInterface`、`b(I)String`、`f(String,int,int)I` 等均非 byte[] 入参）。
`QQSettingMeBizBean` 在 8.8.98 的无参 String getter：`e()/f()/g()/k()/l()`，无 `a()`。
`TroopToDoController` 在 8.8.98 的全部方法：`a()V / b()View / c()V / d()Z / e(Z)V / f()V / g()V`。

## 编译验证

见文末「编译结果」一节（`pwsh -NoProfile -File tools\build.ps1 -Task ":app:compileDebugKotlin"`）。

## 限制与后续

- 目标 2/3：8.8.98 的 `([B)Z` 判定已内联进 `NotificationActivity`，`QSecFrozenBypass` LEVEL=1 的原始语义（shouldHandle=false）在该版本不可达，现降级为 `getForbidScene → -1`（scene ∉ {2,3,4} 即不进统一冻结样式）。若要恢复 LEVEL=1 的完整强度，需要 hook `NotificationActivity` 里内联后的判定分支（下一轮）。
- 目标 7：8.8.98 无 `TroopSecurityTipsBanner` 等价类，功能按设计在设置里消失；`RemoveSecurityTipsBanner_Method` 的 DexKit target 不会被执行（isAvailable=false 时不初始化）。
