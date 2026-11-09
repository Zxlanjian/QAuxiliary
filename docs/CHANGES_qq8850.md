# QAuxiliary × QQ 8.8.50 修复记录

基线：`cinit/QAuxiliary` `main@4791fc9`（= 安装包 `1.6.1.r3054.4791fc9`）
目标客户端：QQ 8.8.50（versionCode 2324）
日期：2026-11-09

---

## 一、材料与方法

| 材料 | 处理 |
| --- | --- |
| `QAuxiliary.zip` 内 82 个 hook 清单 | 解到 `references/hooks/`，1 个主进程成功清单（277 条）+ 81 个失效清单 |
| `apks.zip` 内 `QQ_8.8.50.apk` | 解出 23 个 dex（201.7 MB）到 `apks/dex/` |
| 方法签名索引 | 自写 `tools/dex_index.py` 解析 dex `method_ids`，产出 `analysis/methods_8.8.50.txt`（1,122,776 条签名 / 182,355 个类） |
| 清单比对 | `tools/compare_hooks.py` → `analysis/hook_compare.txt` |
| 反编译 | jadx 1.5.1 单类反编译；aapt2 `dump strings`/`dump resources` |

**关键结论：282 条导出签名里只有 19 条不在 8.8.50 方法表中**（12 条是 android/java.net 系统类，2 条 AppCenter SDK，5 条真失效）。所以「hook 点消失」不是主因。

---

## 二、目标 1：修复失效功能

完整根因表、逐条证据、改前改后与回滚命令见 [`analysis/hook_fix_plan.md`](../analysis/hook_fix_plan.md)（398 行）。

### 2.1 根因分布（81 个失效 hook / 76 个 unique 类）

| 根因 | 数量 | 说明 |
| --- | --- | --- |
| 版本门主动排除 | 59 个 `isAvailable = false` | 43 项 `requireMinQQVersion(≥8.9.0)` + 8 项 `isQQnt()` + 其它 |
| 目标类在 8.8.50 不存在 | 28 个 `ClassNotFoundException` | `com.tencent.qqnt.*`、`com.tencent.mobileqq.aio.*`、`com.tencent.android.androidbypass.*` 在 23 个 dex 中 0 命中 |
| dexkit `DexTarget` 缓存缺项 | 13 个 | 需在有 8.8.50 实机的环境跑一次 dex 解混淆预处理 |
| 真代码级 bug | **3 个** | 已修 |

**8.8.50 是 NT 化之前的旧架构客户端**，大量 hook 面向 9.x 的 NT 包，不是「写错了」而是「版本不存在」。

### 2.2 已修复（6 处源码改动）

| # | 文件 | 改动 | 依据 |
| --- | --- | --- | --- |
| 1 | `com/xiaoniu/hook/DisableDocOnlinePreview.kt` | `parse([B)IConfigData` 不存在 → fallback `onParsed(QConfItem[])` + `migrateOldOrDefaultContent(I)` | jadx 确认该类 8.8.50 改为 `extends IQConfigProcessor<TroopDocsPreviewConfigBean>` |
| 2 | `io/github/moonleeeaf/hook/HideChatDatabaseCorruptDialogHook.java` | `DBFixDialogUI.v(int)` 被 r8 改名 → fallback `a(int)` | jadx 确认 `a(int)` 内部即 `showDialog("checked corrupt")` |
| 3 | `cc/ioctl/hook/troop/TroopFileSaveLasting.kt` | `TroopFileShowAdapter` 路径迁移 → 候选链 | 8.8.50 位于 `troop/filemanager/data/`，无 `$1` 内部类 |
| 4 | `me/hd/hook/simplify/main/ui/contact/ChangeFriendsVip.kt` | 版本门 `QQ_8_9_88` → `QQ_8_8_50` | `Friends;->isServiceEnabled(EVIPSPEC)Z` 实存 |
| 5 | `me/hd/hook/simplify/main/ui/operation/log/ShowServiceRemove.kt` | 版本门 `QQ_8_9_88` → `QQ_8_8_50` | `LebaPluginInfo;->getAllowModify()Z` 实存，且 `<9.0.35` 分支正好走它 |
| 6 | `cc/microblock/hook/ForceEnableMultiForward.kt` | `isQQnt()` → `isQQnt() \|\| requireMinQQVersion(QQ_8_8_50)` | `ForwardRecentActivity;->initEntryHeaderView()V` 实存（8.8.50 未 NT 化） |

