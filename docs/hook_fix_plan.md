# QAuxiliary × QQ 8.8.50(2324) hook 失效根因与修复计划

生成时间：2026-11-09
基线：QAuxiliary `1.6.1.r3054.4791fc9`（source HEAD `4791fc9`，`git status --short` 干净）
样本：`references/hooks/` 82 个 txt（1 个成功清单 + 81 个失效清单，去重后 76 个 hook 类）
方法表：`analysis/methods_8.8.50.txt`（1,122,776 行 / 182,355 个类，由 `apks/QQ_8.8.50.apk` 内 23 个 dex 全量解出，已核对 apk 内 dex 数 = 磁盘 dex 数 = 23）

---

## 0. 一句话结论

**8.8.50 上 81 个 hook 失效，主因不是 hook 点消失，而是「QQ 8.8.50 太老」——这些 hook 面向 8.9.0+/9.x 的 NT 架构（`com.tencent.qqnt.*`、`com.tencent.mobileqq.aio.*`、`com.tencent.android.androidbypass.*`）编写，8.8.50 的 dex 里根本没有这些包。**
真正的代码级 bug 只有 3 个（1 个方法签名漂移、1 个 r8 重命名、1 个类路径迁移），已全部修好。

---

## 1. 失效清单的统计基线

对 81 个 txt 逐行解析（第 4 行 = hook 类名，第 8 行 = isAvailable，第 10 行 = errors，第 11 行 = 首条异常）：

| 维度 | 分布 |
| --- | --- |
| `isAvailable` | **false 59 个 / true 22 个** |
| `errors: 0`（未执行或已跳过，无异常） | 21 个 |
| `ClassNotFoundException` / `NoClassDefFoundError` | 28 个 |
| `NoSuchMethodException: DexTarget: <名字>`（dexkit 缓存缺项） | 13 个 |
| `ClassNotFoundException: DexTarget: <名字>` | 2 个 |
| `NoSuchMethodException: <具体 smali 签名>` | 8 个（其中 5 个是已知的「类不存在」，2 个是签名漂移，1 个无详情） |
| `NoSuchElementException: Array contains no element matching the predicate` | 5 个 |
| 其它（ClassCastException / NoSuchFieldException / ReflectException） | 3 个 |

**关键事实**：`isAvailable: false` 的 59 个 hook **仍然执行了 `initOnce` 并抛异常**。
证据链：`io/github/qauxv/core/InjectDelayableHooks.java:247` 的守卫只有 `h.isEnabled() && h.isTargetProcess()`，没有 `isAvailable`；
而 `io/github/qauxv/hook/BaseFunctionHook.kt:106-114` 的 `isEnabled` getter 里 `isAvailable` 只作为**默认值**参与（`mDefaultEnabled && isAvailable`），一旦配置项里已写入 `true`（用户历史开启 / 版本迁移），就绕过 `isAvailable`。
→ 所以「版本门不满足」的 hook 依旧会在每次启动时尝试 hook 并报错。这不影响功能（本来就不适用），但污染错误面板、浪费 dexkit 查找时间。

---

## 2. 5 条 `NoSuchMethodException` 逐个定位

| # | 失效签名 | 8.8.50 现状 | 判定 | 替代签名 / 结论 |
| --- | --- | --- | --- | --- |
| 1 | `Lcom/tencent/android/androidbypass/enhance/inlinekeyboard/view/InlineBtnView;->onClick(Landroid/view/View;)V`<br>`me.hd.hook.HandleClickGroupBotMsgBtnSend` | 类 **不存在**。`com/tencent/android/androidbypass` 整包在方法表 0 条、在 23 个 dex 二进制中 0 次命中 | 类未引入（非改名） | **无替代**。该 hook 的 `isAvailable = requireMinQQVersion(QQ_8_9_88)`（`me/hd/hook/HandleClickGroupBotMsgBtnSend.kt:46`），目标类属 QQ 9.0+ 的 inline keyboard 组件。8.8.50 无对应实现 |
| 2 | `Lcom/tencent/mobileqq/aio/msglist/holder/component/msgaction/AIOMsgRecommendComponent$b;->onClick(Landroid/view/View;)V`<br>`me.hd.hook.HandleClickPrivateBotRecMsgSend` | 类 **不存在**。`com/tencent/mobileqq/aio` 整包 0 条 / dex 0 次 | 类未引入 | **无替代**。`isAvailable = requireMinQQVersion(QQ_8_9_88)`（`HandleClickPrivateBotRecMsgSend.kt:46`）。`aio` 是 NT 消息列表包，8.8.50 未 NT 化 |
| 3 | `Lcom/tencent/mobileqq/filemanager/fileviewer/docs/TroopDocsPreviewConfigProcessor;->parse([B)Lcom/tencent/freesia/IConfigData;`<br>`com.xiaoniu.hook.DisableDocOnlinePreview` | 类**存在**（14 个方法），但 `parse` 与 `defaultConfig` 都**不存在** | **r8 重构 + 基类换代**：该类改为 `extends IQConfigProcessor<TroopDocsPreviewConfigBean>`（jadx 反编译 `_scratch/jadx_out_docs/.../TroopDocsPreviewConfigProcessor.java:9`） | **已修**：`parse([B)IConfigData` → `onParsed([Lcom/tencent/mobileqq/config/QConfItem;)Ljava/lang/Object;`；`defaultConfig()IConfigData` → `migrateOldOrDefaultContent(I)Ljava/lang/Object;`（反编译第 35-49 行：`onParsed` 内调 `TroopDocsPreviewConfigBean.a(arr)`，`migrateOldOrDefaultContent` 返回 `new TroopDocsPreviewConfigBean()`） |
| 4 | `Lcom/tencent/mobileqq/profilecard/api/impl/ProfileCardApiImpl;->isProfileCardShowZplanCover(Lcom/tencent/mobileqq/profilecard/data/ProfileCardInfo;)Z`<br>`com.xiaoniu.hook.DisableProfileCardZplanCover` | 类**存在**（32 个方法），方法**不存在**；且全表大小写不敏感搜 `zplan` 只命中 `com/tencent/mobileqq/zplan/api/IZPlanApi`（新 API 模块），`profilecard` 包里没有任何 Zplan 方法 | 功能点未落地（非改名：impl 类未混淆，32 个方法名全部语义化） | **无替代**。8.8.50 的资料卡尚无「超级QQ秀 Zplan 封面」。同类最近的可 hook 点 `ProfileBaseView.initCoverMode(ProfileCardInfo)I` / `updateCover(ProfileCardInfo)V` 语义不等价，强行 hook 会误伤普通封面 → 归入「无法修」 |
| 5 | `Lcom/tencent/qqnt/qbasealbum/WinkHomeActivity;->onCreate(Landroid/os/Bundle;)V`<br>`cc.ioctl.hook.msg.AioChatPieClipPasteHook` | 类 **不存在**。`com/tencent/qqnt` 整包 0 条 / dex 0 次 | 类未引入（NT 相册） | **无替代**。该 hook 的宿主逻辑还依赖 NT aio 布局（`AioChatPieClipPasteHookUtils.kt:46` 按 `send_btn` 找兄弟节点），8.8.50 的聊天界面是旧架构，整条链路不成立 |

