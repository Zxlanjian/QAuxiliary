# C 类深挖报告：9 个高价值门控 hook 在 QQ 8.8.50 (2324) 上的真实可行性

判定依据全部来自本机实测：
- 方法索引 `methods_8.8.50.txt`（1,122,776 条，23 个 dex 全量）
- 原始 dex 二进制字符串检索（`apks/dex/*.dex`，`rg -a`，MUTF-8 常量池）
- jadx 反编译实据（`analysis/jd_drawer`、`jd_todo`、`jd_ctrl`、`jd_base`、`jd_poke`）

**关键判定口径**：Q1 问的不是「hook 目标类在不在」，而是「用户看到的那东西在不在」。因此
`com.tencent.qqnt.*` 全系 0 命中 **不等于**功能不存在 —— 8.8.50 是老架构（`com.tencent.mobileqq.*`），
同一功能换了实现类。下表逐个给出「功能本体在不在」+「老架构的等价 hook 点」。

---

## 一、结论总表

| 功能 | 本版本是否存在（证据） | 可 hook 点（类.方法签名） | 结论 | 建议改动 |
| --- | --- | --- | --- | --- |
| 屏蔽链接信息 | **不存在**：`qqnt.kernel.nativeinterface.LinkInfo` 0 命中；全 dex `LinkInfo` 仅命中 ad/qqstory/device/kandian 等无关类；8.8.50 链接消息走 `structmsg.AbsShareMsg` 一体卡片，无「消息下方信息卡」这一层 | — | **真做不到** | 保留 `classExists("...LinkInfo")` 门 |
| 显示被折叠的消息 | **不存在**：`FoldingInfo` 0 命中、`beatType` 0 命中；全 dex「折叠」仅 4 处且全是「折叠更多功能面板」；8.8.50 无消息折叠机制（折叠是 NT 化后引入） | — | **真做不到** | 保留 `classExists("...FoldingInfo")` 门 |
| 屏蔽大号Emoji | **不存在**：`MsgElement`/`MsgRecord` 0 命中；`超级表情`/`大表情` 在 23 个 dex 中 **0 引用**（仅资源字符串残留）；`BigEmoji` 全 dex 仅 1 处非功能常量。8.8.50 只有普通商城表情 `MarketFaceItemBuilder`，无超级表情渲染路径 | — | **真做不到** | 保留 `requireMinQQVersion(8_9_88)` 门 |
| 屏蔽聊天右滑 | **存在**：`activity.aio.drawer.TroopAppShortcutDrawer`、`IntimateInfoChatDrawer`、`TroopMultiCardDrawer`、`GameMsgAppDrawer`、`BaseChatDrawer` 全在索引中；容器为 `com.tencent.mobileqq.widget.DrawerFrame`；字符串「向右滑动查看」命中 | `com.tencent.mobileqq.widget.DrawerFrame.setDrawerEnabled(Z)V` | **能修** | 已改：8.8.50 分支 hook `setDrawerEnabled` → `args[0]=false` |
| 移除消息表态 | **不存在**：`EmoReply` 全索引仅 1 处且属 `guild.message`（QQ 频道），与消息菜单无关；全 dex `表情回应` 0 命中、`emo_reply` 0 命中。表态是 9.0.8 才有的消息级表情回应 | — | **真做不到** | 保留 `requireMinQQVersion(9_0_8)` 门 |
| 隐藏群广场 | **不存在**：`troop_square`/`TroopSquare` 在 23 个 dex **0 命中**；`群广场` 0 命中；全 dex「广场」仅扩列广场。群聊标题栏在 8.8.50 由 `activity.aio.AioTitleLayout` 承担，无 `AIOTitleVB`/`troop_square_vb` 这套 VB 机制 | — | **真做不到** | 保留 `requireMinQQVersion(9_0_80)` 门 |
| 隐藏群待办 | **存在**：`activity.aio.troop.trooptodo.TroopToDoTipsBar` / `TroopToDoController` / `TroopToDoTipsBarManage` 全在索引（101 处 `TroopToDo` 命中）；字符串「群待办」「设置群待办」「关闭待办」命中 | `com.tencent.mobileqq.activity.aio.troop.trooptodo.TroopToDoController.a(Z)V`（= showTroopToDoBanner） | **能修** | 已改：8.8.50 分支 hook `a(Z)V` → `result=null`，并对 `TroopToDoTipsBar` 构造后置根 View `GONE` |
| 禁用轻互动 | **存在（换形态）**：`LIAConfigManager`/`lightinteraction` 0 命中（NT 的输入框上方快捷栏没有）；但「轻互动」字符串在 `classes8.dex` 命中，且与 `MessageForPokeEmo` 同处 —— 8.8.50 的轻互动 = PokeEmo 消息，气泡构建器 `activity.aio.item.PokeEmoItemBuilder` 在索引中 | `com.tencent.mobileqq.activity.aio.item.PokeEmoItemBuilder.a(ChatMessage, BaseBubbleBuilder$ViewHolder, View, BaseChatItemLayout, OnLongClickAndTouchListener)Landroid/view/View;` | **能修** | 已改：8.8.50 分支 hook 该 5 参 `a(...)` → 返回的 View 置 `GONE` |
| 移除评论广告 | **不存在**：`QFSCommentBlock` 0 命中、`cmt_block_type` 0 命中、`cmt_block` 0 命中。8.8.50 有 qcircle 评论区（`QCircleFeedCommentAdapter`），但评论数据模型里没有广告块类型，二楼广告是 8.9.88 才引入的 | — | **真做不到** | 保留 `requireMinQQVersion(8_9_88)` 门 |