版本判定语义证据：`QQVersion.java:65` `QQ_8_8_50 = 2324`；`HostInfo.kt:106-108` 用 `versionCode >= versionCode` 闭区间比较，且要求 `hostSpecies == QQ`，所以 `||` 短路对任何原本成立的版本零影响。

### 2.3 判定为不可修（有证据）

| hook | 结论 |
| --- | --- |
| `HandleClickGroupBotMsgBtnSend` / `HandleClickPrivateBotRecMsgSend` | `androidbypass` / `aio` 整包 0 命中，QQ 9.0+ 组件 |
| `DisableProfileCardZplanCover` | `ProfileCardApiImpl` 有 32 个方法但无 Zplan 相关；8.8.50 资料卡尚无该功能 |
| `AioChatPieClipPasteHook` | `qqnt.qbasealbum` 0 命中，且依赖 NT aio 布局（`send_btn` 兄弟节点） |
| `HideQZoneAD` / `HideQZoneGuidePublishMoodBanner` | `com/qzone/reborn/` 整包 0 命中 |

「类在但方法不在」的 5 个：`TroopInfo.getTroopGuildId`、`PopupMenuDialog.conversationPlusBuild`、`DialogUtil.createCountdownDialog`、`EditTroopMemberNickService.c(ViewGroup,EditText,ViewGroup)`、`QCirclePluginUtil.canJumpToQQProfile(List)`。

### 2.4 未落地但建议

`io/github/qauxv/core/InjectDelayableHooks.java:247` 的守卫只有 `h.isEnabled() && h.isTargetProcess()`，没有 `isAvailable`；而 `BaseFunctionHook.kt:106-114` 的 `isEnabled` 只在「配置项未写过」时用 `isAvailable` 作默认值。结果是 59 个版本门不满足的 hook 每次启动仍会执行 `initOnce` 并报错（污染错误面板、浪费 dexkit 查找）。
建议在 `initialize()` 前加 `isAvailable` 门，但需实机确认 277 条成功清单不掉量，故本次未改。

---

## 三、目标 2：分享卡片「低版本不支持」的根因与解法

完整链路、smali 证据、风险与验证步骤见 [`docs/share_card_analysis.md`](share_card_analysis.md)（459 行）。

### 3.1 根因

不是 appid 白名单，不是 layoutId 缺失，不是 `meta.news` 解析失败，而是 **structmsg 二进制体的 `messageVersion` 与服务端下发的字段布局不匹配**：

```
msgData → StructMsgFactory.a([BI)           解压 + SAX
        → AbsStructMsgItem.readExternal     按 messageVersion 分段读字段(16/18/19/21/22/23/24/25/27/29)
        → 高版本布局 → 读错位
        → throw IOException("structmsg_version_error")
        → StructMsgForGeneralShare.readExternal catch → mMsgException = true
        → getView: iget mMsgException → 非零 → getVersionExceptionView()
                    → 灰字 #777777 气泡「当前版本导致消息展示异常，请升级QQ版本」
```

### 3.2 一个必须说清的点

用户看到的那句「暂不支持该消息类型，请升级至最新版本QQ查看」对应 `0x7f120d19`（`fs_message_unsupported_type`）与 `0x7f1225f8`，但这两个资源在 **全部 23 个 dex 里零代码引用**（`const` 加载 0 命中、`sget` R 字段 0 命中、全 dex 搜中文 0 命中；同脚本对已知 id `0x7f120619` 得 464 命中，扫描器已自检）。
所以 8.8.50 客户端不会主动渲染这段文字，它来自客户端之外的路径（服务端按版本下发提示），标「未验证」。

### 3.3 解法（已实现为实验性功能）