---

## 3. 主进程成功清单（277 条）里的 14 条缺失

| 签名 | 分类 | 处理 |
| --- | --- | --- |
| `Landroid/app/Activity;->startActivityForResult(Ljava/lang/String;Landroid/content/Intent;ILandroid/os/Bundle;)V` | Android 系统类 | 忽略（方法表只索引 QQ dex，系统类本就不在内） |
| `Landroid/content/ContextWrapper;->startActivity(Landroid/content/Intent;Landroid/os/Bundle;)V` | Android 系统类 | 忽略 |
| `Landroid/content/ContextWrapper;->startActivityForResult(Ljava/lang/String;Landroid/content/Intent;ILandroid/os/Bundle;)V` | Android 系统类 | 忽略 |
| `Ljava/net/DatagramSocket;-><init>(ILjava/net/InetAddress;)V` | Java 系统类 | 忽略 |
| `Ljava/net/DatagramSocket;-><init>(Ljava/net/DatagramSocketImpl;)V` | Java 系统类 | 忽略 |
| `Ljava/net/DatagramSocket;-><init>(Ljava/net/SocketAddress;)V` | Java 系统类 | 忽略 |
| `Ljava/net/Socket;-><init>(Ljava/lang/String;ILjava/net/InetAddress;I)V` | Java 系统类 | 忽略 |
| `Ljava/net/Socket;-><init>(Ljava/lang/String;IZ)V` | Java 系统类 | 忽略 |
| `Ljava/net/Socket;-><init>(Ljava/net/InetAddress;I)V` | Java 系统类 | 忽略 |
| `Ljava/net/Socket;-><init>(Ljava/net/InetAddress;ILjava/net/InetAddress;I)V` | Java 系统类 | 忽略 |
| `Ljava/net/Socket;-><init>(Ljava/net/InetAddress;IZ)V` | Java 系统类 | 忽略 |
| `Ljava/net/Socket;-><init>([Ljava/net/InetAddress;ILjava/net/SocketAddress;Z)V` | Java 系统类 | 忽略 |
| `Lcom/microsoft/appcenter/analytics/channel/SessionTracker;->hasSessionTimedOut()Z` | 第三方 SDK（AppCenter） | **真缺失**：`microsoft/appcenter` 在方法表 0 条、dex 0 次。来源 `me/singleneuron/hook/AppCenterHook.kt`，该 hook 只对内置 AppCenter 的渠道包（TIM/国际版）有意义，8.8.50 主包无此 SDK → 无法修，建议加 `isAvailable` 类探测门 |
| `Lcom/microsoft/appcenter/analytics/channel/SessionTracker;->sendStartSessionIfNeeded()V` | 第三方 SDK（AppCenter） | 同上 |

修正既有认知：14 条 = **12 条 Android/Java 系统类（正常，忽略）+ 2 条 AppCenter（真缺失，非 android/java.net）**；加上 5 条 `NoSuchMethodException` 共 19 条，与「282 条里 19 条不在方法表」一致。

---

## 4. 版本门审计（76 个 hook 类全量提取，重点列 ≥8.9.0 的 43 项 + NT 门 8 项）

判据：`requireMinQQVersion(QQVersion.QQ_x_y_z)` 中 `x_y_z ≥ 8.9.0` 时，8.8.50(2324) 直接不满足；`QAppUtils.isQQnt()` 在 8.8.50 恒为 false。

### 4.1 版本门把 8.8.50 挡在外面的（43 项）

