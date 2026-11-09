# A 类 14 个门控 hook 解锁方案（QQ 8.8.50 / versionCode 2324）

方法索引：`analysis/methods_8.8.50.txt`（格式 `Lcls;->name(args)ret`）
DexKit 特征串：`analysis/dexkit_targets_scan.{json,md}` + `apks/dex/classes*.dex` 直接字符串核验

## 结论表

| 功能 | 结论 | 命中签名（8.8.50 实证） | 改动 |
| --- | --- | --- | --- |
| 内部浏览器菜单 | 可放宽 | `Lcom/tencent/widget/PopupMenuDialog;->conversationPlusBuild(Landroid/app/Activity;Ljava/util/List;Lcom/tencent/widget/PopupMenuDialog$OnClickActionListener;Lcom/tencent/widget/PopupMenuDialog$OnDismissListener;)Lcom/tencent/widget/PopupMenuDialog;`<br>`Lcom/tencent/widget/PopupMenuDialog$MenuItem;-><init>(ILjava/lang/String;Ljava/lang/String;I)V`<br>DexKit `RecentPopup_onClickAction`：特征串 `jiahao.fukuan.click` 命中（classes8.dex） | `isAvailable = QAppUtils.isQQnt() \|\| requireMinQQVersion(QQVersion.QQ_8_8_50)`；initOnce 首行 `Log.d` 记录三条命中。initOnce 无 `com.tencent.qqnt.*` 路径，NT 分支不受影响 |
| 打开资料卡 | 可放宽 | 同「内部浏览器菜单」（仅 menuItemId=414414 不同，走 `OpenProfileCard.onClick`） | 同上 |
| 收藏表情包排序 | 需兜底 | 主：`Lcom/tencent/mobileqq/emosm/api/impl/FavroamingDBManagerServiceImpl;->getEmoticonDataList()Ljava/util/List;` ✅<br>辅：DexKit `AIOPicElementType` ❌（特征串 `PicElement(origPath=` 不命中） | 门限放宽为 `isQQnt() \|\| QQ_8_8_50`；`DexKit.requireClassFromCache(AIOPicElementType)` 整段包进 `runCatching`，失败仅跳过「使用次数/上次使用时间」打点，排序主功能照常 |
| 显示设置禁言的管理 | 可放宽 | `Lcom/tencent/mobileqq/troop/utils/TroopGagMgr;->a(IJJJLjava/util/ArrayList;)V` ✅（8.8.50 < 9.0.73，天然走 else 非 NT 分支）<br>DexKit：`Hd_GagInfoDisclosure_Method`/`CMessageRecordFactory`/`NContactUtils_getBuddyName`/`NContactUtils_getDiscussionMemberShowName` 全 OK | `isQQnt() \|\| QQ_8_8_50`；`NtGrayTipHelper` 等为 QAuxiliary 自身 bridge，非 `com.tencent.qqnt.*`，无需改分支结构 |
| 查找共同群 | 可放宽 | 同「内部浏览器菜单」（menuItemId=515515，走 `CheckCommonGroup.onClick`） | 同上 |
| 显示空间动态 | 可放宽 | `Lcom/tencent/qzonehub/api/impl/QZoneApiProxyImpl;->needShowQzoneFrame(Landroid/content/Context;Lmqq/app/AppRuntime;)Z` ✅ | `requireMinQQVersion(QQ_8_8_50)`（原 QQ_9_2_30） |
| 随机气泡 | 可放宽 | `Lcom/tencent/mobileqq/app/SVIPHandler;->getSubBubbleId()I` ✅ | `QQ_8_8_50`（原 QQ_9_2_30） |
| 强制显示资料卡内所有设置 | 可放宽 | `Lcom/tencent/mobileqq/profilesetting/api/impl/ProfileSettingApiImpl;->getProfileDisplaySettingStateFromCard(ILcom/tencent/mobileqq/data/Card;Lcom/tencent/common/app/AppInterface;)I` ✅ | `QQ_8_8_50`（原 QQ_9_1_70） |
| 快捷回拍 | 可放宽 | DexKit `Hd_QuickReplayPaiYiPai_Method` OK：`HighlightClickableSpan` + `click handler is null. actionType=`（classes10.dex / classes23.dex） | `QQ_8_8_50`（原 QQ_9_1_35） |
| 使用 Telegram Stickers 表情包集 | 需兜底 | 命中：`FavoriteEmoticonInfo;->getDrawable(Landroid/content/Context;F)Landroid/graphics/drawable/Drawable;`、`getZoomDrawable(Landroid/content/Context;FII)Landroid/graphics/drawable/Drawable;`、`EmoticonTabAdapter;->generateTabUrl(Ljava/lang/String;Z)Ljava/net/URL;`、`EmotionPanelViewPagerAdapter;->handleIPSite(Lcom/tencent/mobileqq/data/EmoticonPackage;Lcom/tencent/mobileqq/emoticonview/BaseEmotionAdapter;Ljava/util/List;)V`、`EmotionPanelInfo;-><init>(IILcom/tencent/mobileqq/data/EmoticonPackage;)V`<br>缺失：`EmotionPanelViewPagerAdapter.getEmotionPanelData` / `queryEmoticonsByPackageIdFromDB` 均无 | `QQ_8_8_50`（原 QQ_9_0_8）+ 面板数据注入段整段 `runCatching`，失败只丢「面板数据注入」，图标/点击/预览主路径可用 |
| 自定义表情面板列数 | 不可修 | ❌ `Lcom/tencent/mobileqq/emoticonview/EmotionPanelViewPagerAdapter;->getColumnNum(II)I` 不存在；全库仅 `EmoticonLinearLayout$EmoticonAdapter;->getColumnNum()I` 与 `SystemAndEmojiUniversalPanel;->getColumnNum()I`，签名与语义均不匹配<br>❌ 辅助字段 `FavoriteEmotionAdapter.isNewUISwitchOn` 在 23 个 dex 中无该字符串 | 不动代码 |
| 隐藏底栏标题 | 需兜底 | 方法存在但签名不同：`Lcom/tencent/mobileqq/activity/home/impl/TabFrameControllerImpl;->generateTabItem(IIIIIII)Landroid/view/View;`（**7 参**，上游按 11/12 参匹配 → 必然失配） | `QQ_8_8_50`（原 QQ_8_9_88）+ 去掉 `parameterCount(11/12)` 约束，只锁 `returnType(View)` + `name("generateTabItem")`（该类仅此一个重载） |
| 关闭群帖子功能 | 不可修 | ❌ `Lcom/tencent/mobileqq/data/troop/TroopInfo;->getTroopGuildId()J` 不存在。TroopInfo 全类 105 个方法逐一核对，无任何 `guild` 相关成员（`getFansTroopStarId` 是唯一 `get*Id`） | 不动代码 |
| 强制手机模式 | 需兜底 | ❌ 上游 8.8.50 走 `AppSetting.f()I`，但本版本 `e()`/`f()` 已改为返回 `String`；无参 Int 方法只剩 `Lcom/tencent/common/config/AppSetting;->d()I` ✅<br>静态字段 `e`/`f` 无法从 dex 静态确认为 int appId | `QQ_8_8_50`（原 QQ_8_9_15）+ 方法名候选链 `9.2.30→e / ≥8.9.15→f / else→d`；字段读取走候选链 `[首选, e, f, g, d]` 逐个 `runCatching`，全失败时记日志不替换（不崩 initOnce） |

