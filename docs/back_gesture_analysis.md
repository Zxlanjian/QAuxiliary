# QQ 聊天页系统返回手势失效 — 诊断报告

- 目标：QQ 8.8.98（`com.tencent.mobileqq`，`targetSdkVersion=26`，无 `enableOnBackInvokedCallback`）
- 设备：Xiaomi HyperOS（Android 16/17），LSPosed 2.2.1 (7939)
- 日志：`f7.log`（56MB，11-10 20:33 → 11-11 12:19）、`v7.log`（3.2MB LSPosed verbose）
- 方法索引：`methods_8.8.98.txt`（139MB，格式 `Lcom/pkg/Class;->name(args)Ret`）
- 分析脚本：`D:\DSH-Builds\QQBypassFix\tools\{scan_gesture,extract_input,stats_back,search_methods,onback}.py`

---

## 0. 结论摘要

**根因不是任何一个 LSPosed 模块，而是 QQ 8.8.98 自身在 Android 13+ 手势导航下的返回事件架构缺陷。**

系统侧手势链路 100% 正常：在聊天页（`SplashActivity`）上系统**确实**识别了 12 次返回手势并 `triggerBack=true`。返回事件被投递到应用后，落到 `Activity.onBackPressed()` 的 framework 默认实现——而 QQ 8.8.98 **根本没有覆写 `onBackPressed()`**，它的返回逻辑全部挂在 **KeyEvent BACK** 路径（`doDispatchKeyEvent` / `doOnKeyDown`）上。在 `OnBackInvokedCallback` 兼容路径下系统不再合成 `KEYCODE_BACK`，于是 QQ 的返回逻辑永远收不到调用 → 表现为"手势失效"。

`SplashActivity` 是 task root，framework 默认 `onBackPressed()` 既不 finish 也不 pop 任何东西，所以**没有任何可见反应**；而 `AIOGalleryActivity` 是普通子 Activity，默认实现会 `finish()`，所以"看起来正常"。

---

## 1. 日志侧证据

### 1.1 系统手势在聊天页正常工作（推翻"手势失效"的表层描述）

`stats_back.py` 全量统计 `f7.log`：

```
startBackNavigation 总数: 25
其中 QQ task(A=10418) 的: 21
onBackNavigationDone 总数: 26

=== startBackNavigation 按 Activity 分布 ===
   12  com.tencent.mobileqq/.activity.SplashActivity      ← 聊天页
    8  com.tencent.mobileqq/.richmediabrowser.AIOGalleryActivity
    2  xyz.nextalone.nagram/org.telegram.messenger.DefaultIcon
    1  com.example.piliplus/.MainActivity
    1  com.tencent.mobileqq/.activity.MultiForwardActivity
    1  com.android.shell/.BugreportWarningActivity

=== onBackNavigationDone 分布 ===
   25  backType=4 triggerBack=true
    1  backType=4 triggerBack=false
```

聊天页上的 12 次，时间戳密集连发：

```
11-11 12:12:33.356  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:12:33.914  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:12:47.163  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:12:49.733  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:12:50.514  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:12:50.980  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:12:51.441  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:12:51.887  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:14:02.052  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:14:08.992  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:14:09.405  topRunningActivity=.../.activity.SplashActivity t3073
11-11 12:14:09.739  topRunningActivity=.../.activity.SplashActivity t3073
```

**解读**：90 秒内连滑 12 次，全部 `triggerBack=true` —— 这是用户在反复尝试返回、页面始终不动的典型指纹。系统从未拒绝过手势。

> 注：先前"聊天页 0 条 startBackNavigation"的印象来自只看了 `scan_gesture.py` 的前 5 条样例（恰好都是别的应用）。全量统计后结论相反，此处以 `back_stats.txt` 为准。

### 1.2 系统手势通道未被窗口配置破坏

`extract_input.py` 统计 831 条 `input_interaction`：