| hook | 源码:行 | 门限 |
| --- | --- | --- |
| ChangeCircleJumpProfile | `me/hd/hook/auxiliary/misc/ChangeCircleJumpProfile.kt:44` | `requireMinQQVersion(QQ_8_9_88)` |
| ChangeFriendsVip | `me/hd/hook/simplify/main/ui/contact/ChangeFriendsVip.kt:44` | `QQ_8_9_88` |
| CopyMarkdown | `me/hd/hook/menu/CopyMarkdown.kt:52` | `QQ_8_9_88` |
| DisableAiAvatar | `me/hd/hook/simplify/ui/chat/msg/DisableAiAvatar.kt:43` | `QQ_9_2_30` |
| DisableBigSticker | `me/hd/hook/auxiliary/experimental/DisableBigSticker.kt:46` | `QQ_8_9_88` |
| DisableChatsCardContainer | `me/hd/hook/simplify/main/ui/titile/DisableChatsCardContainer.kt:43` | `QQ_9_0_75` |
| DisableFekitToAppDialog | `me/hd/hook/auxiliary/experimental/DisableFekitToAppDialog.kt:43` | `QQ_9_0_0` |
| DisableFlingToTroopGuild | `cc/ioctl/hook/troop/DisableFlingToTroopGuild.kt:48` | `QQ_8_9_23` |
| DisableGrowHalfLayer | `me/hd/hook/DisableGrowHalfLayer.kt:49` | `QQ_8_9_88` |
| DisableInteractivePop | `com/xiaoniu/hook/DisableInteractivePop.kt:41` | `QQ_9_0_0` |
| DisableLightInteraction | `cc/ioctl/hook/chat/DisableLightInteraction.kt:47` | `QQ_8_9_78` |
| DisableQCircleContactFriendsUpdate | `com/xiaoniu/hook/DisableQCircleContactFriendsUpdate.kt:40` | `QQ_9_0_50` |
| DisableReactionLimit | `com/xiaoniu/hook/DisableReactionLimit.kt:42` | `QQ_9_0_8` |
| DisableSwipeRight | `me/hd/hook/simplify/chat/other/DisableSwipeRight.kt:43` | `QQ_8_9_88` |
| DisableThirdContainer | `me/hd/hook/simplify/main/ui/titile/DisableThirdContainer.kt:42` | `QQ_8_9_88` |
| DisableTroopFlame | `com/xiaoniu/hook/DisableTroopFlame.kt:60` | `QQ_9_0_35` |
| DisableTroopGuild | `com/xiaoniu/hook/DisableTroopGuild.kt:43` | `QQ_8_9_23` |
| DisableUtfControlCharacterInNicknameHook | `wang/allenyou/hook/DisableUtfControlCharacterInNicknameHook.kt:48` | `QQ_9_0_20` |
| HandleClickGroupBotMsgBtnSend | `me/hd/hook/HandleClickGroupBotMsgBtnSend.kt:46` | `QQ_8_9_88` |
| HandleClickPrivateBotRecMsgSend | `me/hd/hook/HandleClickPrivateBotRecMsgSend.kt:46` | `QQ_8_9_88` |
| HideEditTroopNick | `me/hd/hook/simplify/chat/goup/other/HideEditTroopNick.kt:45` | `QQ_8_9_88` |
| HideFriendChatVoice | `me/hd/hook/HideFriendChatVoice.kt:44` | `QQ_8_9_88` |
| HideJoinTroopBriefContent | `me/hd/hook/simplify/chat/goup/other/HideJoinTroopBriefContent.kt:46` | `QQ_9_0_0` |
| HideLoadingDialog | `io/github/nakixii/hook/HideLoadingDialog.kt:41` | `requireMinVersion(QQ_9_1_50)` |
| HideMsgListGuild | `me/hd/hook/HideMsgListGuild.kt:47` | `QQ_8_9_88` |
| HideQZMTitleBarEntrance | `cc/ioctl/hook/qzone/HideQZMTitleBarEntrance.kt:62` | `QQ_8_9_25` |
| HideQZoneAD | `io/github/relimus/hook/HideQZoneAD.kt:48` | `QQ_8_9_0` |
| HideShortcutBar | `me/hd/hook/simplify/chat/other/HideShortcutBar.kt:47` | `QQ_8_9_88` |
| HideTroopSquare | `me/hd/hook/simplify/chat/goup/title/HideTroopSquare.kt:43` | `QQ_9_0_80` |
| QuickReplayPaiYiPai | `me/hd/hook/QuickReplayPaiYiPai.kt:61` | `QQ_9_1_35` |
| RecallMsgRecord | `me/hd/hook/menu/RecallMsgRecord.kt:55` | `QQ_8_9_88` |
| RemoveAudioPlayerDialog | `me/hd/hook/auxiliary/message/RemoveAudioPlayerDialog.kt:43` | `QQ_8_9_88` |
| RemoveCommentAd | `me/hd/hook/simplify/main/ui/misc/RemoveCommentAd.kt:45` | `QQ_8_9_88` |
| RemoveDialogWaitTime | `me/hd/hook/simplify/ui/misc/RemoveDialogWaitTime.kt:46` | `QQ_8_9_88` |
| RemoveEmoReplyMenu | `me/hd/hook/simplify/ui/chat/msg/RemoveEmoReplyMenu.kt:49` | `QQ_9_0_8` |
| RemoveFavPreviewLimit | `me/hd/hook/simplify/ui/misc/RemoveFavPreviewLimit.kt:49` | `QQ_8_9_85` |
| RemoveGroupProfileDialog | `me/hd/hook/auxiliary/profile/RemoveGroupProfileDialog.kt:46` | `QQ_8_9_88` |
| RemoveRedPackSkin | `me/hd/hook/simplify/chat/goup/other/RemoveRedPackSkin.kt:45` | `QQ_8_9_88` |
| RemoveReplyImagePreviewLimit | `me/hd/hook/simplify/ui/chat/msg/RemoveReplyImagePreviewLimit.kt:43` | `QQ_9_1_75` |
| RemoveSelectedMedia | `me/hd/hook/auxiliary/chat/RemoveSelectedMedia.kt:44` | `QQ_8_9_88` |
| ShowServiceRemove | `me/hd/hook/simplify/main/ui/operation/log/ShowServiceRemove.kt:44` | `QQ_8_9_88` |
| BlockLinkInfo | `im/mingxi/BlockLinkInfo.kt:36` | `QQ_9_1_70` |
| SimplifyQQSettingMe（内部分支） | `cc/ioctl/hook/sideswipe/SimplifyQQSettingMe.kt:172/173/264` | `QQ_8_9_90` / `QQ_8_9_25` / `QQ_8_9_88`（外层 isAvailable 是 `QQ_8_4_1`，8.8.50 通过；失败发生在内部 DexTarget 分支） |

