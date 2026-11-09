# QQ 8.8.50（versionCode 2324）QSec 冻结 / 踢下线链路分析

> 分析对象：`D:\DSH-Builds\QAuxiliaryFix\apks\QQ_8.8.50.apk`（23 dex）
> 方法索引：`analysis\methods_8.8.50.txt`（112 万条）
> 工具：自研 DEX 解析/反汇编器 `D:\DSH-Builds\_scratch\qsec\xref.py`（直接读 23 个 dex 的 class_data + code_item，输出 invoke 目标与 const-string 引用，绕过 jadx 全量反编译的耗时）
> 时间：2026-11-10

---

## 0. 一句话结论

**Java 层没有「检测」，只有「执行」。** 检测实体（libQSec / libturingmfa / libMSFKernel）与判定（服务端风控）都在 Java 之外；Java 侧拿到的是**登录响应里的 TLV 1347**（`Bundle["tlverror"]`），解析成 `GatewayVerify.RspBody` 后决定「显示冻结页 / 清密码 / 跳登录页」。

因此 Java 层 hook 只能做到：**不显示冻结页、不跳登录页、不执行本地退出**；**不能**阻止服务端已下的判定（会话作废后功能照样不可用）。要真正规避「自动下号」，必须配合 native 绕过模块。

---

## 1. 全链路图

```
┌──────────────────────────── native（Java 进不去） ────────────────────────────┐
│ libQSec.so      : 环境检测（Xposed / Frida / /proc/self/maps / magisk 路径）    │
│ libturingmfa.so : 设备指纹上报（send/sendto）                                   │
│ libMSFKernel.so : setQimei36（QIMEI36 伪造点）                                  │
│   ↓ 检测结论经 native 直接组包上报（SSO / CSP），不落 Java 变量                   │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    │ 上报（Java 侧可见的另一条：MqqSafeDataRpt.MQDun）
                                    ▼
                        服务端风控判定（不可见）
                                    │
                                    ▼ 登录响应 TLV 1347（wlogin loginResultTLVMap[1347]）
┌──────────────────────────── Java（可 hook 的部分） ───────────────────────────┐
│ Lmqq/app/BuiltInServlet;->bundlePutTlv543(FromServiceMsg, Bundle)V             │
│     Bundle["tlverror"] = tlv_t.get_data()                                      │
│                              ↓                                                 │
│ Lmqq/observer/AccountObserver;->onReceiveLoginActions(IZLandroid/os/Bundle;)V   │
│                              ↓                                                 │
│ com.tencent.mobileqq.loginregister.NotifyLoginAction;->a(QBaseActivity,        │
│        AppRuntime, LoginErrorInfo)V                                            │
│     Intent{ type=8, msg, errorUrl, errorver, tlverror, errortitle,             │
│             is_need_login_with_mask, uin, passwd, loginalias, loginret,        │
│             expiredSig, keyFromLoginView }                                     │
│     RouteUtils → "/base/notification"                                          │
│                              ↓                                                 │
│ com.tencent.mobileqq.activity.NotificationActivity                             │
│   ├ parseIntentErrorData(Intent)                                               │
│   │    ├ FrozenNotifyDlgHelper;->a([B)Z  → mUnifyFrozenStyle                   │
│   │    │     GatewayVerify.RspBody.msg_rsp_lft_info.uint32_lft_forbid_area != 0│
│   │    │     && msg_rsp_tips_info.uint32_scene_id ∈ {2,3,4}                    │
│   │    └ FrozenNotifyDlgHelper;->b([B)I  → mUnifyFrozenScene                   │
│   │          = msg_rsp_tips_info.uint32_scene_id                               │
│   ├ myShowDialog(I) / handleUnifyFrozenStyle()   → 冻结弹窗                    │
│   ├ exitApp()                                                                  │
│   └ kickToLoginActivity()                                                      │
│        finish() + Bundle{password="", is_from_account_another_login_exit=true} │
│        + RouteUtils("/base/login") + 广播 mqq.intent.action.ACCOUNT_KICKED      │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 问题 1：谁调用 `FrozenKickUtils.a(...)`，参数是什么

### 2.1 反编译结果（classes12.dex）

```
Lcom/tencent/mobileqq/qqsec/api/FrozenKickUtils;->a(Landroid/content/Context;Ljava/lang/String;Z)V
  0000: const-string/jumbo     v0, 'SharedPreUtils'
  0003: const/4                v1, #2
  0004: if-eqz                 v6, -> 0077              # 第三参 Z
  0006: invoke-virtual         Ljava/lang/String;->length()I
  000a: if-nez                 v2, -> 000d              # 第二参 String 为空则跳 0077
  0018: const-string/jumbo     v3, 'CNR setAutoLoginSharePre auto ='
  0034: const-string/jumbo     v2, '_preferences'
  004c: const-string/jumbo     v4, 'login_auto'
  0059: invoke-interface       Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)…
  0060: const-string/jumbo     v0, 'sp_login_auto'
  006f: invoke-interface       Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)…
  0077: const-string/jumbo     v5, 'CNR setAutoLoginSharePre uin is null'
