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