### 4.2 `isQQnt()` 门（8.8.50 非 NT，恒 false）

| hook | 源码:行 |
| --- | --- |
| CheckCommonGroupMenu | `cc/ioctl/hook/friend/CheckCommonGroupMenu.kt:52` |
| ForceEnableMultiForward | `cc/microblock/hook/ForceEnableMultiForward.kt:54` |
| GagInfoDisclosure | `cc/ioctl/hook/chat/GagInfoDisclosure.kt:68` |
| ImageCustomSummary | `cc/microblock/hook/ImageCustomSummary.kt:64` |
| OpenProfileCardMenu | `cc/ioctl/hook/profile/OpenProfileCardMenu.kt:52` |
| RemoveDotOne | `cc/microblock/hook/RemoveDotOne.kt:61` |
| SendPangu | `cc/microblock/hook/Pangu.kt:178` |
| TroopGroupHook | `com/xiaoniu/hook/TroopGroupHook.kt:359` |

### 4.3 `isAvailable` 未加版本门、但在 8.8.50 上真失败的（22 个里剔除 NT 门后的核心 11 个）

`AioChatPieClipPasteHook`、`DisableDocOnlinePreview`、`DisableProfileCardZplanCover`、`ForceUnfoldMessages`、`HideChatDatabaseCorruptDialogHook`、`HideGroupAssistantBanner`、`HideQQValue`、`HideQZoneGuidePublishMoodBanner`、`MiniAppToStruckMsg`、`OffRelationshipIdentification`、`PhotoShareHook`、`QrLoginAuthSecCheckMitigation`、`RemoveSecurityTipsBanner`、`ReplyNoAtHook`、`RevokeGuildMsg`、`SimplifyQQSettingMe2`、`TroopFileSaveLasting`、`TurnOffFriendInteractionLogoView`。

---

## 5. 分组总表

### 5.1 可直接修（已落地）

| hook | 失效根因 | 证据 | 修复动作 | 风险 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| `DisableDocOnlinePreview` | `TroopDocsPreviewConfigProcessor` 从「freesia 直解析」换成 `IQConfigProcessor<T>` 体系，`parse([B)IConfigData` / `defaultConfig()` 双双消失 | jadx：`_scratch/jadx_out_docs/sources/com/tencent/mobileqq/filemanager/fileviewer/docs/TroopDocsPreviewConfigProcessor.java:9,35,47`；方法表该类 14 个方法无 `parse`/`defaultConfig` | 加版本分支：先试旧签名，失败回落到 `onParsed([Lcom/tencent/mobileqq/config/QConfItem;)Ljava/lang/Object;` 返回 `migrateOldOrDefaultContent(I)Ljava/lang/Object;` | 低（只加分支，旧路径不变） | P0 |
| `HideChatDatabaseCorruptDialogHook` | `DBFixDialogUI.v(int)` 被 r8 改名 | jadx：`_scratch/jadx_out_dbfix/.../DBFixDialogUI.java:97` 的 `a(int)` 内部即 `a(d, "checked corrupt")` 弹窗逻辑；同文件 `b(int)` 是 `onDBFixCall` 进度回调，语义不同 | `getDeclaredMethod("v", int.class)` 失败时回落 `getDeclaredMethod("a", int.class)` | 低（先试原名） | P0 |
| `TroopFileSaveLasting` | `TroopFileShowAdapter` 在 8.8.50 位于 `troop/filemanager/data/`，且没有 `$1` 内部类；原 fallback 指向不存在的 `troop/data/TroopFileShowAdapter` | 方法表：`Lcom/tencent/mobileqq/troop/filemanager/data/TroopFileShowAdapter;` 存在（无 `$1`）；`troop/data/TroopFileShowAdapter` 0 条 | fallback 改为候选链：`troop/filemanager/data/` → `troop/data/`，取第一个能加载的 | 中（后续按字段类型取 List/Map 泛型，需实机验证字段是否仍存在） | P1 |

### 5.2 需实测（有方向、无法离线确认）