**汇总：3 能修 / 6 真做不到。**

---

## 二、能修的 3 个：改动细节

### 1. 屏蔽聊天右滑 —— `me/hd/hook/simplify/chat/other/DisableSwipeRight.kt`

原门：`requireMinQQVersion(QQ_8_9_88)` → 改为 `requireMinQQVersion(QQ_8_8_50)`。

8.8.50 的右滑抽屉不是 `com.tencent.aio.frame.drawer.DrawerFrameViewGroup`（0 命中），而是
`com.tencent.mobileqq.widget.DrawerFrame`：

```
DrawerFrame-><init>(Landroid/content/Context;Landroid/view/ViewGroup;)V
DrawerFrame->setDrawerEnabled(Z)V      <-- hook 点
DrawerFrame->setDrawerCallbacks(Lcom/tencent/mobileqq/widget/DrawerFrame$IDrawerCallbacks;)V
DrawerFrame->onInterceptTouchEvent(Landroid/view/MotionEvent;)Z
```

`setDrawerEnabled(false)` 之后抽屉不再响应侧滑，群应用/亲密关系面板都滑不出来。
分支判据用 `classExists("com.tencent.aio.frame.drawer.DrawerFrameViewGroup")`，
新架构仍走原 `isPrivate && parameters(Int::class.java)` 的 scrollX 归零逻辑。

### 2. 隐藏群待办 —— `me/hd/hook/HideTroopToDo.kt`

原门：`requireRangeQQVersion(QQ_8_9_88, QQ_9_0_68)` → 改为 `requireRangeQQVersion(QQ_8_8_50, QQ_9_0_68)`。

8.8.50 的老架构（jadx 实据 `jd_ctrl/.../TroopToDoController.java`）：

```java
public class TroopToDoController implements TroopToDoTipAbility {
    private View f41338a;  private View b;  private ViewGroup f41339c;
    public void a(boolean z) {          // showTroopToDoBanner
        f();
        if (this.d.z().d().d().a()) this.f41338a.setVisibility(0);
        this.f41339c.setVisibility(0);   // 待办条根 ViewGroup 显示
        ...
    }
    public void a() { ... this.f41339c.setVisibility(8); }   // dismiss
    public View b() { f(); return this.f41339c; }
}
```

hook `a(Z)V` before 直接 `param.result = null` → 整个显示流程被吞，待办条永不出现。
双保险：`TroopToDoTipsBar` 构造 `(AIOContext, QQAppInterface, TroopToDoController)` after
把唯一的 `View` 类型字段（`f41340a`）置 `GONE`。

注意 8.8.50 构造签名与 9.x 完全不同（`params[0]=AIOContext` 而非 `Context`，`params[2]=TroopToDoController`
而非 `TroopToDoReporter`），原代码的 `constructors.single { params[0]==Context }` 在 8.8.50 必然抛
`NoSuchElementException`，这就是它被门挡住的真实原因。

### 3. 禁用轻互动 —— `cc/ioctl/hook/chat/DisableLightInteraction.kt`

原门：`requireMinQQVersion(QQ_8_9_78)` → 改为 `requireMinQQVersion(QQ_8_8_50)`。

DexKit target 原本找的是 `Lcom/tencent/mobileqq/aio/bottombar/c2c/LiteActionBottomBar;`
（特征串 `em_bas_shortcut_bar_above_c2c_input_box`），8.8.50 无此类。
8.8.50 的轻互动是消息级 `MessageForPokeEmo`，渲染入口：

```
PokeEmoItemBuilder->a(Lcom/tencent/mobileqq/data/ChatMessage;Lcom/tencent/mobileqq/activity/aio/BaseBubbleBuilder$ViewHolder;Landroid/view/View;Lcom/tencent/mobileqq/activity/aio/BaseChatItemLayout;Lcom/tencent/mobileqq/activity/aio/OnLongClickAndTouchListener;)Landroid/view/View;
```

hook after → `(param.result as? View)?.visibility = GONE`，气泡不占位不显示。
分支判据：`!classExists("com.tencent.qqnt.biz.lightbusiness.lightinteraction.LIAConfigManager")`。
9.x 的 `LIAConfigManager` 逻辑与 9.0.8 的 `DexKit` 逻辑原样保留。

---

## 三、真做不到的 6 个：0 命中证据