```

**结论：这个方法不是「踢下线」，也不是「弹窗」。** 它的真实语义是 `setAutoLoginSharePre(Context ctx, String uin, boolean auto)`：

| 参数 | 含义 | 证据 |
| --- | --- | --- |
| `Context` | 取 SharedPreferences 用 | `Context.getSharedPreferences("…_preferences", 4)` |
| `String` | uin；为空直接 return | `if (uin.length() == 0) → 0077` + 日志 `CNR setAutoLoginSharePre uin is null` |
| `Z` | **自动登录开关值**（`putBoolean("login_auto"+uin, Z)`） | `putBoolean` 后紧跟 `putInt("sp_login_auto"…)` |

即：**冻结/踢下线流程里用它把「自动登录」关掉**，避免重启后又自动登回。Z 既不是「立即退出」也不是「弹窗」。

### 2.2 调用者

全 23 dex 的 invoke 扫描（`xref.py xref`）只有一处：

```
Lcom/tencent/mobileqq/qqlive/api/impl/room/AudienceRoomPlayModule$4;-><init>(AudienceRoomPlayModule;ZLjava/lang/String;)V
   ==> Lcom/tencent/mobileqq/qqsec/api/FrozenKickUtils;->a(Landroid/content/Context;Ljava/lang/String;Z)V   [classes12.dex]
```

（该类是 R8 合成的 lambda body，真实调用点在 qqlive 直播间退出/切换账号路径，属于「关自动登录」的正常用法。）

> **推测**：`FrozenKickUtils` 这个类名是「冻结后的收尾工具」而不是「执行冻结」，不要把它当成踢下线执行点来 hook —— hook 它只会让下次启动仍然自动登录，反而更容易反复触发风控。

---

## 3. 问题 2：检测结论从哪进 Java

### 3.1 Java 层没有环境检测代码

对 23 个 dex 的全部 string_ids 做关键词扫描（`xref.py sref xposed`），含 `xposed` 的字符串只有 3 处，且**全部属于 Bugly 崩溃 SDK**：

```
STR [classes.dex] #49456 'de.robv.android.xposed.XposedBridge'
STR [classes.dex] #49457 'de.robv.android.xposed.installer'
STR [classes.dex] #85843 'xposed'
  USE Lcom/tencent/bugly/crashreport/common/info/b;->j(Landroid/content/Context;)I  <== 'de.robv.android.xposed.installer'
  USE Lcom/tencent/bugly/crashreport/common/info/b;->p()I                          <== 'de.robv.android.xposed.XposedBridge'
  USE Lcom/tencent/bugly/crashreport/common/info/b;->q()I                          <== 'xposed'
