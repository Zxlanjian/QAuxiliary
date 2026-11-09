# 8 个 DexKit 型门控 hook 解锁结论（QQ 8.8.50 / versionCode 2324）

方法索引：`analysis/methods_8.8.50.txt`；反编译：`analysis/jd_v2/`（jadx 8 类，2 类 Input class not found）。

## 结论表

| 功能 | 结论 | 命中签名（8.8.50 实证） | 改动 |
| --- | --- | --- | --- |
| 允许群名带表情 | 已可用（门不挡，无需改） | `Lcom/tencent/mobileqq/activity/editservice/EditTroopMemberNickService$EmojiFilter;->filter(Ljava/lang/CharSequence;IILandroid/text/Spanned;II)Ljava/lang/CharSequence;` | 不改。`requireMaxQQVersion(QQ_9_0_90)` 是**上限**门（`versionCode <= 7218`），8.8.50=2324 已满足；hook 的 `getDeclaredMethod("filter", CharSequence, Int, Int, Spanned, Int, Int)` 与签名逐参一致（注意此处全是引用类型 + `Int::class.java`，不存在原始类型比较坑） |
| 去除戳一戳时间限制 | 已可用（门不挡，无需改） | `Lcom/tencent/mobileqq/paiyipai/PaiYiPaiHandler;->a(Ljava/lang/String;)Z`（`private boolean`，方法体即 canSendReq：`pai_yi_pai_user_double_tap_timestamp_` + `j2 > 10000` 冷却判断） | 不改。门 `QQ_8_5_0`=1596 ≤ 2324；`hookBeforeIfEnabled` 置 `result = true` 与 `boolean` 返回匹配 |
| 隐藏一起听歌 | 已可用（门不挡，无需改） | `Lcom/tencent/mobileqq/listentogether/ui/BaseListenTogetherPanel;->a(Lcom/tencent/mobileqq/listentogether/ListenTogetherSession;)V`（方法体首行即 `onUIModuleNeedRefresh, checkSession is false`） | 不改。门 `QQ_8_5_0` ≤ 2324；void 方法 `result = null` 直接返回，面板不刷新 |
| 发送消息自动 Pangu | 不可修 | AIOTextElementCtor **解析不到**：`com.tencent.mobileqq.aio.msg.AIOMsgElement` 在 8.8.50 不存在（jadx：`Input class not found`），`com/tencent/mobileqq/aio` 在 23 个 dex 中 0 命中（`textElement` 8 处属其它包，被 `filter = strInClsName("com/tencent/mobileqq/aio/msg")` 排除） | 不改。8.8.50 无 NT aio 架构，`isQQnt` 门放开也无 hook 点 |
| 统计聊天发送消息数量 | 不可修 | `NQQSettingMe_onResume` 命中 `Lcom/tencent/mobileqq/activity/QQSettingMe;->b()V`（日志 `-->onResume!`，即 onResume 实现）；但注入点类 `com.tencent.mobileqq.activity.QQSettingMeView` 在 8.8.50 **不存在**（dex 里 3 处均为 `QQSettingMeViewModel` 子串），且 `com/tencent/qqnt` 0 命中 → `MsgService.sendMsg` 计数 hook 不可用 | 不改。8.8.50 是 V9 侧滑栏（`QQSettingMe(QBaseActivity, AppRuntime, ViewGroup)` + `QQSettingMeViewModel`）+ 无 NT 内核，旧样式注入与计数通道同时缺失 |
| 编辑重发文本消息 | 不可修 | AIO_InputRootInit_QQNT **解析不到**：`com.tencent.mobileqq.aio.input.simpleui.AIOInputSimpleUIVBDelegate` 不存在（jadx：`Input class not found`），`com/tencent/qqnt` 0 命中 → `OnMenuBuilder` 的 NT 菜单链路也不存在 | 不改 |
| 隐藏会员红名 | 已可用（门不挡，无需改） | `Lcom/tencent/mobileqq/utils/VipUtils;->a(Lcom/tencent/mobileqq/app/QQAppInterface;Ljava/lang/String;Z)I`（方法体含 `getUserStatus Friends is null`，返回 int）<br>`Lcom/tencent/mobileqq/activity/aio/core/FriendChatPie;->bm()V`（`protected void`，方法体含 `FriendChatPie updateUI_titleBarForTheme` 且调用 `VipUtils.a(this.d, this.ah.b, false)` 判 2/3 设红名） | 不改。门 `QQ_8_5_5`=1630 ≤ 2324；`result = -1` 与 int 返回匹配，`updating` 标志 hook 与 void 方法匹配 |
| 屏蔽广告弹窗(测试版) | 可放宽 + 兜底 | `Lcooperation/vip/qqbanner/manager/VasADBannerManager;->g()V`（`public void`，方法体 `start showVasADBanner` → 加载资源并 `SetSplash.a(this.b)` 展示）；Method1 的 `grow_half_layer_info` 特征串在 8.8.50 未命中 | `isAvailable`：`requireMinQQVersion(QQ_9_8_88)` → **`QQ_8_8_50`**；`Hd_DisableGrowHalfLayer_Method1` 改 `DexKit.loadMethodFromCache(...)?.let{...}` 判空，缺失只跳过「测试1」，Method2 照常 hook（否则 `requireMethodFromCache(Method1)` 抛 `NoSuchMethodException` 让整个 initOnce 失败） |