## 统计

- 可放宽：8（内部浏览器菜单、打开资料卡、显示设置禁言的管理、查找共同群、显示空间动态、随机气泡、强制显示资料卡内所有设置、快捷回拍）
- 需兜底：4（收藏表情包排序、使用 Telegram Stickers、隐藏底栏标题、强制手机模式）
- 不可修：2（自定义表情面板列数、关闭群帖子功能）

## isQQnt 门 5 个的 NT 路径核查

| 功能 | initOnce 是否含 `com.tencent.qqnt.*` | 处理 |
| --- | --- | --- |
| 内部浏览器菜单 | 否（仅 `com.tencent.widget.PopupMenuDialog*` + DexKit） | 直接 `isQQnt() \|\| QQ_8_8_50` |
| 打开资料卡 | 否（同上） | 同上 |
| 收藏表情包排序 | 否（`com.tencent.mobileqq.emosm.*`） | 同上（另加兜底） |
| 显示设置禁言的管理 | 否（`TroopGagMgr` + 自身 bridge.ntapi） | 同上；8.8.50 天然走非 NT 的 `TroopGagMgr` 分支 |
| 查找共同群 | 否（同内部浏览器菜单） | 同上 |

## 改动文件（12 个，git diff --stat）

```
cc/ioctl/hook/chat/GagInfoDisclosure.kt              |  6 +++-
cc/ioctl/hook/experimental/ForcePhoneMode.kt         | 33 +++++++++++++++----
cc/ioctl/hook/friend/CheckCommonGroupMenu.kt         | 10 +++++-
cc/ioctl/hook/profile/OpenProfileCardMenu.kt         | 10 +++++-
cc/microblock/hook/DumpTelegramStickers.kt           | 12 ++++++-
cc/microblock/hook/FavEmoticonSortBy.kt              | 38 +++++++++++++---------
io/github/duzhaokun123/hook/InternalBrowserMenu.kt   | 11 ++++++-
io/github/nakixii/hook/ForceShowProfileSettings.kt   |  6 +++-
me/hd/hook/QuickReplayPaiYiPai.kt                    |  6 +++-
me/hd/hook/RandomBubble.kt                           |  5 ++-
me/hd/hook/simplify/main/ui/operation/log/ShowQzoneFrame.kt | 6 +++-
me/hd/hook/simplify/ui/misc/HideBottomTabTitle.kt    |  9 +++--
```