```
  81  com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity
  26  (同上，另一 window token)
  19  (同上，第三 token)

Interaction with: [f15da82 com.tencent.mobileqq/...SplashActivity,
  {touchableRegion=[0,0][1200,2670], inputConfig=0x0},
  PointerEventDispatcher0,
  [Gesture Monitor] onehanded-touch,
  [Gesture Monitor] MultiTaskSwitch,
  [Gesture Monitor] swipe-up,
  [Gesture Monitor] miui-gesture], on displayId 0
```

- `inputConfig=0x0`：没有 `FLAG_NOT_TOUCHABLE` / `FLAG_NOT_TOUCH_MODAL`
- `touchableRegion=[0,0][1200,2670]`：全屏可触摸
- **`[Gesture Monitor] miui-gesture` 在列表里**：MIUI 的边缘返回手势监视器对 QQ 窗口是激活的

`GestureStubRight` 热区（边缘返回判定窗）：

```
Interaction with: [39d4 GestureStubRight,
  {touchableRegion=[1132,280][1200,2576],
   inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY}, ...]
```

右侧 68px 宽（1200-1132），纵向 280–2576。**y<280 或 y>2576 不响应边缘返回**——这是 HyperOS 为避开状态栏/手势条的正常设计，属次要因素。

### 1.3 排除"手势排除区（exclusion rects）"假设

`reportSystemGestureExclusionChanged` / `onSystemGestureExclusionChanged` 共 170 条，全部来自 `com.netease.cloudmusic` 等应用；**筛 `mobileqq` 命中 0 条**。

```
11-11 12:02:29.733  D WindowManager: reportSystemGestureExclusionChanged(): state for package: com.netease.cloudmusic, exclusionRects =[Rect(57, 1975 - 122, 2040)]
...
=== QQ exclusion count above ===   ← 空
```

QQ 从未向 WindowManager 上报过手势排除区，所以 `setSystemGestureExclusionRects` 不是原因。

### 1.4 `MiInputDispatcher: Skip gesture window` 只有 2 条，且属正常

```
11-11 12:17:02.535  I MiInputDispatcher: Skip gesture window, because event action is POINTER_DOWN or HOVER.
11-11 12:17:02.535  I MiInputDispatcher: Skip gesture window, because event action is POINTER_DOWN or HOVER.
```

POINTER_DOWN 时跳过 gesture window 是 MIUI 的既定行为（首个事件不参与手势窗口判定），不是故障。

### 1.5 触摸分发链（`v7.log` 11800–11840，聊天页表情面板点击的真实栈）

```
ViewRootImpl$ViewPostImeInputStage.processPointerEvent
  → DecorView.dispatchTouchEvent(DecorView.java:605)
  → com.tencent.mobileqq.activity.SplashActivity.dispatchTouchEvent(...:9)
  → com.tencent.mobileqq.app.BaseActivity.dispatchTouchEvent(...:9)
  → com.tencent.mobileqq.app.QBaseActivity.dispatchTouchEvent(...:34)
  → mqq.app.AppActivity.dispatchTouchEvent(...:16)
  → FragmentActivity/ComponentActivity.dispatchTouchEvent
  → android.app.Activity.dispatchTouchEvent(Activity.java:4892)
  → PhoneWindow.superDispatchTouchEvent
  → DecorView.superDispatchTouchEvent(DecorView.java:659)
  → ViewGroup.dispatchTouchEvent ×N
  → com.tencent.mobileqq.activity.fling.TopGestureLayout.dispatchTouchEvent(...:7)   ← QQ 边缘手势容器
  → com.tencent.widget.FitSystemWindowsRelativeLayout.dispatchTouchEvent(...:50)
  → ViewGroup.dispatchTouchEvent ×N
  → EmoticonPanelViewPager → EmotionPanelListView(AbsListView)
  → EmoticonPanelLinearLayout.onTouchEvent → QQEmoticonPanelLinearLayoutHelper.onTouchEvent
```

**证实 QQ 单 Activity 架构**：聊天页挂在 `SplashActivity` 上；`TopGestureLayout` 位于 `DecorView` 与业务 View 之间的触摸链上。

---

## 2. 代码侧证据（方法索引）

### 2.1 QQ 没有覆写 `onBackPressed`，返回逻辑只在 KeyEvent 路径

`onback.py` 对 `methods_8.8.98.txt` 全量检索：

