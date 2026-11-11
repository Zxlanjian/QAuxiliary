# QQ 8.8.98 适配分析（第一轮）

目标版本：**QQ 8.8.98，versionCode 3002**（原 8.8.50 = 2324）
方法索引：`analysis/methods_8.8.98.txt`，**1,314,516 条唯一签名**（8.8.50 是 1,122,776）
dex：30 个 / 249.8 MB（8.8.50 是 23 个 / 201 MB）

---

## 一、原有修复点在 8.8.98 上的存活情况

对 8.8.50 修复用到的 21 个关键目标做了存在性验证：**10 个仍在，11 个 MISS**。

### 仍存在（无需改动）

| 目标 | 用途 |
| --- | --- |
| `PatchedTextView.onMeasure(II)V` | 修复多行消息折叠 |
| `StructMsgForGeneralShare.getView(...)` / `readExternal(ObjectInput)V` | 强制显示高版本分享卡片 |
| `InputUIUtils.a(AIOContext,BaseSessionInfo,Z)V` | 禁止回复自动@ |
| `mqq.app.AppRuntime.onRunningBackground()V` | 后台保活防撤回 |
| `ArkAppItemBubbleBuilder.<init>(5 参)` | Ark 卡片转换（构造函数） |
| `DrawerFrame.setDrawerEnabled` | ⚠️ 见下 |
| `ProfileCardApiImpl.isProfileCardShowZplanCover(ProfileCardInfo)Z` | **8.8.98 上新增存在**（8.8.50 没有），所以该 hook 在 8.8.98 上会真正生效 |
| `ArkAppMessage.fromAppXml(String)Z` / `toAppXml()String` | 小程序转链接 |

### MISS（需要重新定位）

| # | 原目标（8.8.50） | 8.8.98 现状 |
| --- | --- | --- |
| 1 | `ArkAppItemBubbleBuilder.a(ChatMessage,ViewHolder,View,BaseChatItemLayout,OnLongClickAndTouchListener)View` | 5 参 getView 签名变了 |
| 2 | `FrozenNotifyDlgHelper.a([B)Z` | **类还在，但方法签名全变了**（见下） |
| 3 | `FrozenNotifyDlgHelper.b([B)I` | 同上 |
| 4 | `TroopGagMgr.a(IJJJLjava/util/ArrayList;)V` | 签名变了 |
| 5 | `QQSettingMe.a(QQSettingMeBizBean)Z` | 签名变了 |
| 6 | `QQValuePagView.a(String,String,Z)V` | 签名变了 |
| 7 | 类 `troop/tipsbar/TroopSecurityTipsBanner` | 类不存在 |
| 8 | `TroopToDoController.a(Z)V` | 签名变了 |
| 9 | 类 `data/MessageForPokeEmo` | 类不存在 |
| 10 | `PokeEmoItemBuilder.a(...5 参...)View` | 签名变了 |
| 11 | `MSFKernelBridge$CppProxy.native_setQimei36(String)V` | **整个 `msfcore.MSFKernelBridge` 在 8.8.98 上 0 命中**（8.8.98 的 MSF 是旧架构 `com/tencent/mobileqq/msf/core/MsfCore`） |

---

## 二、`FrozenNotifyDlgHelper` 在 8.8.98 的完整方法表（已确认）

8.8.50 的 `a([B)Z`（是否冻结）/ `b([B)I`（冻结场景）在 8.8.98 上**都没有了**，替换为：

```
a()Lcom/tencent/mobileqq/qqsec/api/ISecControllerInterface;   // 取控制器
b(I)Ljava/lang/String;
c([B)I                                                        // ← 对应原 b([B)I（场景 id）
d()L...FrozenNotifyDlgHelper;                                 // 单例
e(Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;
f(Ljava/lang/String;II)I
g(I)I
h(Ljava/lang/String;Lcom/tencent/mobileqq/app/QBaseActivity;I)Ljava/lang/CharSequence;
i(I)V
j(Ljava/lang/String;)Z                                        // ← 三个 boolean 判定，对应原 a([B)Z
k(Ljava/lang/String;)Z
l(Ljava/lang/String;)Z
m(Lcom/tencent/common/app/business/BaseQQAppInterface;IILjava/lang/String;)V
n..s(Lcom/tencent/common/app/business/BaseQQAppInterface;IZ)V   // 六个「弹冻结窗」重载
t(Lcom/tencent/mobileqq/app/QBaseActivity;Ljava/lang/String;)Z
```