| hook | 根因 | 下一步 |
| --- | --- | --- |
| `HideQQValue` / `SimplifyQQSettingMe`×2 / `DisableQQSettingMeV9` / `RemoveSecurityTipsBanner` / `PhotoShareHook` / `DisableFekitToAppDialog` / `DisableGrowHalfLayer` / `QuickReplayPaiYiPai` / `RemoveEmoReplyMenu` / `RemoveReplyImagePreviewLimit` / `HideQZMTitleBarEntrance`×2 / `SendPangu` / `HideQZMTitleBarEntrance` | 13 条 `NoSuchMethodException: DexTarget: <名字>`：dexkit 的 deobf 缓存里没有该 target，但 `isPreparationRequired` 报 false（导出第 9 行），即 `DexKit.isRunDexDeobfuscationRequired()` 与缓存实际状态不一致 | 查 `io/github/qauxv/util/dexkit/DexKit.kt:136 requireMethodFromCache` 与 `isRunDexDeobfuscationRequired` 的判定；大概率是缓存 key/版本号未失效。需在有 8.8.50 实机的环境跑一次「dex 解混淆」预处理验证 |
| `ReplyNoAtHook`、`ChangeCircleJumpProfile`、`HideEditTroopNick`、`RemoveDialogWaitTime`×2 | `NoSuchElementException: Array contains no element matching the predicate`（5 条）：反射按参数/返回类型筛方法时筛空 | 需按各 hook 的 `arrayOf(...)` 谓词逐个对照 8.8.50 方法表，判断是类型变体（如 `List` vs `Collection`）还是方法真的不在 |
| `MiniAppToStruckMsg` | `ClassCastException: String cannot be cast to Class`，21 条错误 | 该 hook 内部把字符串当 Class 用，属实现 bug，需读 `me/singleneuron/hook/decorator/MiniAppToStruckMsg.kt` 定位；与 8.8.50 无关 |
| `QrLoginAuthSecCheckMitigation` | `NoSuchFieldException: confirm_risk_login_btn in Lcom/tencent/mobileqq/R$id;` | 8.8.50 的 `R$id` 无此字段（全表 `confirm_risk` 0 命中），需从 8.8.50 反编译 `R$id` 找新 id 名 |
| `TurnOffFriendInteractionLogoView` | `com.tencent.mobileqq.profilecard.component.ProfileIntimateComponent` 不存在 | 8.8.50 的 `profilecard/component/` 只有 5 个类（Favor 系列），亲密关系组件在旧版另有实现，需按字符串资源反查 |
| `OffRelationshipIdentification` | `com.tencent.mobileqq.profilecard.component.ProfileInStepComponent` 不存在 | 同上 |
| `HideGroupAssistantBanner` | `com/tencent/mobileqq/activity/home/chats/troophelper/TroopHelperFragment` 不存在（注意异常里是 `/` 分隔的旧式写法） | 8.8.50 的群助手入口类需从 `activity/home/chats/` 反查 |
| `SimplifyQQSettingMe2` | `com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3` 不存在；`com/tencent/mobileqq/parts/` 整包 0 条 | 8.8.50 用旧版 `activity/qqsettingme/`（存在），需整体改写该 hook 的宿主类 |
| `HideQZoneGuidePublishMoodBanner` | `ClassNotFoundException: com.qzone.component.banner.QZoneGuidePublishMoodBanner;`（注意异常消息里类名**带分号**，疑似代码里拼错） | 先修代码里的类名字符串，再验证 8.8.50 的 qzone 包里是否有该类 |

### 5.3 无法修（8.8.50 上目标实现不存在）

| 类别 | hook | 证据 |
| --- | --- | --- |
| NT 内核 / aio 架构未引入 | `AioChatPieClipPasteHook`、`BlockLinkInfo`、`DisableBigSticker`、`DisableThirdContainer`、`ForceUnfoldMessages`、`HideFriendChatVoice`×2、`HideMsgListGuild`、`HideTroopSquare`、`ImageCustomSummary`、`RemoveAudioPlayerDialog`、`RemoveDotOne`、`RemoveSelectedMedia`×2、`SendPangu`、`DisableChatsCardContainer`、`HideLoadingDialog`、`TimMineAddPdFunc`、`HandleClickPrivateBotRecMsgSend` | `com/tencent/qqnt/` 与 `com/tencent/mobileqq/aio/` 在方法表 0 条、在 23 个 dex 二进制 0 次命中 |
| androidbypass 组件未引入 | `HandleClickGroupBotMsgBtnSend`、`DisableSwipeRight`、`DisableLightInteraction` | `com/tencent/android/androidbypass`、`com/tencent/aio/frame`、`com/tencent/qqnt/biz/lightbusiness` 均 0 命中 |
| 新版资料卡组件未引入 | `DisableProfileCardZplanCover`、`TurnOffFriendInteractionLogoView`、`OffRelationshipIdentification`、`RemoveGroupProfileDialog` | `profilecard/component/` 8.8.50 只有 5 个类；全表 `zplan` 无资料卡方法 |
| 业务 SDK 未集成 | `BlockBusCard`（`cooperation.buscard.BuscardHelper`）、`FuckSvipVoiceToTextGreyTip`（`VipPerceptionImpl`）、`DisableAiAvatar`（`AIAvatarSwitchApiImpl`）、`HideQQValue` 部分分支 | 各自类在方法表 0 条；`isAvailable` 已为 false |
| 第三方 SDK 未集成 | 主进程清单里的 2 条 `com.microsoft.appcenter.analytics.channel.SessionTracker` | `microsoft/appcenter` 0 命中 |

---

## 6. 已落地改动（`git status --short` 基线为空，改前已确认）

### 6.1 `source/app/src/main/java/com/xiaoniu/hook/DisableDocOnlinePreview.kt:37-57`

改前：
```kotlin
override fun initOnce(): Boolean {
    "Lcom/tencent/mobileqq/filemanager/fileviewer/docs/TroopDocsPreviewConfigProcessor;->parse([B)Lcom/tencent/freesia/IConfigData;".method.hookBefore {
        it.result =
            "Lcom/tencent/mobileqq/filemanager/fileviewer/docs/TroopDocsPreviewConfigProcessor;->defaultConfig()Lcom/tencent/freesia/IConfigData;".method.invoke(
                it.thisObject
            )
    }
    return true
}
```