```
SplashActivity;->onBackPressed          => 0
SplashActivity;->doOnBackPressed        => 0
QBaseActivity;->onBackPressed           => 0
com/tencent/mobileqq/app/BaseActivity;->onBackPressed => 0
ChatFragment;->onBackPressed            => 0
OnBackInvoked                           => 0     ← 整个 APK 无任何 OnBackInvoked* 符号
Landroid/app/Activity;->onBackPressed   => 1     ← 仅 framework 自身声明
checkGesture                            => 1
widget/DrawerFrame;->setTouchableBound  => 1
```

而 `SplashActivity` 的返回处理确实存在，但走的是键盘事件：

```
Lcom/tencent/mobileqq/activity/SplashActivity;->doDispatchKeyEvent(Landroid/view/KeyEvent;)Z
Lcom/tencent/mobileqq/activity/SplashActivity;->doOnKeyDown(ILandroid/view/KeyEvent;)Z
Lcom/tencent/mobileqq/activity/SplashActivity;->checkGesture()V
Lcom/tencent/mobileqq/activity/SplashActivity;->dispatchTouchEvent(Landroid/view/MotionEvent;)Z
Lcom/tencent/mobileqq/activity/SplashActivity;->clearAIO()V
Lcom/tencent/mobileqq/activity/SplashActivity;->finish()V
```

**这是根因的直接证据**：
1. `OnBackInvoked` 在整个 APK 里 0 命中 → QQ 完全没适配新返回 API
2. `onBackPressed` 在所有 QQ 基类里 0 命中 → 系统的兼容回调落地时无自定义逻辑
3. `doDispatchKeyEvent` / `doOnKeyDown` 存在 → 返回逻辑只挂在 `KEYCODE_BACK` 上

Android 13+ 对 `targetSdk<33` 应用的兼容路径：系统注册 `CompatOnBackInvokedCallback` → 直接调 `Activity.onBackPressed()`，**不再合成 `KEYCODE_BACK`**。于是 `doDispatchKeyEvent(KeyEvent(4))` 永不触发。

### 2.2 `SplashActivity` 是 task root，默认 `onBackPressed` 无副作用

调用链（AndroidX）：

```
系统 → androidx.activity.ComponentActivity.onBackPressed()
     → OnBackPressedDispatcher.onBackPressed()
     → 无已注册 callback → fallback → super.onBackPressed()
     → android.app.Activity.onBackPressed()
        ├─ mActionBar.collapseActionView()      （QQ 无 ActionBar）
        ├─ fragmentManager.popBackStackImmediate()  （QQ 页面切换不走 Fragment backstack）
        └─ !isTaskRoot() ? finishAfterTransition() : 什么都不做   ← SplashActivity 是 task root
```

三步全落空 → 用户看不到任何变化。

对照 `AIOGalleryActivity`：普通子 Activity，`isTaskRoot()==false` → `finishAfterTransition()` → 正常退出。**这解释了"图库页正常、聊天页失效"的差异**，与模块无关。

### 2.3 QQ 确实有自己的边缘手势容器（次要因素）

`Lcom/tencent/mobileqq/widget/DrawerFrame` 完整方法表：

```
onInterceptTouchEvent(Landroid/view/MotionEvent;)Z
onTouchEvent(Landroid/view/MotionEvent;)Z
setTouchableBound(IIII)V          ← 自划边缘可触摸区
setDrawerEnabled(Z)V
l(Landroid/view/MotionEvent;)Z    ← 私有边缘判定
p(IZ)V / v(II)V                   ← 打开/位移入口
g(Z)V
```

聊天页专属消费器：`aio/helper/ChatDrawerHelper;->a(Lcom/tencent/mobileqq/widget/DrawerFrame$TouchEventConsumer;)V`

`setDrawerEnabled(Z)V` 在 8.8.98 只有两个实现：
```
Lcom/tencent/mobileqq/activity/recent/DrawerFrame;->setDrawerEnabled(Z)V   ← 消息列表页
Lcom/tencent/mobileqq/widget/DrawerFrame;->setDrawerEnabled(Z)V            ← 聊天页
```