`app/src/main/java/io/github/qauxv/hook/experimental/ForceShowHighVersionStructMsg.kt`

- A 点：`StructMsgForGeneralShare.readExternal(ObjectInput)V` → `hookAfter` 清 `mMsgException`
- B 点：`StructMsgForGeneralShare.getView(Context,View,OnLongClickAndTouchListener,Bundle)View` → `hookBefore` 再清一次（覆盖 DB/缓存反序列化对象）
- 字段定位：先沿继承链找 `mMsgException`（实际声明在 `AbsShareMsg`），失败则按 boolean 字段名含 `exception`/`error` 兜底，再失败就终止初始化并 `traceError`，不静默成功
- 两处目标签名均已在 `methods_8.8.50.txt` 中确认存在

风险：清掉标记后字段可能残缺（卡片缺 title/preview 但布局正常）；若该 serviceID 的 layout 在 8.8.50 不存在，仍会 fallback 到 `getInCompatibleView` 黑字气泡。不建议 hook `getVersionExceptionView` 返 null（`getView` 会对返回值 `setId/setTag`，直接 NPE）。

---

## 四、目标 3：PatchedTextView 修复并入模块

`app/src/main/java/io/github/qauxv/hook/experimental/FixPatchedTextViewMeasure.kt`
UI 位置：`FunctionEntryRouter.Locations.Auxiliary.EXPERIMENTAL_CATEGORY`（辅助功能 → 实验性功能）

原 `build.yml` 里是一个独立 LSPosed 模块（libxposed API 102，`MainModule.java`），逻辑是替换
`com.tencent.mobileqq.widget.PatchedTextView.onMeasure`：先把 `Spanned` 文本固化成 `SpannedString`，
再 `invokeSpecial` 调 `TextView.onMeasure`，异常时 `setText(text)` 后重试。

QQ 8.8.50 的原实现（jadx 反编译，`classes14.dex`）：

```java
protected void onMeasure(int i, int i2) {
    try { super.onMeasure(i, i2); }
    catch (Exception e) {
        if (QLog.isColorLevel()) QLog.e(TAG, 2, "Error : Exception thrown in PatchedTextView.onMeasure", e);
        setText(getText().toString());   // ← 富文本降级成纯文本，span 全丢，多行塌陷
        super.onMeasure(i, i2);
    }
}
```

合并后的实现差异：

| 点 | 独立模块 | 模块内实现 |
| --- | --- | --- |
| 替换方式 | `intercept` + `getInvoker().invokeSpecial` | `hookReplace` + `Reflex.invokeNonVirtual(TextView.onMeasure)` |
| 异常回退 | `setText(text)` 重试 | 同，但外层 `hookReplace` 会兜住第二次异常，不冒泡到 QQ |
| 日志 | 直接反射 `QLog.e` | `Log.e` 必写，`QLog` 反射包 `runCatching` 失败不影响主流程 |
| 启用方式 | 独立 APK + LSPosed 作用域 | 模块内开关（默认关） |

---

## 五、目标 4：同步上游 + 构建 + 开源

- 上游同步：`git clone` `main@4791fc9`（= 最新 CI 包同 commit），子模块全部对齐到父仓库 pin 的 commit。
- 构建：`tools/build.ps1`。本机 `:app:compileDebugKotlin` 通过（含本次全部改动）。
  完整 APK 需要 NDK 29.0.13599879 + CMake 3.31.0（LSPlant 要求），本机原为 NDK 28.2 / CMake 3.22。
- 依赖坑：`com.github.plattysoft:Leonids:1746429`（WeatherView 传递依赖）只在 `maven.tmpfs.dev` 发布且该仓库 502，
  jitpack/mavenCentral/阿里云均无 → 已在 `app/build.gradle.kts` 排除，仅影响节日天气粒子动画。
- 详见 [`BUILD.md`](../BUILD.md)。

---

## 六、验证状态