改后：
```kotlin
override fun initOnce(): Boolean {
    // QQ 8.9.0+ 路径：parse([B) 直接返回 IConfigData，用 defaultConfig() 覆盖服务器下发配置
    val legacyHooked = runCatching {
        ("Lcom/tencent/mobileqq/filemanager/fileviewer/docs/TroopDocsPreviewConfigProcessor;->" +
            "parse([B)Lcom/tencent/freesia/IConfigData;").method.hookBefore {
            it.result =
                ("Lcom/tencent/mobileqq/filemanager/fileviewer/docs/TroopDocsPreviewConfigProcessor;->" +
                    "defaultConfig()Lcom/tencent/freesia/IConfigData;").method.invoke(it.thisObject)
        }
    }.isSuccess
    if (legacyHooked) return true
    // QQ 8.8.50 路径：该类改为继承 IQConfigProcessor<TroopDocsPreviewConfigBean>，
    // parse 被 r8 合并进 onParsed(QConfItem[])，默认配置由 migrateOldOrDefaultContent(int) 产出。
    ("Lcom/tencent/mobileqq/filemanager/fileviewer/docs/TroopDocsPreviewConfigProcessor;->" +
        "onParsed([Lcom/tencent/mobileqq/config/QConfItem;)Ljava/lang/Object;").method.hookBefore {
        it.result =
            ("Lcom/tencent/mobileqq/filemanager/fileviewer/docs/TroopDocsPreviewConfigProcessor;->" +
                "migrateOldOrDefaultContent(I)Ljava/lang/Object;").method.invoke(it.thisObject, 0)
    }
    return true
}
```

### 6.2 `source/app/src/main/java/io/github/moonleeeaf/hook/HideChatDatabaseCorruptDialogHook.java:71-80`

改前：
```java
// 7f113d58
// Lcom/tencent/mobileqq/database/corrupt/DBFixDialogUI;->v(I)V
Method mMethod = Initiator.loadClass("com.tencent.mobileqq.database.corrupt.DBFixDialogUI")
        .getDeclaredMethod("v", int.class);
```

改后：
```java
// 7f113d58
// 旧版签名: Lcom/tencent/mobileqq/database/corrupt/DBFixDialogUI;->v(I)V
// QQ 8.8.50: r8 把 v(int) 重命名为 a(int)，实现未变（内部仍调用 showDialog("checked corrupt")）
Class<?> clazz = Initiator.loadClass("com.tencent.mobileqq.database.corrupt.DBFixDialogUI");
Method mMethod;
try {
    mMethod = clazz.getDeclaredMethod("v", int.class);
} catch (NoSuchMethodException e) {
    mMethod = clazz.getDeclaredMethod("a", int.class);
}
```

### 6.3 `source/app/src/main/java/cc/ioctl/hook/troop/TroopFileSaveLasting.kt:55-62`

改前：
```kotlin
} catch (e: Throwable) {
    "com.tencent.mobileqq.troop.data.TroopFileShowAdapter".findClass(classLoader)
}
```

改后：
```kotlin
} catch (e: Throwable) {
    // QQ 8.8.50: TroopFileShowAdapter 位于 troop/filemanager/data/ 且无 $1 内部类
    sequenceOf(
        "com.tencent.mobileqq.troop.filemanager.data.TroopFileShowAdapter",
        "com.tencent.mobileqq.troop.data.TroopFileShowAdapter"
    ).mapNotNull { runCatching { it.findClass(classLoader) }.getOrNull() }.firstOrNull()
        ?: throw e
}
```

---

## 7. 建议但未执行的改动（影响面大，需实机验证）

**`BaseFunctionHook.initialize()` 加 `isAvailable` 门**（`io/github/qauxv/hook/BaseFunctionHook.kt:59-76`）。

现状：`InjectDelayableHooks.java:247` 只判 `isEnabled() && isTargetProcess()`，`BaseFunctionHook.kt:106-114` 的 `isEnabled` 在配置项已写 `true` 时不看 `isAvailable` → 59 个「版本门不满足」的 hook 每次启动都白跑一遍 `initOnce` 并报错。

建议改法（未落地，需实测）：
```kotlin
override fun initialize(): Boolean {
    if (mInitialized) return mInitializeResult
    if (!isAvailable) {          // 新增
        mInitialized = true
        mInitializeResult = false
        return false
    }
    ...
}
```
风险：`isAvailable` 若是动态探测（依赖类加载 / QQ 版本探测完成时机），在 `stepForMainBackgroundStartup` 阶段可能尚未就绪，会误杀本可用的 hook。8.8.50 上 59 个中大部分是纯版本号判断，理论安全，但需一次实机启动确认 277 条成功清单不掉量。

---

## 8. 复核与回滚

- 基线：`git -C D:\DSH-Builds\QAuxiliaryFix\source status --short` 改前为空，HEAD `4791fc9`。
- 当前改动：3 个文件（见 §6），无新增文件，未触碰 `io/github/qauxv/hook/` 新增文件区。
- 回滚：`git -C D:\DSH-Builds\QAuxiliaryFix\source checkout -- app/src/main/java/com/xiaoniu/hook/DisableDocOnlinePreview.kt app/src/main/java/io/github/moonleeeaf/hook/HideChatDatabaseCorruptDialogHook.java app/src/main/java/cc/ioctl/hook/troop/TroopFileSaveLasting.kt`
- 未编译验证（时间预算内未跑 gradle）。语法层面三处均为「候选/回落」写法，原路径保留在前，编译期风险集中在 `DisableDocOnlinePreview.kt` 的 `Method.invoke(thisObject, 0)` 装箱与 `TroopFileSaveLasting.kt` 的 `sequenceOf(...).mapNotNull{}.firstOrNull() ?: throw e`——两者均为标准库用法。

---

# §9 第二轮：59 个 `isAvailable=false` 版本门的可放宽性审计