### 屏蔽链接信息
```
$ rg -c "qqnt.kernel.nativeinterface.LinkInfo" methods_8.8.50.txt
0
$ rg -o "^L[^;]*;" methods_8.8.50.txt | sort -u | rg -i "LinkInfo"
com/tencent/ad/tangram/protocol/qq_ad_get$...$ProductLinkInfo
com/tencent/biz/qqstory/model/item/VideoLinkInfo
com/tencent/device/datadef/ProductNetLinkInfo
com/tencent/mobileqq/kandian/repo/feeds/entity/OutsideLinkInfo
tencent/gdt/qq_ad_get$...$ProductLinkInfo
trpc/qqlive/common/LinkMicProto$TmemLinkInfo
```
全是广告/视频/设备/看点/直播的 `LinkInfo`，与消息链接卡片无关。
8.8.50 的链接消息是 `structmsg.AbsShareMsg` / `AbsStructMsgItem` 渲染的一体卡片
（标题+摘要+缩略图在同一 View 树里），**没有**「消息下方附加一张信息卡」这个独立对象，
所以没有可屏蔽的目标。`MsgExtKt.T/S`、`aio.msg.TextMsgContent` 也全部 0 命中。

### 显示被折叠的消息
```
$ rg -c "FoldingInfo"  methods_8.8.50.txt   -> 0
$ rg -a -c "beatType"  apks/dex/*.dex       -> 0（全 23 个 dex）
$ rg -n "折叠" qq_strings.txt
28127: 折叠更多功能面板
28128: 折叠更多功能面板，拉起输入框
35868: 分组已折叠
44131: 折叠上传照片等功能
```
「折叠」在 8.8.50 只指功能面板/分组/相册，没有消息折叠（色图折叠）。
`FoldingInfo.beatType` 这套是 NT 内核的折叠协议字段，老架构不存在。

### 屏蔽大号Emoji
```
$ rg -c "MsgElement"  methods_8.8.50.txt  -> 0
$ rg -c "MsgRecord"   methods_8.8.50.txt  -> 0
$ rg -a -c "超级表情" apks/dex/*.dex      -> 0
$ rg -a -c "大表情"   apks/dex/*.dex      -> 0
$ rg -a -c "big_emoji|BigEmoji" apks/dex/*.dex
classes9.dex:1        （非功能常量，无渲染路径）
```
`qq_strings.txt` 里确有 `String #44943 : 超级表情`、`String #39664 : 大表情:`，
但 **23 个 dex 里 0 处引用** —— 属于共用资源包残留/服务端下发文案。
8.8.50 的表情渲染是 `MarketFaceItemBuilder`（商城表情）与 `FaceInfo`（系统表情），
`faceType==3` 这套超级表情判定字段在 8.8.50 不存在。

### 移除消息表态
```
$ rg -n "EmoReply" methods_8.8.50.txt
553402:Lcom/tencent/mobileqq/guild/message/api/impl/GuildUniteGrayTipUtilApiImpl;->isGrayTipsEmoReply(...)Z
$ rg -a -c "表情回应|emo_reply" apks/dex/*.dex  -> 0
```
唯一命中是 QQ 频道的灰条判断，与聊天消息菜单无关。消息级「表情回应/表态」是 9.0.8 引入，
8.8.50 的长按菜单只有 `QQCustomMenuItem` 的转发/回复/撤回/多选等常规项。

### 隐藏群广场
```
$ rg -a -c "troop_square|TroopSquare" apks/dex/*.dex  -> 0
$ rg -a -c "群广场" apks/dex/*.dex                    -> 0
$ rg -n "广场" qq_strings.txt
35583: 广场                       （首页广场）
37116: 首页「广场」样式设置
38981: 清空扩列宣言后你将不在扩列广场中出现...
41223: 关闭后，你将不会在扩列广场被他人发现
```
全是首页/扩列广场。8.8.50 群聊标题栏由 `com.tencent.mobileqq.activity.aio.AioTitleLayout` 实现，
没有 `aio.title.AIOTitleVB`（0 命中），也就没有 `troop_square_vb` 这个 VB 关键字可拦。

### 移除评论广告
```
$ rg -a -c "QFSCommentBlock|cmt_block_type|cmt_block" apks/dex/*.dex  -> 0
```
8.8.50 的 qcircle 评论区确实存在（`com.tencent.biz.qqcircle.adapter.QCircleFeedCommentAdapter`
有 `onBindViewHolder(RecyclerView$ViewHolder, int)`），但评论数据模型里没有 `cmt_block_type`
这个广告块标记，也没有 `QFSCommentBlock` 这层 adapter。原 hook 靠
`mDataList -> comment -> cmt_block_type -> value == 1` 过滤，8.8.50 数据里根本没有这条链。

---

## 四、编译验证

```
pwsh -NoProfile -File D:\DSH-Builds\QAuxiliaryFix\tools\build.ps1 -Task ":app:compileDebugKotlin"
BUILD SUCCESSFUL in 46s
101 actionable tasks: 4 executed, 97 up-to-date
```

改动文件（仅这 3 个，未触碰 A 类 14 个 / `io.github.qauxv.hook.experimental` / `core` / `dsl` / `fragment`）：
- `source/app/src/main/java/me/hd/hook/simplify/chat/other/DisableSwipeRight.kt`
- `source/app/src/main/java/me/hd/hook/HideTroopToDo.kt`
- `source/app/src/main/java/cc/ioctl/hook/chat/DisableLightInteraction.kt`