| 项 | 状态 |
| --- | --- |
| dex 方法索引（1,122,776 条） | ✅ 完成，与 apk 内 23 个 dex 一一对应 |
| 82 个清单比对 | ✅ 完成（`analysis/hook_compare.txt`） |
| 6 处源码修复 | ✅ 已改，`:app:compileDebugKotlin` 通过 |
| 2 个新实验性 hook | ✅ 已加，编译通过；hook 目标签名均在 8.8.50 方法表中确认 |
| 实机验证 | ❌ 无 8.8.50 设备，全部结论基于静态证据 |
| 完整 APK | ⏳ 依赖 NDK 29 / CMake 3.31 |

---

## 七、第二轮：基于实机 LSPosed 日志的修正（2026-11-09 晚）

设备：QQ 8.8.50(2324)，QAuxiliary 1.6.1.r3054.4791fc9，LSPosed 作用域正常。
日志：`LSPosed_20261109_131224.zip`（`full.log` 330,491 行 + 82 份带运行时状态的 hook 清单）。

### 7.1 「除去不支持该版本的功能」

第一轮只做了分析，没有让 `isAvailable` 真正生效。实机清单显示：59 个 hook 的
`isAvailable: false` 但 `isEnabled: true`，每次启动照样执行 `initOnce` 并抛异常。
根因两处：

- `BaseFunctionHook.isEnabled` 里 `isAvailable` 只作为**默认值**参与（`mDefaultEnabled && isAvailable`），
  一旦配置写过 `true` 就再也不看它；
- `InjectDelayableHooks` / `HookInstaller` 的 6 处注入守卫只判 `isEnabled() && isTargetProcess()`。

改动：

| 文件 | 改动 |
| --- | --- |
| `core/HookInstaller.java` | 新增 `isHookApplicable(IDynamicHook)`（内部 try/catch `isAvailable`），`allowEarlyInit` 加判断 |
| `core/InjectDelayableHooks.java` | 5 处守卫加 `HookInstaller.isHookApplicable(h)`（86 / 108 / 214 / 247 / 266 行） |
| `dsl/FunctionEntryRouter.kt` | 构建设置树时跳过 `isAvailable == false` 的 `IDynamicHook`，功能不再出现在界面上 |
| `fragment/SearchOverlaySubFragment.kt` | 搜索索引同步过滤，避免「搜得到但点不进」 |
| `util/ClassAvailability.kt`（新） | `classExists(name)` / `classExistsAnd(name){}`，用于按「目标类是否存在」设门 |

并给 7 个「类在本版本整包不存在」的 hook 补了类探测门（原来默认 `isAvailable = true`）：

| hook | 探测的类 |
| --- | --- |
| `BlockLinkInfo` | `com.tencent.qqnt.kernel.nativeinterface.LinkInfo` |
| `ForceUnfoldMessages` | `com.tencent.qqnt.kernel.nativeinterface.FoldingInfo` |
| `HideGroupAssistantBanner` | `com.tencent.mobileqq.activity.home.chats.troophelper.TroopHelperFragment` |
| `HideQZoneGuidePublishMoodBanner` | `com.qzone.component.banner.QZoneGuidePublishMoodBanner` |
| `SimplifyQQSettingMe2` | `com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3` |
| `OffRelationshipIdentification` | `com.tencent.mobileqq.profilecard.component.ProfileInStepComponent` |
| `AioChatPieClipPasteHook` | `com.tencent.qqnt.qbasealbum.WinkHomeActivity` |

另两个按「资源/方法是否存在」设门：`QrLoginAuthSecCheckMitigation`（`R$id.confirm_risk_login_btn`）、
`DisableProfileCardZplanCover`（`ProfileCardApiImpl.isProfileCardShowZplanCover`）。

效果：这些功能不再出现在设置里，也不再在每次启动时初始化并报错。

### 7.2 禁止回复自动@（用户说的「去除@」）

`cc/ioctl/hook/ui/chat/ReplyNoAtHook.kt`，实机 `NoSuchElementException` 来自 `declaredMethods.single { }`。
根因是**两处 Kotlin 包装类比较写错**，不是 QQ 改签名：

