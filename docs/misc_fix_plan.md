# QQ 8.8.50 (2324) 运行时清单 4 个报错项的判定与修复

- 目标客户端：QQ 8.8.50 / versionCode 2324
- 模块版本：1.6.1.r3054.4791fc9
- 实机清单目录：`D:\DSH-Builds\QAuxiliaryFix\references\hooks\`
- 方法签名索引：`D:\DSH-Builds\QAuxiliaryFix\analysis\methods_8.8.50.txt`（1,122,776 条）
- 反编译：jadx 1.5.1，输出 `analysis\jd2\`

结论速览：

| 目标 | 能否修 | 处理方式 |
| --- | --- | --- |
| 频道防撤回消息 `RevokeGuildMsg` | **可修（已修）** | 去掉对不存在的 `processUpdateEvent` 的硬 `findMethod`，改为按名字安全探测 |
| 聊天照片分享增强 `PhotoShareHook` | 不可修（8.8.50 无对应实现） | 加 `isAvailable` 类存在性门控 |
| 隐藏群聊风险提醒 `RemoveSecurityTipsBanner` | 不可修（目标类整包不存在） | 加 `isAvailable` 类存在性门控 |
| 关闭好友互动标识 `TurnOffFriendInteractionLogoView` | 不可修（目标类与目标方法都不存在） | 加 `isAvailable` 类存在性门控 |

---

## 1. 频道防撤回消息 — `cc/ioctl/hook/guild/RevokeGuildMsg.kt`

### 根因

清单堆栈指向 `RevokeGuildMsg.initOnce$lambda$0` → `ezxhelper MethodUtilsKt.findMethod`：

```
java.lang.NoSuchMethodException
	at com.github.kyuubiran.ezxhelper.utils.MethodUtilsKt.findMethod(MethodUtils.kt:81)
	at cc.ioctl.hook.guild.RevokeGuildMsg.initOnce$lambda$0(RevokeGuildMsg.kt:134)