**适配结论**：`QSecFrozenBypass` 需要从「hook `a([B)Z` + `b([B)I`」改成
「hook `j/k/l(String)Z` 返回 false + `c([B)I` 返回 -1」。
`EnvGuard.hookFrozenDialog`（bypass 模块）同理——它现在按 `byte[]` 参数筛方法，在 8.8.98 上一个都筛不到。

---

## 三、`DrawerFrame` 在 8.8.98 被混淆

方法表（31 条）里**没有 `setDrawerEnabled`**，同类语义被混淆成：

```
g(Z)V                       ← 疑似 setDrawerEnabled（唯一单 boolean 参数）
l(Landroid/view/MotionEvent;)Z  ← 疑似 onInterceptTouchEvent / onTouchEvent
e(Lcom/tencent/mobileqq/widget/DrawerFrame$TouchEventConsumer;)V  ← 设置触摸消费者
```

**影响**：`DisableSwipeRight`（屏蔽聊天右滑）在 8.8.98 上会**初始化失败**——它用
`singleMethod { name("setDrawerEnabled") }` 精确匹配方法名，而 `singleMethod` 内部是
`declaredMethods.single{}`，找不到就抛 `NoSuchElementException`。

这也意味着：**「返回手势失效」不是这个 hook 造成的**（它根本没挂上）。

---

## 四、聊天页返回手势失效：目前的证据

### 日志侧

- 系统 `CoreBackPreview` 日志里，QQ 的 AIOGalleryActivity / MultiForwardActivity 返回手势**正常**
  （`startBackNavigation` → `onBackNavigationDone backType=4, triggerBack=true`）
- 全量 56 MB 日志里 `triggerBack=false` **只有 1 条**，且当时前台是 `xyz.nextalone.nagram`（Telegram），不是 QQ
- 日志里搜 `ChatActivity` **0 条**——QQ 把聊天页的输入分发日志过滤了

**推论**：QQ 聊天页的手势失效时，系统侧**根本没有记录 startBackNavigation**，说明手势在到达系统之前
就被应用层的某个 View 消费了。

### 代码侧（最可能的原因）

QQ 8.8.98 的聊天页外层是 `com.tencent.mobileqq.widget.DrawerFrame`，它有：

- `l(MotionEvent)Z` —— 极可能是 `onInterceptTouchEvent`，负责判断边缘滑动
- `e(TouchEventConsumer)` —— 允许外部挂一个「触摸事件消费者」

当 `DrawerFrame` 拦截了左侧边缘滑动、但抽屉内容为空或已禁用时，表现就是
**「滑了没反应、页面也不后退」= 系统返回手势失效**。

### 另一个已确认的模块侧问题（与手势无关，但同属 8.8.98 适配）

```
java.lang.NullPointerException: baseChatPie or ref is null
    at cc.hicore.hook.ReplyMsgWithImg.lambda$initOnce$0(ReplyMsgWithImg.java:192)
    at ...CustomEmotionSenderUtil.sendCustomEmotion
    at ...QQEmoticonPanelLinearLayoutHelper.onTouchEvent
    at android.view.View.dispatchTouchEvent
```

`ReplyMsgWithImg` 的 `mBaseChatPie` 在 8.8.98 上一直没被赋值（`onInitBaseChatPie` 回调没触发），
所以点收藏表情会 NPE。这是 8.8.98 适配要修的另一个点。

---

## 五、下一步

1. 定位 MISS 1/4/5/6/8/10 在 8.8.98 的新签名（按参数类型 + 返回类型匹配）
2. 改 `QSecFrozenBypass`（`j/k/l(String)Z` + `c([B)I`）和 bypass 的 `EnvGuard`
3. `DisableSwipeRight` 改用 `g(Z)`（或按 `parameters(Boolean.TYPE)` + 返回 void 匹配）
4. 手势：hook `DrawerFrame.l(MotionEvent)Z` 让边缘手势不被消费，需要先确认 `l` 的语义
5. 修 `ReplyMsgWithImg` 的 baseChatPie 注入