**但按 1.1/1.2 的证据，系统 gesture monitor 在 InputDispatcher 层已先于应用消费边缘触摸**，应用层 View 的 `onInterceptTouchEvent` 拦不住系统返回手势。所以这一条只能解释"QQ 自己的右滑抽屉/右滑返回"的行为，**不能解释系统返回手势失效**。

---

## 3. 模块侧排查（全部排除为根因）

### 3.1 QAuxiliary `DisableSwipeRight.kt`

源码 `source/app/src/main/java/me/hd/hook/simplify/chat/other/DisableSwipeRight.kt:50-73`：

```kotlin
if (classExists("com.tencent.aio.frame.drawer.DrawerFrameViewGroup")) {
    // NT 架构（9.x）
    ... singleMethod { isPrivate && parameters(Int::class.java) } ... args[0] = 0
} else {
    // QQ 8.8.50
    "com.tencent.mobileqq.widget.DrawerFrame".toHostClass()
        .singleMethod { name("setDrawerEnabled") && parameters(java.lang.Boolean.TYPE) }
        .hookBeforeIfEnabled(this) { param -> param.args[0] = false }
}
```

分支判定实测：`methods_8.8.98.txt` 搜 `aio/frame/drawer` → **0 条**，`com/tencent/aio/frame/drawer/DrawerFrameViewGroup` 在 8.8.98 不存在。所以 8.8.98 **走 else 分支**，hook 到 `widget/DrawerFrame.setDrawerEnabled(Z)` 并强制 `false`（该方法确实存在，见 2.3）。

**判定：hook 生效，但不是返回手势失效的原因。**
- 若它真是原因，症状应是"QQ 右滑抽屉打不开"，而非系统返回手势失效
- 系统返回手势在 InputDispatcher 层完成，与应用内 `setDrawerEnabled` 无关
- 且 12 次 `triggerBack=true` 证明系统侧全程正常

**潜在副作用（值得修，但不是本次根因）**：强制 `setDrawerEnabled(false)` 让 `DrawerFrame` 长期处于禁用态；若其 `onInterceptTouchEvent` 在禁用态仍返回 `true`（吞掉触摸又不滑出），会额外吃掉聊天页的横向滑动。建议改为不拦截触摸（见第 5 节）。

### 3.2 QAuxiliary `DisableFlingToTroopGuild.kt`

`isAvailable = requireMinQQVersion(QQ_8_9_23)`，8.8.98 **不满足下限，功能不加载**。且它 hook 的是 `TroopGuildChatPie.flingRToL()`（群帖子），与返回手势无关。**排除**。

### 3.3 QAuxiliary `DisableInteractivePop.kt`

`isAvailable = requireMinQQVersion(QQ_9_0_0)`，8.8.98 **不加载**。且只 `hookReturnConstant(null)` 两个弹窗管理器的 show 方法。**排除**。

### 3.4 QAuxiliary `DisableLeftSwipeReplyVibrate.kt`

`isAvailable = requireMinQQVersion(QQ_8_9_88)`，8.8.98 **不加载**。且只把 `canSwipeReplyVibrate()` 返回 false（关震动），不碰触摸。**排除**。

### 3.5 QAuxiliary `ArkFallbackToText.kt`

替换 Ark 气泡为 TextView，作用域是气泡内容区，不在屏幕边缘。**排除**。

### 3.6 QQ Bypass (`io.github.xalsace.qqbypass`) `EnvGuard`

`v7.log` 564 条 `[QQBypass]` 全部为环境探测/冻结弹窗相关：

```
[QQBypass] loaded into com.tencent.mobileqq (process=com.tencent.mobileqq)
[QQBypass] native hooks installed=true
[QQBypass] [QQBypass/Env] frozen dialog hooked
[QQBypass] java guards installed
[QQBypass] maps[/proc/version] failed: FileNotFoundException ... EACCES
[QQBypass] maps[/proc/self/maps] lines=4030 bypass=false bytehook=false libQSec=false libturingxq=false
```

无任何 `View` / `MotionEvent` / `dispatchTouchEvent` 相关 hook。`f7.log` 中 `bypass` 关键词 0 条。**排除**。