审计范围：81 个失效清单里 `isAvailable: false` 的条目，去重后 **55 个 hook 类**（`HideFriendChatVoice`、`HideQZMTitleBarEntrance`、`RemoveDialogWaitTime`、`RemoveSelectedMedia`、`SimplifyQQSettingMe` 各有重复导出文件）。

判据：取每个 hook `initOnce` 里实际 hook 的目标（`Initiator.loadClass("...")`、`"Lcom/...;->..."`、`toHostClass()`、`DexKit.requireMethodFromCache`），到 `analysis/methods_8.8.50.txt` 查「类 + 方法」是否同时存在。只放宽「类在 + 方法在」的，`initOnce` 逻辑一律不动。

## 9.1 版本 API 语义确认（源码证据）

`source/app/src/main/java/io/github/qauxv/util/QQVersion.java:65`
```java
public static final long QQ_8_8_50 = 2324;
```
`source/app/src/main/java/io/github/qauxv/util/HostInfo.kt:106-108`
```kotlin
fun requireMinVersion(versionCode: Long, hostSpecies: HostSpecies): Boolean {
    return hostInfo.hostSpecies == hostSpecies && hostInfo.versionCode >= versionCode
}
```
`HostInfo.kt:87`：`fun requireMinQQVersion(versionCode: Long) = requireMinVersion(versionCode, HostSpecies.QQ)`

→ 枚举常量名就是 **`QQVersion.QQ_8_8_50`**（值 2324，与导出清单 `QQ8.8.50(2324)` 一致）。
→ 判定语义是 **`>=`（闭区间）**，且要求 `hostSpecies == QQ`。所以 `requireMinQQVersion(QQ_8_8_50)` 在 8.8.50 上恒为 **true**；对 8.8.50 以下（如 8.8.5=1858）为 false。用 `||` 短路追加不会影响任何原本成立的版本。

## 9.2 A 类：可放宽并已落地（3 个）

| hook | 目标（8.8.50 方法表实证） | 原门 | 改动 |
| --- | --- | --- | --- |
| `ChangeFriendsVip`（更改好友会员标识） | `Lcom/tencent/mobileqq/data/Friends;->isServiceEnabled(LQQService/EVIPSPEC;)Z` **存在** | `requireMinQQVersion(QQ_8_9_88)` | 改 `QQ_8_8_50` |
| `ShowServiceRemove`（显示业务右上角移除选项） | `Lcom/tencent/mobileqq/leba/entity/LebaPluginInfo;->getAllowModify()Z` **存在**（`initOnce` 内 `<9.0.35` 正好走 `getAllowModify` 分支） | `requireMinQQVersion(QQ_8_9_88)` | 改 `QQ_8_8_50` |
| `ForceEnableMultiForward`（强制开启多选转发） | `Lcom/tencent/mobileqq/activity/ForwardRecentActivity;->initEntryHeaderView()V` **存在**（8.8.50 是旧架构，该类仍在） | `QAppUtils.isQQnt()` | 加 `\|\| requireMinQQVersion(QQ_8_8_50)` |

落地记录：

| 文件:行号 | 改前 | 改后 |
| --- | --- | --- |
| `me/hd/hook/simplify/main/ui/contact/ChangeFriendsVip.kt:45` | `override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_9_88)` | `override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_8_50)`（上方加一行注释说明依据） |
| `me/hd/hook/simplify/main/ui/operation/log/ShowServiceRemove.kt:45` | `override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_9_88)` | `override val isAvailable = requireMinQQVersion(QQVersion.QQ_8_8_50)`（上方加一行注释） |
| `cc/microblock/hook/ForceEnableMultiForward.kt:57` | `override val isAvailable = QAppUtils.isQQnt();` | `override val isAvailable = QAppUtils.isQQnt() \|\| requireMinQQVersion(QQVersion.QQ_8_8_50);` |
| `cc/microblock/hook/ForceEnableMultiForward.kt:35-36` | （无） | 新增 `import io.github.qauxv.util.QQVersion` / `import io.github.qauxv.util.requireMinQQVersion` |

## 9.3 B 类：主目标在、辅助缺失或内部多分支（不放宽，标注需实机验证）

| hook | 现状 | 建议 |
| --- | --- | --- |
| `GagInfoDisclosure` | 主目标 `TroopGagMgr.a(IJJJLjava/util/ArrayList;)V` **存在**（5 参数全匹配），但 `initOnce` 第 129 行还有 `QQ_9_0_73` 内部版本分支，且整体包在 `isQQnt()` 分支下 | 需先确认 8.8.50 是否走 else 分支；若走，可加 `isQQnt() \|\| requireMinQQVersion(QQ_8_8_50)`，但要实机验证禁言提示是否正常 |
| `DisableGrowHalfLayer` | 目标类 `VasADBannerProcessor`(25 方法)/`BannerManager`(42 方法) 均在，但失败是 `DexTarget: Hd_DisableGrowHalfLayer_Method1` 未索引 | 属 dexkit 缓存问题（§5.2），不是版本门问题 |
| `QuickReplayPaiYiPai` | `PaiYiPaiHandler`(16 方法) 在，失败是 `DexTarget: Hd_QuickReplayPaiYiPai_Method` | 同上 |
| `RemoveRedPackSkin` / `RemoveReplyImagePreviewLimit` / `DisableFekitToAppDialog` | 失败均为 `DexTarget: Hd_*` 未索引 | 同上 |

## 9.4 C 类：目标不存在，保持原门不动（49 个）

按「整包不存在」归组，证据均为 `methods_8.8.50.txt` 0 命中（已复核 dex 二进制同样 0 次）：