```

`/data/adb/magisk`、`/data/local/tmp/frida`、`/system/xposed`、`/proc/self/maps` 这些串在 dex 里**一个都没有**（native 模块 hook 的 `fopen("/proc/self/maps")` 就是用来堵这条路的）。

→ 与「检测实体在 native」的既有结论一致：**Java 层不产生、也不接收原始检测结论**。

### 3.2 结论进入 Java 的唯一形态：TLV 1347

```
Lmqq/app/BuiltInServlet;->bundlePutTlv543(Lcom/tencent/qphone/base/remote/FromServiceMsg;Landroid/os/Bundle;)V
  0005: const-string/jumbo     v0, 'userSigInfo'
  000e: check-cast             → Loicq/wlogin_sdk/request/WUserSigInfo
  001d: iget-object            v4, v2, Loicq/wlogin_sdk/request/WUserSigInfo;->loginResultTLVMap:Ljava/util/HashMap;
  0022: const/16               v0, #1347
  0028: invoke-virtual         Ljava/util/HashMap;->get(Ljava/lang/Object;)Ljava/lang/Object;
  0031: invoke-virtual         Loicq/wlogin_sdk/tlv_type/tlv_t;->get_data()[B
  0038: const-string/jumbo     v0, 'tlverror'
  003b: invoke-virtual         Landroid/os/Bundle;->putByteArray(Ljava/lang/String;[B)V
```

**这就是「检测/风控结论」进入 Java 的唯一入口**：服务端把判定塞进登录响应的 TLV 1347，客户端原样放进 `Bundle["tlverror"]`。

同一 Bundle 被 `mqq/observer/AccountObserver;->onReceiveLoginActions(IZ,Landroid/os/Bundle;)V` 消费，转成 `LoginErrorInfo`，再由 `NotifyLoginAction` 拼成 Intent：

```
Lcom/tencent/mobileqq/loginregister/NotifyLoginAction;->a(QBaseActivity, AppRuntime, LoginErrorInfo)V
  0013: Intent.putExtra("type", 8)
  006c: putExtra("errorver", LoginErrorInfo.f)
  0074: putExtra("tlverror", LoginErrorInfo.g)        # byte[]，就是 TLV1347
  007c: putExtra("errortitle", LoginErrorInfo.h)
  0084: putExtra("is_need_login_with_mask", LoginErrorInfo.k)
  009c: putExtra("uin", …)  00a4: putExtra("passwd", …)
  00dd: putExtra("loginret", LoginErrorInfo.d)
  00e5: putExtra("errorUrl", LoginErrorInfo.c)
  00ed: putExtra("expiredSig", LoginErrorInfo.e)
  00f9: RouteUtils.a(ctx, intent, "/base/notification")
```

### 3.3 Java 侧解析：`FrozenNotifyDlgHelper`

```
a([B)Z  (shouldHandleByDlgHelper)   [classes9.dex]
  0010: new-instance   Ltencent/im/login/GatewayVerify$RspBody;
  0013: mergeFrom([B)
  0018: iget-object    msg_rsp_lft_info.uint32_lft_forbid_area
  001e: if-nez         → 003d                       # forbid_area == 0 → 不处理
  0020: iget-object    this.c (ArrayList<Integer>)
  0024: iget-object    msg_rsp_tips_info.uint32_scene_id
  002e: ArrayList.contains(Integer.valueOf(scene_id))
  0032: if-eqz         → 003d
  0034: const/4 v0, #1                              # 命中 → true
```

* `this.c` 在构造函数里被填成 `{2, 3, 4}`（`<init>` 里三次 `ArrayList.add(Integer.valueOf(2/3/4))`）。
* `b([B)I`（getForbidScene）只取 `msg_rsp_tips_info.uint32_scene_id`，失败返回 `-1`。
* 命中后 `NotificationActivity.parseIntentErrorData` 把 `mUnifyFrozenStyle=true`、`mUnifyFrozenScene=scene_id`，进入统一冻结样式分支。

**所以「检测到 hook」在 Java 层的可观测形态是：`GatewayVerify.RspBody.msg_rsp_lft_info.uint32_lft_forbid_area != 0` 且 `msg_rsp_tips_info.uint32_scene_id ∈ {2,3,4}`。** 没有「hook / xposed / 环境异常」这种字段或常量。

### 3.4 顺带排除的类

| 类 | 结论 | 证据 |
| --- | --- | --- |
| `com.tencent.qqprotect.qsec.CSProcessorImpl` | **纯通道**，无检测分支 | `getInterfaceName() = "CSP"`；`a(String,[B,ICSProcessorListener)` 只做 `ProtoUtils.a(runtime, wrapper, payload, cmd)`；`CSProcessorImpl$2.onResult(I,[B,Bundle)` 是 `return-void` 空实现 |
| `QSecRptHelper` | **上报 payload 拼接器**，无判定 | 只有 `StringBuilder` + `a(String)`（把 `,` 换成 `;` 再 append）/`a(int)`（`%d`）/`a([B)`（`HexUtil.bytes2HexStr`）/`toString()` |
| `QSecRptConst` | **空类**（8.8.50 已退化） | 反汇编只有 `<init>` + `Object.<init>`，无任何 static 常量；`b(String,III)` 也是空方法体 `return-void` |
| `CloudAVEngineImpl` | 云查杀结果缓存（文件 hash → ResultBundle），与 hook 检测无关 | 内部类全是 `AVEngineCache` / `CacheFileReader` / `XML` 序列化 |

---

## 4. 问题 3：上报链路

### 4.1 发送出口（唯一）

```
Lcom/tencent/qqprotect/common/QSecRptController;->b(Lcom/tencent/mobileqq/app/QQAppInterface;Lcom/tencent/ims/SafeReport$ReqBody;)V
  000e: new-instance   Lcom/tencent/qqprotect/common/QSecRptController$2;
  0013: invoke-virtual Lcom/tencent/ims/SafeReport$ReqBody;->toByteArray()[B
  0017: const-string/jumbo v1, 'MqqSafeDataRpt.MQDun'      ← SSO 命令名
  001a: invoke-static  Lcom/tencent/biz/ProtoUtils;->a(Lmqq/app/AppRuntime;Lcom/tencent/biz/ProtoUtils$TroopProtocolObserver;[BLjava/lang/String;)V
```

`a(QQAppInterface, SafeReport$ReqBody)V` 只是转发到 `b(...)`，所以**唯一咽喉是 `ProtoUtils.a(..., "MqqSafeDataRpt.MQDun")`**。

### 4.2 组包

```
Lcom/tencent/qqprotect/common/QSecRptController;->a(Ljava/lang/String;IILcom/tencent/mobileqq/app/QQAppInterface;)V
  0002: new SafeReport$ReqBody
  0007: new SafeReport$LogItem
  000a: LogItem.uint32_rpt_id.set(int)
  0013: b(I)Ljava/lang/String;                # 拼设备指纹串
  0034: LogItem.bytes_rpt_data.set(ByteStringMicro.copyFrom(str.getBytes()))
  003f: ReqBody.LogItem_reportdata.add(LogItem)
  0051: new QSecRptController$1(QQAppInterface, ReqBody)
  0059: ThreadManager.post(runnable, 5, null, true)      # 异步
```

`b(int)` 拼的 payload（逗号分隔，`QSecRptHelper` 逐段 append）：

```
NetConnInfoCenter.GUID → DeviceInfoUtil.g() → DeviceInfoUtil.a(ctx) → QPMiscUtils.c() → QPMiscUtils.d()
→ Build.CPU_ABI → PhoneInfoMonitor.getModel() → Build.MANUFACTURER → QPMiscUtils.a()
→ AppSetting.d() → DeviceInfoUtil.j()
```

### 4.3 调用者（`xref` 结果）

```
CommTvRpt.a(II)                    → a(String,int)
QSecRptControllerImpl$ReportRunnable.run() → a(I)/b(I)      ← 定时上报队列
GGMM.k()                           → b(String,int)
QSecLibMgr.b(LoadedLibInfo)        → b(String,int)          ← native 库加载结果
FilterManager.b(String,Object)     → a(String,int)
RptImpl.a(II,String)               → a(String,int) / b(String,int)
QPUpdateManager.a(String,int,String,String) → a(String,int,int)
```

`QSecLibMgr.b(LoadedLibInfo)` 值得注意：**native 安全库（QSec）加载/校验结果本身就是一条上报**。

### 4.4 能不能在 Java 层拦

能拦（`ProtoUtils.a` 或 `QSecRptController.b`），但**不建议无脑拦**：

* 上报是 `ThreadManager.post` 异步的，拦掉不会崩；
* 但 `MqqSafeDataRpt.MQDun` 同时承载安全库加载心跳、异常统计、TV 上报。**服务端收不到心跳，比收到「正常」心跳更异常**（推测：风控侧「缺报」是独立信号，且 native 侧可能另有一条直连上报通道，Java 拦不到）。

---

## 5. 问题 4：Java 层可 hook 点（含副作用）

| # | 类 + 方法 + 签名 | 改什么 | 效果 | 副作用 |
| --- | --- | --- | --- | --- |
| **A** | `com.tencent.mobileqq.app.security.FrozenNotifyDlgHelper;->a([B)Z`<br>`b([B)I` | `a` 返回 `false`；`b` 返回 `-1` | `mUnifyFrozenStyle=false`、`mUnifyFrozenScene=-1`，冻结页降级成普通登录错误页 | 最小侵入；登录仍会被服务端拒绝，只是没有「冻结」样式与文案。**推荐作为默认档** |
| **B** | `mqq.app.BuiltInServlet;->bundlePutTlv543(FromServiceMsg, Bundle)V` | hookAfter 里 `bundle.remove("tlverror")` | 冻结信息在进入 Intent 之前就消失，`errorUrl`/`errortitle` 可能随之缺失，页面走普通错误分支 | 若服务端只靠 TLV1347 传错误页 URL，可能显示空白错误页（比 A 更「脏」）；对其它依赖 tlverror 的登录错误码也一并影响 |
| **C** | `com.tencent.mobileqq.activity.NotificationActivity;->kickToLoginActivity()V` | hookBefore `param.result = null` | 不 `finish()`、不清本地密码、不 `RouteUtils("/base/login")`、不发 `ACCOUNT_KICKED` 广播 | 用户停在冻结页出不去（需要自己返回）；本地登录态还在但服务端已作废，**下一次网络请求照样失败**，属于「掩耳盗铃」档 |
| D（不建议） | `QSecRptController;->b(QQAppInterface, SafeReport$ReqBody)V` 或 `ProtoUtils.a(...)` cmd `MqqSafeDataRpt.MQDun` | 直接 return | 少发一次安全上报 | 缺心跳更可疑；且 native 上报通道不受影响，等于白拦 |

> 另：`SafeBlockApiImpl` 全部是转发壳（`exitApp`/`finishNotifyActivityByKicked`/`getForbidScene`/`shouldHandleByDlgHelper` 都只是 `FrozenNotifyDlgHelper` 或 `NotificationActivity.instance` 的一行委托），hook 它没有额外收益，直接 hook 被委托方更稳。
>
> `ISecControllerInterface` 是 `QQSecBlockInjectUtil.a`（`ArrayList<Class>`）在 `FrozenNotifyDlgHelper.<clinit>` 里 `newInstance` 出来的注入接口，`kickToLoginActivity` 会调 `b(BaseQQAppInterface)` 取「被冻结的子账号列表」。这是可插拔点，但 QQ 8.8.50 里默认实现为空列表（推测：该接口用于多账号/子账号冻结，不参与主账号判定）。

---

## 6. 问题 5：Java 层到底能不能防

**不能真正防。** 分三层说清楚：

1. **检测层**：`libQSec` / `libturingmfa` / `libMSFKernel` 在 native 采集环境特征（maps、magisk 路径、frida 端口、QIMEI36），Java 层完全看不到这些数据，也没有对应的 Java 方法可 hook（第 3.1 节的字符串扫描为证）。
2. **判定层**：判定在服务端。客户端能做的只是「不发上报」或「发假上报」，而假上报需要同时伪造 native 侧的签名/加密（`MqqSafeDataRpt.MQDun` 的 body 由 `SafeReport$ReqBody` protobuf 序列化，签名在 MSF/native 层），纯 Java 改字段会因校验失败被丢弃（推测，依据：`QSecRptHelper` 只拼明文，加密/签名不在此链路）。
3. **执行层**：Java 能挡（上表 A/B/C），但挡的是「客户端表现」，不是「账号状态」。服务端一旦把会话标记为冻结，`/base/login` 之后的任何请求都会失败。

### 用户该怎么做（按性价比排序）

1. **必须配合 native 绕过模块**：`QQ Enhanced Bypass 1.0.7` 那套（`setQimei36` 伪造 + QSec `dlopen/dlsym/dladdr` 伪造 + Turing `send/sendto` 拦截 + `fopen("/proc/self/maps")` 拦截）才是对症的；QAuxiliary 是纯 Java 模块，进不了这一层。
2. **降低触发概率的配置**（减少「被判定」的输入面）：
   - 不给 QQ 授予「读取应用列表」权限（`QUERY_ALL_PACKAGES`），Xposed 管理器/模块包名是最常见的判定输入；
   - 不用 `LSPosed` 的「作用域包含 QQ + 模块自身可见」组合，改用「只对 QQ 生效、隐藏模块自身」；
   - 关闭 Frida server、不要在 `/data/local/tmp` 留 `frida-server`；
   - Magisk 用 Zygisk + DenyList 覆盖 QQ，并把 `/data/adb/magisk` 路径对 QQ 隐藏（`magisk --denylist` + 随机化包名）；
   - 不要在多开/分身里跑 QQ（多开会额外触发设备指纹不一致）。
3. **Java 层只做「体验兜底」**：用本仓库新增的 `QSecFrozenBypass`（默认关闭，LEVEL=1）把冻结页降级成普通错误页，避免「点一下就自动下号」的观感；它不改变账号状态，不要指望它解封。

---

## 7. 交付物

| 文件 | 说明 |
| --- | --- |
| `analysis\qsec_detection_analysis.md` | 本报告 |
| `source\app\src\main\java\io\github\qauxv\hook\experimental\QSecFrozenBypass.kt` | 实验性 hook 草稿（默认关闭，`EXPERIMENTAL_CATEGORY`，LEVEL 1/2/3 三档） |
| `D:\DSH-Builds\_scratch\qsec\xref.py` | 自研 DEX xref/反汇编工具（`xref` / `dump` / `sref` / `methods` 四个子命令），可复现本报告全部证据 |
| `D:\DSH-Builds\_scratch\qsec\dump_*.txt` | 各类的完整反汇编输出（FrozenNotifyDlgHelper / NotificationActivity / QSecRptController / BuiltInServlet / NotifyLoginAction / CSProcessorImpl / SafeBlockApiImpl） |

## 8. 复现命令

```bash
export PY="C:/Users/Administrator/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"
cd /d/DSH-Builds/_scratch/qsec
# 调用关系
"$PY" xref.py xref /d/DSH-Builds/QAuxiliaryFix/apks/dex 'Lcom/tencent/mobileqq/app/security/FrozenNotifyDlgHelper;'
# 单类反汇编
"$PY" xref.py dump /d/DSH-Builds/QAuxiliaryFix/apks/dex 'Lcom/tencent/qqprotect/common/QSecRptController;'
# 字符串引用者
"$PY" xref.py sref /d/DSH-Builds/QAuxiliaryFix/apks/dex tlverror xposed
```