```

清单里报的行号 134 对应设备上那份构建（含前几轮修复插入的注释）；当前 HEAD 上同一段代码在 **`RevokeGuildMsg.kt:110-112`**：

```kotlin
"com/tencent/mobileqq/guild/message/eventflow/api/impl/GuildEventFlowServiceImpl".clazz!!.findMethod {
    name == "processUpdateEvent"
}.hookBefore(this) { ... }
```

`initOnce` 里三处目标：

| 位置 | 目标 | 8.8.50 是否存在 |
| --- | --- | --- |
| 第 55-56 行 | `GuildEventFlowServiceImpl;->handleDeleteEvent` | ✅ 存在 |
| 第 99 行 | `MessageForGuildRevokeGrayTip;->init(MessageRecord,String,Z,String,String,String,Z)V` | ✅ 存在 |
| 第 110-111 行 | `GuildEventFlowServiceImpl;->processUpdateEvent` | ❌ **不存在** |

### 证据

方法索引里 `GuildEventFlowServiceImpl` 的全部方法（`rg 'GuildEventFlowServiceImpl;->' methods_8.8.50.txt`）：

```
;-><init>()V
;->access$000(...)Lmqq/app/AppRuntime;
;->addGreyTipsForDeletedMsg(Lcom/tencent/mobileqq/data/MessageRecord;Ltencent/im/group_pro_proto/common/common$Event;)V
;->handleDeleteEvent(Lcom/tencent/mobileqq/data/MessageRecord;Ltencent/im/group_pro_proto/common/common$Event;Ljava/lang/String;Ljava/lang/String;J)V
;->isGuildRoamMessageEventFlowProcessorNull()Z
;->needToAddRevokeGrayTipToAio(...)Z
;->onChatMessageExposure(...)V
;->onCreate(Lmqq/app/AppRuntime;)V
;->onDestroy()V
;->onPullRoamMsgEventFlowRsp(...)V
;->processEvent(Lcom/tencent/mobileqq/data/MessageRecord;Ltencent/im/group_pro_proto/common/common$Event;Ljava/lang/String;Ljava/lang/String;J)Z
;->processEvents(Lcom/tencent/mobileqq/data/MessageRecord;Ljava/util/List;Ljava/lang/String;Ljava/lang/String;J)V
;->shouldAddRevokeGrayTip(...)Z
;->updateEventFlowContent(...)V
;->updateReplyMsgCommentForSourceMsg(...)Z
```

**没有 `processUpdateEvent`**。8.8.50 的 eventflow 接口已经从「单事件更新」改成 `processEvent` / `processEvents`。

`MessageForGuildRevokeGrayTip` 目标方法存在：

```
Lcom/tencent/mobileqq/guild/message/msgtype/MessageForGuildRevokeGrayTip;->init(Lcom/tencent/mobileqq/data/MessageRecord;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V
```

jadx 反编译 `analysis\jd2\gef\sources\com\tencent\mobileqq\guild\message\eventflow\api\impl\GuildEventFlowServiceImpl.java:88-106`：

```java
public boolean processEvent(MessageRecord messageRecord, common.Event event, String str, String str2, long j) {
    ...
    if (event.type.get() != 1 && event.type.get() != 2) {
        return false;
    }
    handleDeleteEvent(messageRecord, event, str, str2, j);
    return true;
}
```

即：8.8.50 的 `processEvent` 自身就对 `event.type`（1=撤回、2=删除）做过滤并对撤回事件 `return true` 接管，旧版 hook 补的「事件类型变化时接管」语义已由客户端实现，这一段在 8.8.50 上是多余的；它唯一的作用是抛出 `NoSuchMethodException` 把整个 `initOnce` 打断——虽然前两处 hook 在抛异常前已经装上，但 `isInitializationSuccessful` 变成 false、错误面板常驻一条异常。

### 结论

**可修**。不是类/方法缺失导致的「功能没了」，而是第三段兼容代码在 8.8.50 上找错方法名。防撤回主体（`handleDeleteEvent` 的 `hookBefore` + `param.result = null`）与灰条文案改写（`MessageForGuildRevokeGrayTip.init` 的 `hookBefore` 替换 `args[1]` 为 `wording`）在 8.8.50 上目标都存在，功能可用。

### 改动

`source\app\src\main\java\cc\ioctl\hook\guild\RevokeGuildMsg.kt`

- 把硬 `findMethod { name == "processUpdateEvent" }`（找不到即抛）换成按名字安全探测：

```kotlin
"com/tencent/mobileqq/guild/message/eventflow/api/impl/GuildEventFlowServiceImpl"
    .clazz!!.declaredMethods
    .firstOrNull { it.name == "processUpdateEvent" }
    ?.hookBefore(this) {
        if (it.args[1].get("msgtype") != it.args[2].get("msgtype")) {
            it.result = true
        }
    }
```

命中就挂（旧客户端保留原行为），未命中静默跳过，`initOnce` 继续 `return true`，前两处 hook 不受影响。
- 移除随之失效的 `import com.github.kyuubiran.ezxhelper.utils.findMethod`。

---

## 2. 聊天照片分享增强 — `io/github/duzhaokun123/hook/PhotoShareHook.kt`

（路径经 rg 确认：清单里的类 `io.github.duzhaokun123.hook.PhotoShareHook` 对应
`source\app\src\main\java\io\github\duzhaokun123\hook\PhotoShareHook.kt`）

### 根因

```
java.lang.ClassNotFoundException: DexTarget: SharePanelSceneData
	at io.github.qauxv.util.dexkit.DexKit.requireClassFromCache(DexKit.kt:141)
	at io.github.duzhaokun123.hook.PhotoShareHook.initOnce(PhotoShareHook.kt:63)
```

`initOnce` 第一步就是 `DexKit.requireClassFromCache(SharePanelSceneData)`。DexKit target 定义
（`DexKitTarget.kt:1148-1153`）：

```kotlin
data object SharePanelSceneData : DexKitTarget.UsingStr() {
    override val findMethod = false
    override val traitString = arrayOf("SharePanelSceneData(sceneId=")
    override val declaringClass = "com.tencent.mobileqq.sharepanel.config.SharePanelSceneData"
    override val filter = DexKitFilter.strInClsName("com/tencent/mobileqq/sharepanel/config/")
}
```

另外两个 target `SharePanel_Handler_OtherApp_openImageByOtherApp` / `openVideoByOtherApp`
声明的类都是 `com.tencent.mobileqq.sharepanel.share.handler.SharePanel_Handler_OtherApp`。

### 证据

```
rg -Nc 'sharepanel' methods_8.8.50.txt            → 0
rg -Nc 'sharepanel' analysis/res_dump.txt         → 0
rg -N 'openImageByOtherApp|openVideoByOtherApp' methods_8.8.50.txt → 0
```

索引里所有含 `SharePanel` 的 57 条全部属于 `com.tencent.av.share.ShareChat$SharePanel`（音视频房间内的分享面板），与 `com.tencent.mobileqq.sharepanel` 无关：

```
Lcom/tencent/av/share/ShareChat$SharePanel$1;->run()V
Lcom/tencent/av/share/ShareChat$SharePanel$IHandle;->a()I
...
```

8.8.50 里「外部分享」相关的真实实现是系统 Intent + `ShareActionSheet`：

```
Lcom/tencent/mobileqq/activity/shortvideo/ShortVideoPlayActivity;->openWithOtherApp()V
Lcom/tencent/mobileqq/richmediabrowser/api/IFileDepend;->openWithOtherApp(Landroid/app/Activity;Ljava/lang/String;)V
Lcom/tencent/mobileqq/richmediabrowser/api/impl/FileDependImpl;->openWithOtherApp(...)V
Lcom/tencent/mobileqq/widget/share/ShareActionSheet
```

这些都不是 SharePanel 场景数据结构：`SharePanelSceneData(sceneId=...)` 是 9.x NT 化后的分享面板模型，8.8.50 不存在同类语义的类（该版本没有「场景化分享面板」这一层，图片分享直接进 `ForwardBaseOption` 体系）。

### 结论

**不可修**（在本版本上）。整包 `com.tencent.mobileqq.sharepanel` 不存在，没有可替代的等价类；强行改写要重做一套基于 `ForwardBaseOption` / `ShareActionSheet` 的实现，属于新功能而非 hook 修复，超出本次范围。

### 改动

`PhotoShareHook.kt` 加门控，让它在 8.8.50 上不展示、不初始化：

```kotlin
override val isAvailable: Boolean =
    classExists("com.tencent.mobileqq.sharepanel.config.SharePanelSceneData")
```

（`import io.github.qauxv.util.classExists`）

---

## 3. 隐藏群聊风险提醒 — `io/github/horange321/RemoveSecurityTipsBanner.kt`

（路径经 rg 确认：清单里的类 `io.github.horange321.RemoveSecurityTipsBanner` 对应
`source\app\src\main\java\io\github\horange321\RemoveSecurityTipsBanner.kt`，不在 `com/xiaoniu/hook` 下）

### 根因

```
java.lang.NoSuchMethodException: DexTarget: RemoveSecurityTipsBanner_Method
	at io.github.qauxv.util.dexkit.DexKit.requireMethodFromCache(DexKit.kt:136)
	at io.github.horange321.RemoveSecurityTipsBanner.initOnce(RemoveSecurityTipsBanner.kt:44)
```

target 定义（`DexKitTarget.kt:1123-1128`）：

```kotlin
data object RemoveSecurityTipsBanner_Method : DexKitTarget.UsingStringVector() {
    override val findMethod = true
    override val declaringClass = "com/tencent/mobileqq/troop/tipsbar/TroopSecurityTipsBanner"
    override val traitStringVectors = arrayOf(arrayOf("[doOnCreate]", "TroopSecurityTipsBar"))
    override val filter = DexKitFilter.allowAll
}
```

### 证据

```
rg -No '^L[^;]*/tipsbar/[A-Za-z0-9_$]*;' methods_8.8.50.txt   → 0 条（包不存在）
rg -N 'TroopSecurityTipsBanner' methods_8.8.50.txt            → 0 条
```

8.8.50 的 troop 包下所有 Tips 相关类（全量）：

```
Lcom/tencent/mobileqq/troop/data/TroopAioBlueTipsView;
Lcom/tencent/mobileqq/troop/data/TroopAioTips;
Lcom/tencent/mobileqq/troop/data/TroopGrayTipsMsgExpose;
Lcom/tencent/mobileqq/troop/data/TroopTipsEntity;
Lcom/tencent/mobileqq/troop/jointroopsecuritytips/JoinTroopSecurityTipsConfig;
Lcom/tencent/mobileqq/troop/jointroopsecuritytips/JoinTroopSecurityTipsHelper;
Lcom/tencent/mobileqq/troop/jointroopsecuritytips/JoinTroopSecurityTipsItem;
Lcom/tencent/mobileqq/troop/navigatebar/AioTips;
Lcom/tencent/mobileqq/troop/temporaryban/TemporarilyBannedTroopTipsBtn;
Lcom/tencent/mobileqq/troop/temporaryban/TemporarilyBannedTroopTipsItem;
Lcom/tencent/mobileqq/troop/utils/TroopTipsMsgMgr;
```

没有 `TroopSecurityTipsBanner`，也没有 `[doOnCreate]` + `TroopSecurityTipsBar` 这组字符串特征（`qq_strings.txt` 里搜「风险提醒 / 风险提示」只有 `String #34847 风险提示:`、`String #44388 风险提示` 两条通用文案，没有对应的 banner 类）。

`joinTroopSecurityTips` 是「加群安全提示」，语义不同（加群前弹窗），不是群聊顶部风险提醒条。

### 结论

**不可修**。目标 banner 类在 8.8.50 上整包不存在，`requireMethodFromCache` 必然抛 `NoSuchMethodException`。该功能属于后续版本（群聊顶部风险提示条）的 UI，8.8.50 没有对应承载类。

### 改动

`RemoveSecurityTipsBanner.kt` 加门控：

```kotlin
override val isAvailable: Boolean =
    classExists("com.tencent.mobileqq.troop.tipsbar.TroopSecurityTipsBanner")
```

（`import io.github.qauxv.util.classExists`）

---

## 4. 关闭好友互动标识 — `top/linl/hook/TurnOffFriendInteractionLogoView.java`

（路径经 rg 确认：`source\app\src\main\java\top\linl\hook\TurnOffFriendInteractionLogoView.java`，
Java 源文件而非 Kotlin，包名 `top.linl.hook` 而非 `io.github.qauxv.hook`）

### 根因

清单里只写了 `errors: 1`，实际异常在堆栈里：

```
top.linl.util.reflect.ReflectException: 没有找到类: com.tencent.mobileqq.profilecard.component.ProfileIntimateComponent
	at top.linl.util.reflect.ClassUtils$XClassLoader.loadClass(ClassUtils.java:159)
	at top.linl.util.reflect.ClassUtils.getClass(ClassUtils.java:86)
	at top.linl.util.reflect.MethodTool.get(MethodTool.java:98)
	at top.linl.hook.TurnOffFriendInteractionLogoView.initOnce(TurnOffFriendInteractionLogoView.java:53)
```

`initOnce`（源码第 49-60 行）要找的是：

```java
MethodTool.find("com.tencent.mobileqq.profilecard.component.ProfileIntimateComponent")
        .name("initComponentViewContainer")
        .returnType(void.class)
        .get();
```

### 证据

```
rg -N 'ProfileIntimate' methods_8.8.50.txt            → 0 条
rg -N 'initComponentViewContainer' methods_8.8.50.txt → 0 条
```

8.8.50 的 `com.tencent.mobileqq.profilecard.component` 包全量类：

```
BaseProfileFavorComponent
ElegantProfileFavorComponent (+ 内部类 $1, $1$1)
ProfileCardComponentConstant
ProfileFavorComponent
```

只有「好友赞」组件，**没有 `ProfileIntimateComponent`**，整个 `profilecard` 包也没有任何 `Intimate*` 类。

`Intimate` 相关的类全部在别的包，且都不是资料卡组件：

```
Lcom/tencent/mobileqq/activity/aio/IntimateInfoView;         // 会话内亲密度浮层
Lcom/tencent/mobileqq/activity/aio/IntimateInfoShareHelper;
Lcom/tencent/mobileqq/activity/aio/intimate/IntimateScoreCardView;  // 亲密度卡片
Lcom/tencent/mobileqq/friends/intimate/IntimateInfoConstants;
Lcom/tencent/mobileqq/friends/intimate/CommonTroopListActivity;
```

`initComponentViewContainer` 在整个 112 万条索引里 0 命中 —— 8.8.50 的资料卡组件生命周期方法名不同（该版本资料卡是 `makeOrRefresh*` + `onDataUpdate` 体系，见 `BaseProfileFavorComponent`）。

### 结论

**不可修**。目标类与目标方法都不存在；「好友互动标识」作为资料卡组件是后续版本才引入的。`IntimateInfoView` 等类属于会话内亲密度浮层，与「关闭资料卡好友互动标识」不是同一处 UI，替换会改变功能语义，不做。

### 改动

`TurnOffFriendInteractionLogoView.java` 加 `isAvailable` override（Kotlin 侧 `val isAvailable: Boolean`
的 JVM getter 是 `isAvailable()`，`classExists` 带 `@JvmOverloads`，Java 可单参调用）：

```java
@Override
public boolean isAvailable() {
    return io.github.qauxv.util.ClassAvailabilityKt.classExists(
            "com.tencent.mobileqq.profilecard.component.ProfileIntimateComponent");
}
```

---

## 门控为何能消掉报错（机制证据）

`isAvailable` 在初始化链路的最前面被检查，返回 false 时 `initialize()` 根本不会被调用，因此不会产生
`ClassNotFoundException` / `NoSuchMethodException`，也不会在错误面板里留条目：

`app/src/main/java/io/github/qauxv/core/InjectDelayableHooks.java:243-258`

```java
IDynamicHook[] hooks = HookInstaller.queryAllAnnotatedHooks();
for (IDynamicHook h : hooks) {
    if (HookInstaller.isHookApplicable(h) && h.isEnabled() && h.isTargetProcess()) {
        if (!h.isPreparationRequired()) {
            h.initialize();
        }
        ...
```

`app/src/main/java/io/github/qauxv/core/HookInstaller.java:106-113`

```java
public static boolean isHookApplicable(@NonNull IDynamicHook hook) {
    try {
        return hook.isAvailable();
    } catch (Throwable e) {
        Log.e("isAvailable() failed for " + hook.getClass().getName() + ", assuming usable", e);
        return true;
    }
}
```

同一个模式已在 `im/mingxi/BlockLinkInfo.kt` 上验证过（该 hook 用
`override val isAvailable = classExists("com.tencent.qqnt.kernel.nativeinterface.LinkInfo")` 处理
8.8.50 上 `com.tencent.qqnt.*` 整包缺失）。

---

## 改动文件清单

| 文件 | 改动 |
| --- | --- |
| `source\app\src\main\java\cc\ioctl\hook\guild\RevokeGuildMsg.kt` | `processUpdateEvent` 硬查找 → `declaredMethods.firstOrNull` 安全探测；删 `findMethod` import |
| `source\app\src\main\java\io\github\duzhaokun123\hook\PhotoShareHook.kt` | 新增 `isAvailable`（`classExists("...sharepanel.config.SharePanelSceneData")`）+ import |
| `source\app\src\main\java\io\github\horange321\RemoveSecurityTipsBanner.kt` | 新增 `isAvailable`（`classExists("...troop.tipsbar.TroopSecurityTipsBanner")`）+ import |
| `source\app\src\main\java\top\linl\hook\TurnOffFriendInteractionLogoView.java` | 新增 `public boolean isAvailable()` override |

未触碰：`io/github/qauxv/hook/experimental/`、`core/`、`dsl/`、`fragment/`、
`cc/ioctl/hook/sideswipe/`、`com/xiaoniu/hook/DisableQQSettingMeV9.kt`、`HideQQValue.kt`。

---

## 编译验证

```
pwsh -NoProfile -File D:\DSH-Builds\QAuxiliaryFix\tools\build.ps1 -Task ":app:compileDebugKotlin"
→ BUILD SUCCESSFUL in 41s   (exit 0)

pwsh -NoProfile -File D:\DSH-Builds\QAuxiliaryFix\tools\build.ps1 -Task ":app:compileDebugJavaWithJavac"
→ BUILD SUCCESSFUL in 43s   (exit 0)
```

两次编译只剩既有的 deprecation / unchecked 提示，与本次改动无关（`AboutFragment.kt`、
`FuncStatusDetailsFragment.kt`、`TransactionHelper.kt`、`StartActivityHook.kt`）。

Kotlin 侧 `override val isAvailable` 与 Java 侧 `public boolean isAvailable()` 均通过签名校验
（若不匹配会直接报 `overrides nothing` / `method does not override`）。

`git diff --stat`（本人 4 个文件）：

```
 app/src/main/java/cc/ioctl/hook/guild/RevokeGuildMsg.kt              | 21 +-
 app/src/main/java/io/github/duzhaokun123/hook/PhotoShareHook.kt      |  9 +
 app/src/main/java/io/github/horange321/RemoveSecurityTipsBanner.kt   |  8 +
 app/src/main/java/top/linl/hook/TurnOffFriendInteractionLogoView.java| 13 +
```

工作树里其余改动（`core/MainHook.java`、`hook/experimental/ArkFallbackToText.kt`、
`me/singleneuron/data/*`、`hook/decorator/MiniAppToStruckMsg.kt`、`libs/LSPlant`）
属于另一名队友，未触碰。