未触碰：`io/github/qauxv/hook/experimental/`、`core/`、`dsl/`、`fragment/`、`util/ClassAvailability.kt`，以及 C 类 9 个文件。

## 编译

```
pwsh -NoProfile -File D:\DSH-Builds\QAuxiliaryFix\tools\build.ps1 -Task ":app:compileDebugKotlin"
> Task :app:compileDebugKotlin
BUILD SUCCESSFUL in 1m 30s
101 actionable tasks: 4 executed, 97 up-to-date
```

`e:` 级错误 0 条；输出中剩余 `w:` 全部来自未改动文件（`NonNTMessageStyleNotification.kt`、`MiniAppDirectJump.kt`、`SimplifyPlusPanel.kt`、`ezxhelper/FieldUtils.kt` 等既存告警）。

首轮编译曾报 7 条 `ARGUMENT_TYPE_MISMATCH`（`io.github.qauxv.util.Log` 只有 `d(String)` / `d(String, Throwable)`，没有 `(tag, msg)` 重载），已把所有新增日志改成单参数 `Log.d("Tag: msg")` 后重编通过。

## 遗留风险

- `ForcePhoneMode`：静态字段 `e`/`f` 是否为 int appId 无法从 dex 静态确认，改用候选链 `[首选, e, f, g, d]` + `runCatching` 逐个试读；全失败时只记日志、不替换返回值，不会让 initOnce 崩溃。真机验证需看 `ForcePhoneMode:` 日志行。
- `DumpTelegramStickers`：8.8.50 丢失面板数据注入，Telegram 表情包会出现在 Tab 上但面板内容可能为空，取决于 `FavoriteEmoticonInfo` 路径是否足够。
- `FavEmoticonSortBy`：`AIOPicElementType` MISS 导致「按使用次数/上次使用」缺少打点来源，排序只在有历史记录时生效。