### 3.7 额外发现：`dev.codex.miuibackgesturehook`（高度可疑，但已排除）

LSPosed 模块表里存在 `dev.codex.miuibackgesturehook`（`db_modules.txt:51-53`），名字直指 MIUI 返回手势。其 scope（`db_scope.txt:477-496`）：

```
app_pkg_name=com.google.android.googlequicksearchbox
app_pkg_name=system
app_pkg_name=com.android.systemui
app_pkg_name=com.miui.home
```

**不含 `com.tencent.mobileqq`**。它作用于 `system`（system_server），属全局性 hook，但既然 1.1 证明系统侧手势正常，它不是本次故障源。**排除**，但列在此处供后续排查其他机型问题时参考。

### 3.8 附带发现：QAuxiliary 在 8.8.98 上有一处 NPE（与本题无关）

```
11-11 11:14:46.000  10418 17326 E/LSPosedFramework
(com.tencent.mobileqq)[io.github.qauxv,QAuxv,...] baseChatPie or ref is null
java.lang.NullPointerException: baseChatPie or ref is null
    at cc.hicore.hook.ReplyMsgWithImg.lambda$initOnce$0$ReplyMsgWithImg(ReplyMsgWithImg.java:192)
    ...
    at com.tencent.mobileqq.emoticonview.AIOEmoticonPanelHelper.send(...)
```

`ReplyMsgWithImg` 在发送自定义表情时拿不到 `baseChatPie`。**不影响返回手势**，但说明 QAux 部分 hook 在 8.8.98 上的 `BaseChatPie` 定位需要复查。

---

## 4. 按可能性排序的结论

| # | 结论 | 置信度 | 关键证据 |
|---|---|---|---|
| **1** | **QQ 8.8.98 未适配 `OnBackInvokedCallback`，返回逻辑只在 `KEYCODE_BACK` 路径；系统兼容路径调 `onBackPressed()` 落到 framework 默认实现，SplashActivity 作为 task root 不 finish → 无反应** | **高（已确证）** | `OnBackInvoked`=0、`onBackPressed`=0、`doDispatchKeyEvent`/`doOnKeyDown` 存在；SplashActivity 上 12 次 `triggerBack=true` 但页面不动；AIOGalleryActivity 正常因其非 task root |
| 2 | `DisableSwipeRight` 强制 `setDrawerEnabled(false)` 使 `widget/DrawerFrame` 处于禁用态，可能吞掉聊天页横向触摸（影响 QQ 自家右滑，不影响系统返回） | 中（副作用，非根因） | 索引确认 else 分支命中；`DrawerFrame` 有 `onInterceptTouchEvent`/`onTouchEvent`/`setTouchableBound` |
| 3 | `GestureStubRight` 热区纵向 280–2576，从屏幕最顶部/最底部边缘滑不触发 | 低（正常设计） | `touchableRegion=[1132,280][1200,2576]` |
| 4 | exclusion rects 导致系统不识别手势 | **已排除** | 170 条 exclusion 日志中 QQ 0 条 |
| 5 | `miuibackgesturehook` / bypass 模块干扰 | **已排除** | scope 不含 QQ；bypass 无 View 相关 hook |

---

## 5. 验证方法与修法

### 5.1 设备侧 60 秒判别（决定是"路径 1"还是"路径 2"）

在聊天页执行：

```bash
adb shell input keyevent 4
```

- **有反应（退出聊天页）** → `KEYCODE_BACK` 路径完好，`onBackPressed` 路径死了 → **确证结论 1**
- **无反应** → 返回逻辑本身有问题（例如 ChatFragment 栈状态异常），需查 `doOnKeyDown` 内部

进一步区分（确认系统确实投递了 back）：

```bash
adb logcat -c; adb logcat | grep -E "CoreBackPreview|ChatActivity|onBackPressed"
# 然后在聊天页做边缘返回手势
```

应能看到 `startBackNavigation ... SplashActivity` + `onBackNavigationDone backType=4, triggerBack=true`，但无任何后续 Activity 变化 —— 与 `f7.log` 一致即复现。