## 关键判定依据

- `requireMaxVersion` 实现：`hostInfo.versionCode <= versionCode`（`HostInfo.kt:110`）→ `QQ_9_0_90`=7218 对 2324 恒真，第 1 行不是被门挡住的 hook。
- `DexKit.loadMethodFromCache` 对 `<init>` 直接返回 null（`DexKit.kt:124`）→ 任何「构造器 target」在 8.8.50 都不可能被 `requireMethodFromCache` 取到；Pangu 的 AIOTextElementCtor 即使特征串在也走不通。
- `DexDeobfStep.step()` 捕获异常返回 false（`step/DexDeobfStep.java:52`），`InjectDelayableHooks` 只 `Log.e` → targets 数组里保留 MISS 的 Method1 不会阻断其它 target 的准备步骤，但仍必须把 initOnce 里的 `requireMethodFromCache` 换成可空版本。
- 反编译证据：`com.tencent.mobileqq.aio.msg.AIOMsgElement`、`com.tencent.mobileqq.aio.input.simpleui.AIOInputSimpleUIVBDelegate` 均 `Input class not found`；`com/tencent/qqnt`、`Lcom/tencent/mobileqq/aio` 在 `apks/dex/classes*.dex` 中 0 命中。

## 改动文件

```
me/hd/hook/DisableGrowHalfLayer.kt   | 门 8_9_88 → 8_8_50；Method1 可选化
```

未触碰：`io/github/qauxv/hook/experimental/`、`core/`、`dsl/`、`fragment/`、`util/ClassAvailability.kt`。

## 统计

- 已可用（门本来就不挡，签名核对通过）：4（允许群名带表情、去除戳一戳时间限制、隐藏一起听歌、隐藏会员红名）
- 可放宽 + 兜底：1（屏蔽广告弹窗(测试版)）
- 不可修：3（发送消息自动 Pangu、统计聊天发送消息数量、编辑重发文本消息）

## 编译

`pwsh -NoProfile -File D:\DSH-Builds\QAuxiliaryFix\tools\build.ps1 -Task ":app:compileDebugKotlin"`

```
BUILD SUCCESSFUL in 47s
101 actionable tasks: 4 executed, 97 up-to-date
```

前两次运行分别报 `kspDebugKotlin NoSuchFileException: app\build\generated\ksp\debug\kotlin\io\github\qauxv\gen` 与 Kotlin `Internal compiler error`（FIR 堆栈），均为同目录并发构建（另一队友同时跑 gradle，机器上同时存在 4 个 java 进程）导致的抖动；间隔后重跑即通过，与本次改动无关。