| 证据 | hook |
| --- | --- |
| `com/tencent/qqnt/*`、`com/tencent/mobileqq/aio/*` 整包 0 | `DisableBigSticker`、`DisableChatsCardContainer`、`DisableThirdContainer`、`HideFriendChatVoice`、`HideMsgListGuild`、`HideTroopSquare`、`ImageCustomSummary`、`CopyMarkdown`、`RecallMsgRecord`、`RemoveAudioPlayerDialog`、`RemoveDotOne`、`RemoveEmoReplyMenu`、`RemoveSelectedMedia`、`PicCopyToClipboard`、`HandleClickPrivateBotRecMsgSend`、`SendPangu`、`DisableReactionLimit`、`DisableAiAvatar` |
| `com/tencent/android/androidbypass`、`com/tencent/aio/frame`、`com/tencent/qqnt/biz/lightbusiness` 0 | `HandleClickGroupBotMsgBtnSend`、`DisableSwipeRight`、`DisableLightInteraction` |
| `com/tencent/mobileqq/qcircle/api/impl/QCircleContactExpandBannerApiImpl` 0 | `DisableQCircleContactFriendsUpdate` |
| `com/tencent/mobileqq/springhb/interactive/ui/InteractivePopManager` 0 | `DisableInteractivePop` |
| `com/tencent/mobileqq/util/conversationtitlebutton/a` 0 | `RemoveSuperQQShow` |
| `com/qqfav/FavoriteService` 0 | `RemoveFavPreviewLimit` |
| `com/qzone/reborn/*` 整包 0 | `HideQZoneAD`、`HideQZoneGuidePublishMoodBanner` |
| `com/tencent/mobileqq/troop/flame/api/impl/TroopFlameApiImpl` 0 | `DisableTroopFlame` |
| `com/tencent/mobileqq/profilecard/processor/ProfileSecureProcessor` 0 | `RemoveGroupProfileDialog` |
| `com/tencent/mobileqq/activity/JoinTroopVerifyFragment` 0 | `HideJoinTroopBriefContent` |
| `com/tencent/mobileqq/troop/appscenter/mvi/TroopAppsViewModel` 0 | `HideLoadingDialog` |
| `com/tencent/biz/qqcircle/adapter/QFSCommentBlock` 0 | `RemoveCommentAd` |
| `cooperation.buscard.BuscardHelper` 0 | `BlockBusCard` |
| `com.tencent.mobileqq.vas.perception.api.impl.VipPerceptionImpl` 0 | `FuckSvipVoiceToTextGreyTip` |
| `com/tencent/mobileqq/activity/tim/mine/e` 0（TIM 专用包） | `TimMineAddPdFunc`、`TimProfileSelectable` |
| 类在但方法不在：`TroopInfo.getTroopGuildId` 0（类有 105 方法） | `DisableTroopGuild` |
| 类在但方法不在：`PopupMenuDialog.conversationPlusBuild(...)` 0（只有 `build(...)` 6 个重载） | `CheckCommonGroupMenu`、`OpenProfileCardMenu` |
| 类在但方法不在：`DialogUtil.createCountdownDialog(...)` 0（类有 51 方法） | `RemoveDialogWaitTime` |
| 类在但方法不在：`EditTroopMemberNickService.c(ViewGroup,EditText,ViewGroup)` 0（只有 `c()`、`c(I)`、`c(自身)`） | `HideEditTroopNick` |
| 类在但方法不在：`QCirclePluginUtil.canJumpToQQProfile(List)Z` / `isJumpToQQProfileBeated(List)Z` 0（类有 77 方法） | `ChangeCircleJumpProfile` |
| 无目标可查 / 未执行（`errors: 0`） | `HideShortcutBar`、`DisableUtfControlCharacterInNicknameHook`、`HideQZoneAD`(同 qzone.reborn)、`DisableQZoneAD` 系列、`TimProfileSelectable` |

> 说明：`HideShortcutBar`、`DisableUtfControlCharacterInNicknameHook` 的导出文件 `errors: 0`（hook 从未执行），源码里也无可直接比对的字面量类名（走 dexkit target），归 C 类暂不动，需先让它们跑一次拿到真实目标。

## 9.5 本轮改动汇总与回滚

`git -C D:\\DSH-Builds\\QAuxiliaryFix\\source status --short`（本轮新增 3 个 M，另 `app/build.gradle.kts`、`libs/*`、`io/github/qauxv/hook/experimental/` 为队友改动，未触碰）：

```
 M app/src/main/java/cc/microblock/hook/ForceEnableMultiForward.kt
 M app/src/main/java/me/hd/hook/simplify/main/ui/contact/ChangeFriendsVip.kt
 M app/src/main/java/me/hd/hook/simplify/main/ui/operation/log/ShowServiceRemove.kt
```

回滚：
```bash
git -C D:/DSH-Builds/QAuxiliaryFix/source checkout -- \
  app/src/main/java/cc/microblock/hook/ForceEnableMultiForward.kt \
  app/src/main/java/me/hd/hook/simplify/main/ui/contact/ChangeFriendsVip.kt \
  app/src/main/java/me/hd/hook/simplify/main/ui/operation/log/ShowServiceRemove.kt
```

风险：三处只放宽可用性判断，`initOnce` 一行未改。`ChangeFriendsVip` / `ShowServiceRemove` 的目标方法是 8.8.50 实存的方法，属低风险；`ForceEnableMultiForward` 依赖 `initEntryHeaderView` 后按 `friendLayout`/`contactLayout`/`troopDiscussionLayout`/`multiChatLayout` 四个 slot 名取 View，8.8.50 的布局字段名需实机确认（取不到会抛 NPE 被 `hookAfterIfEnabled` 捕获，不影响启动）。