- `method.returnType == Void::class.java` —— `java.lang.Void.class` ≠ `void.class`（`Void.TYPE`）
- `params[2] == Boolean::class.java` —— `java.lang.Boolean.class` ≠ `boolean.class`

两者恒 false，`single` 必然抛异常。改为 `Void.TYPE` / `Boolean.TYPE` 比较，并降级 `isStatic` 为
「static 优先、否则退回」，`param.args` 下标按签名动态求。命中签名：
`InputUIUtils;->a(AIOContext, BaseSessionInfo, Z)V`（`QQConfigTable.kt:80` 给 8.6.0+ 的类名）。

同类问题一并修：`HideEditTroopNick`（方法名 `c`→`a`）、`ChangeCircleJumpProfile`
（`returnType(Boolean::class.java)` 恒不等 + 方法名混淆，4 选 2 标推测）、
`RemoveDialogWaitTime`（`Int::class.java` 恒不等 + `createCountdownDialog` 已消失，只消异常不恢复功能）。

### 7.3 小程序分享转链接（接收）

`me/singleneuron/hook/decorator/MiniAppToStruckMsg.kt`，实机 `errors: 21` 全是
`ClassCastException: String cannot be cast to Class`。

`Reflex.invokeVirtual` 的参数布局是「参数值…, 参数类型…, 返回类型」，`arcg = length / 2`。
原来只传一个值 → `argc = 0` → 把 `structMsgJson` 当成返回类型强转成 `Class` 而炸。
改为 `invoke("fromAppXml", structMsgJson, String::class.java, Boolean::class.javaPrimitiveType!!)`，
对应 `ArkAppMessage.fromAppXml(Ljava/lang/String;)Z`。

### 7.4 「强制显示高版本分享卡片」为什么无效

**方向错了：那条消息不是 structmsg，是 Ark 消息。** `{"app":"com.tencent.tuwen.lua","view":"news","meta":{…}}`
对应 `MessageForArkApp`，走 `ArkAppItemBubbleBuilder`；而 `mMsgException` 只在**二进制
`readExternal`** 路径被置 true，8.8.50 的 `StructMsgFactory` 走 XML+SAX，根本不经过 `readExternal`，
所以那个标记恒为构造时的 false —— hook 挂上了也只是空操作。

三条证据：

1. `AbsShareMsg` 三处 `mMsgException` 写入（dex13 偏移 1583222 / 1583295 / 1583791）全是构造函数初始化 false；
2. 唯一置 true 的是 `StructMsgForGeneralShare.readExternal` 的 catch（1941014）；
3. `getInCompatibleView` 零调用者，是死代码。

同时修正了该 hook 的字段定位：原实现「找不到 `mMsgException` 就取继承链上唯一的 boolean 字段」有风险
（该链上有 `mHasSource` / `mHasAdSource` 等多个 boolean），改为只按名精确取，取不到就报错，
并在 description 里写明**只对二进制 structmsg 生效**。

### 7.5 Ark 消息的正确解法（新增功能）

8.8.50 对这条 Ark 消息走的是正常 `ArkAppView` 渲染，**Java 层没有任何「不支持」判定分支**：
`ArkAppItemBubbleBuilder.getView` 调 `ArkAioContainerWrapper.a(appName, appView, appMinVersion,
metaList, density, msg, session)Z`，返回值被直接丢弃（无 `move-result`），初始化失败只打一条
`QLog.d("ArkApp.ArkAioContainerWrapper", 4, "super.init return false!!! wrapper: %h.")`。
真正的降级在 native `libark` 内部，Java 侧没有 hook 点。

因此新增 `io/github/qauxv/hook/experimental/ArkFallbackToText.kt`（「Ark 卡片降级为文本」，默认关闭）：

- hook 点：`ArkAppItemBubbleBuilder` 里「5 参数 + 返回 View」的方法（`getView` 已混淆成 `a`，按签名定位，
  全类唯一）
- 进入前直接 `param.result = TextView`，原方法不执行 → Ark 容器不初始化
- 文本：`promptText` → `mText` → `compatibleText` 依次兜底；点击打开 `metaList` 第一个 view 的
  `jumpUrl`，兜底 `mSourceUrl`