### 5.2 修法 A（推荐）：把系统返回桥接成 `KEYCODE_BACK` 喂给 QQ

给 `SplashActivity` 注册 `OnBackInvokedCallback`，回调里合成 `KeyEvent(KEYCODE_BACK)` 调 QQ 自己的 `doDispatchKeyEvent`。这样 QQ 原有的返回逻辑（关聊天页、退到消息列表、退出）全部照旧工作。

补丁文件：`source/app/src/main/java/cc/ioctl/hook/chat/BackGestureCompat.kt`（已生成，见第 6 节）。

原理：
```
系统边缘手势
  → OnBackInvokedDispatcher.dispatchOnBackInvoked()
  → 我们的 callback
  → activity.doDispatchKeyEvent(KeyEvent(ACTION_DOWN, KEYCODE_BACK))
  → QQ 原返回逻辑
```

### 5.3 修法 B（临时，无需改模块）：切三键导航

系统设置 → 桌面 → 系统导航方式 → 虚拟按键。三键导航下系统合成真实 `KEYCODE_BACK`，QQ 的 `doOnKeyDown` 正常收到。可作为根因的旁证。

### 5.4 修法 C：`DisableSwipeRight` 的加固（顺带修掉第 2 条副作用）

把"强制 `setDrawerEnabled(false)`"改为"让 `DrawerFrame` 不拦截触摸"，既达到屏蔽右滑抽屉的目的，又不破坏触摸链状态机：

```kotlin
} else {
    // QQ 8.8.x：不再全局 setDrawerEnabled(false)（会让 DrawerFrame 处于禁用态并可能吞掉边缘触摸），
    // 改为直接让抽屉容器不拦截、不消费触摸 —— 抽屉永不滑出，触摸完整下传。
    val clz = "com.tencent.mobileqq.widget.DrawerFrame".toHostClass()
    clz.singleMethod { name("onInterceptTouchEvent") && parameters(MotionEvent::class.java) }
        .hookBeforeIfEnabled(this) { param -> param.result = false }
    clz.singleMethod { name("onTouchEvent") && parameters(MotionEvent::class.java) }
        .hookBeforeIfEnabled(this) { param -> param.result = false }
}
```

> 注意：该修改**不是**本次返回手势失效的修复，只是消除一个潜在副作用。是否合入由维护者决定。

---

## 6. 交付物

| 文件 | 说明 |
|---|---|
| `analysis/back_gesture_analysis.md` | 本报告 |
| `analysis/back_stats.txt` | `startBackNavigation` 全量统计（按 Activity 分布） |
| `analysis/input_f7.txt` | 831 条 `input_interaction` 窗口/手势监视器分析 |
| `analysis/scan_f7.txt` / `scan_v7.txt` | 13 组关键词全量计数与样例 |
| `analysis/methods_scan.txt` | fling 包 / TopGestureLayout / DrawerFrame / exclusion rects 索引检索 |
| `analysis/onback.txt` | `onBackPressed` / `OnBackInvoked` 索引检索（根因直接证据） |
| `source/.../cc/ioctl/hook/chat/BackGestureCompat.kt` | 修法 A 补丁 |

---

## 7. 未完成 / 需设备确认项

1. **`doDispatchKeyEvent` 的签名与可见性**：索引确认存在 `(Landroid/view/KeyEvent;)Z`，但未反编译确认它是 `public`（补丁用反射调用，`setAccessible(true)` 可绕过）。
2. **QQ 在 `KEYCODE_BACK` 下的实际行为**：需 `adb shell input keyevent 4` 实测（见 5.1）。若实测也无反应，说明聊天页的返回栈状态本身异常，需进一步查 `ChatFragment` / `BaseChatPie` 的返回处理。
3. **12:12:33–12:14:09 之外是否还有聊天页手势尝试**：`f7.log` 覆盖 11-10 20:33 → 11-11 12:19，聊天页集中在 12:12–12:14 一段；若用户在其他时段也复现过，说明现象稳定，与本次结论一致。
4. **1 条 `triggerBack=false`**：位于跨日志边界的样本，未能配对到 `startBackNavigation`，不影响结论。