- 代价：开启后**所有 Ark 消息**都会变成文本气泡；要只针对特定 app，在 hooker 里加一行
  `if (appName != "com.tencent.tuwen.lua") return`

### 7.6 本轮验证

| 项 | 状态 |
| --- | --- |
| `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| `:app:assembleDebug` | ✅ 出包 `QAuxiliary-1.6.1-qq8850-r2-20261109.apk`（20 MB） |
| 实机复测 | ⏳ 需重装后重新导出清单确认 errors 归零 |

---

## 八、第三轮：合并 Ark 转换方案 + 后台保活（2026-11-09 晚）

来源：用户提供的 `push_ci.txt`（GitHub Actions 脚本，内含 base64+xz 打包的 7 个源文件）。
解包后逐文件比对，采纳其中 6 个，保留本仓库自己的 `ForceShowHighVersionStructMsg.kt`。

### 8.1 Ark 卡片不再只能降级为文本

第二轮我给的 `ArkFallbackToText` 只能把 Ark 气泡变成文本（因为 8.8.50 的卡片渲染在 native libark 里，
Java 层没有判定分支）。gemini 的方案更进一步：**把新版 Ark JSON 原地改写成旧版认识的
`com.tencent.structmsg` + `view=news`**，交给 8.8.50 内置的 libark 渲染成真正的图文卡片。

| 文件 | 说明 |
| --- | --- |
| `me/singleneuron/data/StructMsgData.kt` | 新增 `fromArkJsonObject()` / `shouldConvertToStructMsg()` / `tryConvertArkAppMessage()`：`meta.detail_1`（小程序）、`meta.news`/`miniapp`/`music`/任意首个对象（图文分享）都能抽成 structmsg news；用 `getDeclaredMethod` 直接反射 `toAppXml`/`fromAppXml`，**绕开 `Reflex.invokeVirtual` 的参数布局坑**（见 7.3）；转换后清 `mExtra` 并重置 `MessageForArkApp.arkContainer` 缓存，否则气泡不重绘 |
| `me/singleneuron/hook/decorator/MiniAppToStruckMsg.kt` | 改为调用 `StructMsgData.tryConvertArkAppMessage()`，不再只认 `com.tencent.miniapp`，`com.tencent.tuwen.lua` 这类图文分享一并处理；description 同步更新 |
| `io/github/qauxv/hook/experimental/ArkFallbackToText.kt` | 改为两级：先转 structmsg 卡片（能渲染出卡片），只有在没有可转换 `meta` 时才回退文本气泡 |
| `me/singleneuron/data/MiniAppArkData.kt` | 配套数据结构调整 |

### 8.2 新增：后台保持消息加载并压制耗电（`KeepBgMsgForAntiRevoke`）

解决「防撤回提示『没收到』」的根因：QQ 退后台后 `AppRuntime.isBackground_Stop` 置 true，
消息拉取管线停摆，撤回通知先到、原消息还没入库。

- 维持 `isBackground_Stop=false`、保留 `isBackground_Pause=true`（仍能正常弹系统通知）
- 拦截后台 `GuardManager` / `Process.killProcess` / `Runtime.exit` 的主进程自杀（带崩溃/登出/设置页白名单，不拦正常退出）
- `onTrimMemory` 等级封顶 `TRIM_MEMORY_UI_HIDDEN`
- 后台 15 秒后清理附属子进程（保留主进程与 MSF），限制 `WakeLock` 持有 ≤5 秒，屏蔽后台埋点
- 默认关闭；`MainHook` 里与 `RevokeMsgHook` 一起 `allowEarlyInit`

依赖已在 8.8.50 验证：`isBackground_Stop` / `isBackground_Pause` 字段存在，
`Lmqq/app/AppRuntime;->onRunningBackground()V` 存在。

**风险提示**（未实机验证）：进程自杀拦截与子进程清理属于侵入性改动，误判会导致 QQ 无法正常退出/重启；
所有 hook 点都包了 `runCatching` 且有白名单，但首次开启建议观察一天。
