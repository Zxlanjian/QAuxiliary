# QQ 8.8.50 分享消息「暂不支持该消息类型」逆向分析报告

- 分析对象：`QQ_8.8.50.apk`（`D:\DSH-Builds\QAuxiliaryFix\apks\QQ_8.8.50.apk`），23 个 dex，201 MB
- 工具：aapt2 35.0.0、dexdump（build-tools 35.0.0）、jadx 1.5.1、自写 dex 解析器（`analysis/dexscan*.py`、`analysis/scan_const.py`）
- 样本消息：`app=com.tencent.tuwen.lua`、`view=news`、`app_type=1`、`appid=1105910806`（小黑盒分享卡片）
- 结论可信度标注：**【已验证】**= 有 smali/资源直证；**【未验证】**= 推理，缺直证

---

## 1. 结论（TL;DR）

1. **文案资源定位【已验证】**
   - `0x7f120d19` = `string/wvb` = `R.string.fs_message_unsupported_type` = `[暂不支持该消息类型，请升级至最新版本QQ查看]`
   - `0x7f1225f8` = `string/yl4` = 同值（小世界/QCircle 资源组，`去小世界看更多精彩内容` 相邻）
2. **这两个资源在 8.8.50 的全部 23 个 dex 中没有任何代码引用【已验证】**
   - 扫 `const`（opcode `0x14` 31i / `0x15` 21ih）该两个 id：**0 命中**
   - 扫 `sget*` 引用 `R$string.fs_message_unsupported_type` 字段：**0 命中**（该 field 只出现在 `classes19.dex` 的 `R$string` 定义处，`field_idx=35353`）
   - 全 dex 字节级搜索 UTF-8 `暂不支持该消息类型`：**0 命中**（连 `HardCodeUtil` 硬编码表里都没有）
   - ⇒ 8.8.50 客户端**不会主动**把这条消息渲染成这段文字；显示该文案的路径在客户端之外（服务端按客户端版本下发的提示文本 / 文本消息），或由未打进这 23 个 dex 的模块渲染。**【未验证】**
3. **客户端真正存在的「版本不支持」兜底链路已完整还原【已验证】**
   `messageVersion` 分段读取 → 字段不足/错位 → `IOException("structmsg_version_error")` → `mMsgException=true` → `getView()` 直接返回 `getVersionExceptionView()`（灰字气泡「当前版本导致消息展示异常，请升级QQ版本」）。这条链路与用户现象同源（同一个「版本不支持」判定族），是 8.8.50 上唯一可 hook 的客户端判定点。
4. **根因分类【已验证部分】**
   - ❌ 不是 `appid/app_type` 白名单未收录 —— `StructMsgFactory` / `StructMsgForGeneralShare` 里没有 appid 白名单分支，`appid` 只用于跳转与埋点
   - ❌ 不是 `layoutId` 资源缺失 —— 异常视图是**代码动态构造**的 `RelativeLayout + TextView`，不 inflate 任何 layout XML
   - ❌ 不是 `meta.news` 字段解析失败 —— `meta.news` 是 JSON 侧的展示字段，二进制 structmsg 走 `readExternal` 的字段序列，与 `meta.news` 无关
   - ✅ **是 `messageVersion` 版本比较 + 序列化字段布局不匹配**（客户端只认识 ≤ 自己支持版本号的分段，服务端按高版本布局下发 → 读取错位 → 版本异常标记）
   - ⚠️ 服务端下发模板缺失（serviceID 未注册 → `StructMsgParserHandler.a()` 返回 null）是并列可能，**未验证**（需要真实二进制 msgData 才能定 serviceID）

---

## 2. 判定链路图

```
服务端下发 msgData（structmsg 二进制：int serviceID + int messageVersion + 字段序列）
        │
        ▼
MessageForStructing.parse()
        │
        ▼
StructMsgFactory.a(QQAppInterface, int, long, byte[], int)      [cls_StructMsgFactory.txt:80]
        │  └─ 若返回 null → QLog "getStructMsgFromXmlBuff = null ,error happened"
        ▼
StructMsgFactory.a([BI)  = getStructMsgFromXmlBuffByStream      [cls_StructMsgFactory.txt:316]
        │  ① StructMsgUtils.a([BI) 解压
        │  ② new String(bytes,"utf-8")
        │  ③ StructMsgFactory.a(String) 过滤非法字符 —— 命中则 return null
        │  ④ SAXParser.parse(StructMsgParserHandler)
        │  ⑤ handler.a() → AbsStructMsg（按 serviceID 选 StructMsgForGeneralShare 等）
        ▼
AbsStructMsg.readExternal(ObjectInput)  /  AbsStructMsgItem.readExternal
        │  按 this.messageVersion 分段读字段：if (version >= 16) … 18 … 19 … 21 … 22 … 23 … 24 … 25 … 27 … 29
        │  任一段读取异常 → throw new IOException("structmsg_version_error")   [dex13.txt:1586798]
        ▼
StructMsgForGeneralShare.readExternal(ObjectInput)              [dex13.txt:1940955-1941014]
        │  catch (IOException e) {
        │      if ("structmsg_version_error".equals(e.getMessage()))
        │          this.mMsgException = true;          ← offset 0x0418
        │  }
        ▼
StructMsgForGeneralShare.getView(Context, View, OnLongClickAndTouchListener, Bundle)
        │  offset 0x00cc: iget-boolean v12, mMsgException
        │  offset 0x00ce: if-eqz v12, 0x0115     ← false 才走正常卡片渲染
        │  true → QLog "struct msg has version exception! uin:… msgVersion:… version:…"
        │       → getVersionExceptionView(ctx, convertView, listener, bundle)   ← offset 0x010a
        ▼
AbsStructMsg.getVersionExceptionView(...)                       [cls_AbsStructMsg.txt:960]
        │  const v0, #0x7f1228f6  = R.string.f94 = "当前版本导致消息展示异常，请升级QQ版本"
        ▼
AbsStructMsg.getExceptionView(..., int resId) → getString(resId) → getExceptionView(..., String)
        │  动态构造 RelativeLayout + TextView
        │  setText(str) / setTextSize(2, 16sp) / setTextColor("#777777")
        │  setBackgroundResource(0x7f020344)
        ▼
灰字异常气泡（用户看到的「升级 QQ」类提示）
```

并行分支（serviceID 不支持）：

```
StructMsgParserHandler.a() → AbsStructMsg
AbsStructMsg.getInCompatibleView(Context, View, String)          [cls_AbsStructMsg.txt:895]
        │  动态构造 RelativeLayout + TextView
        │  setText(str) / setTextSize(2, 16sp) / setTextColor("#000000")
        │  setBackgroundResource(0x7f020344) / padding 用 BaseChatItemLayout 静态值
        ▼
黑字「不兼容」气泡（str 由调用方传入，可传任意资源文案）
```

---

## 3. 证据明细（类 / 方法签名 / 片段）

### 3.1 文案资源

`analysis/res_dump.txt`（aapt2 dump resources）：

```
resource 0x7f120d19 string/wvb
  () "[暂不支持该消息类型，请升级至最新版本QQ查看]"
resource 0x7f1225f8 string/yl4
  () "[暂不支持该消息类型，请升级至最新版本QQ查看]"
```

字段名（`analysis/dex19.txt:176991`）：

```
    #3353              : (in Lcom/tencent/mobileqq/R$string;)
      name          : 'fs_message_unsupported_type'
      type          : 'I'
      access        : 0x0019 (PUBLIC STATIC FINAL)
      value         : 2131889433          (= 0x7f120d19)
```

相邻字段 `fs_message_bubble_tips` / `fs_message_page_empty_hint` / `fs_message_page_title` / `fs_personal_fan` 表明这组属于 QQ「消息」页（feed stream）文案，**不是 AIO 分享卡片专用**。

### 3.2 「无代码引用」的验证方法与结果

| 验证 | 命令/脚本 | 结果 |
|---|---|---|
| `const` 直接加载资源 id | `analysis/scan_const.py <dex> 0x7f120d19,0x7f1225f8` | `total: 0` |
| 同一脚本自检（已知被引用的 id） | `scan_const.py <dex> 0x7f120619` | `total: 464`（扫描器有效） |
| `sget` 读 R 字段 | `analysis/dexscan2.py`（`WANT={"fs_message_unsupported_type"}`） | `total hits: 0`；该 field 仅在 `classes19.dex` 定义 |
| 字符串是否进 dex | UTF-8 字节搜索 `暂不支持该消息类型` / `升级至最新版本QQ查看` | 23 个 dex 全 0 命中 |
| `R$string` 引用总量对照 | `grep -c 'R\$string' dex19.txt` | 29130 处（说明 R 字段引用普遍存在，本资源是例外） |

⇒ 该资源在 8.8.50 中是**未引用的存量资源**（QQ 每个版本都保留大量废弃资源）。

### 3.3 版本异常标记的产生

`AbsStructMsgItem.readExternal(ObjectInput)` 尾部（`analysis/dex13.txt:1586798`）：

```
1faa9c: 2208 c32f        |0274: new-instance v8, Ljava/io/IOException;
1faaa0: 1b00 ef0d 0100   |0276: const-string/jumbo v0, "structmsg_version_error"
1faaa6: 7020 f2e7 0800   |0279: invoke-direct {v8, v0}, Ljava/io/IOException;.<init>:(Ljava/lang/String;)V
1faaae: 2708             |027d: throw v8
```

`StructMsgForGeneralShare.readExternal` 的 catch（`analysis/dex13.txt:1941010`）：

```
209916: 1b01 ef0d 0100   |040f: const-string/jumbo v1, "structmsg_version_error"
20991c: 6e20 33e9 1b00   |0412: invoke-virtual {v11, v1}, Ljava/lang/String;.equals:(Ljava/lang/Object;)Z
209924: 380b 0400        |0416: if-eqz v11, 041a
209928: 5ca0 061b        |0418: iput-boolean v0, v10, Lcom/tencent/mobileqq/structmsg/StructMsgForGeneralShare;.mMsgException:Z
```

版本分段读取（同方法，`analysis/dex13.txt:1940880` 起，节选）：

```
20973c: 1302 1200   |0322: const/16 v2, #int 18
209740: 3521 0400   |0324: if-ge v1, v2, 0328      ; v1 = messageVersion
209760: 1302 1300   |0334: const/16 v2, #int 19
209784: 1302 1500   |0346: const/16 v2, #int 21
20979c: 1302 1600   |0352: const/16 v2, #int 22
2097b2: 1302 1700   |035d: const/16 v2, #int 23
2097c8: 1302 1800   |0368: const/16 v2, #int 24
209802: 1302 1900   |0385: const/16 v2, #int 25
209818: 1302 1b00   |0390: const/16 v2, #int 27
209844: 1302 1d00   |03a6: const/16 v2, #int 29
```

即：`messageVersion` 决定字段序列长度；服务端按更高版本布局下发时，8.8.50 只读到它认识的段，剩余字节使后续解析错位 → 抛 `structmsg_version_error`。

### 3.4 异常视图的渲染判定

`StructMsgForGeneralShare.getView(Context,View,OnLongClickAndTouchListener,Bundle)`（`analysis/dex13.txt:1939059`）：

```
205570: 550c 061b   |00cc: iget-boolean v12, v0, Lcom/tencent/mobileqq/structmsg/StructMsgForGeneralShare;.mMsgException:Z
205574: 380c 4700   |00ce: if-eqz v12, 0115      ; false → 正常渲染
205586: 1b07 c90d 0100 |00d7: const-string/jumbo v7, "struct msg has version exception! uin : "
2055a8: 5207 2b1b   |00e8: iget v7, v0, ...StructMsgForGeneralShare;.messageVersion:I
2055be: 5207 271b   |00f3: iget v7, v0, ...StructMsgForGeneralShare;.mVersion:I
2055ec: 7140 352a 2153 |010a: invoke-static {v1,v2,v3,v5}, ...StructMsgForGeneralShare;.getVersionExceptionView:(...)Landroid/view/View;
2055f4: 6e20 6406 9100 |010e: invoke-virtual {v1, v9}, Landroid/view/View;.setId:(I)V
2055fa: 6e30 7606 9100 |0111: invoke-virtual {v1, v9, v0}, Landroid/view/View;.setTag:(ILjava/lang/Object;)V
205600: 1101        |0114: return-object v1
```

### 3.5 异常视图的构造（代码动态构造，不 inflate XML）

`AbsStructMsg.getVersionExceptionView(...)`（`analysis/cls_AbsStructMsg.txt:960`）：

```
1fccdc: 1400 f628 127f  |0000: const v0, #7f1228f6     ; R.string.f94
1fcce2: 7150 2729 2143  |0003: invoke-static {v1,v2,v3,v4,v0}, ...AbsStructMsg;.getExceptionView:(...I)Landroid/view/View;
```

`AbsStructMsg.getExceptionView(...,int)` → `getString`（`cls_AbsStructMsg.txt:782`）：

```
1fcb20: 6e10 0c01 0100  |0000: invoke-virtual {v1}, Landroid/content/Context;.getResources:()
1fcb28: 6e20 9101 5000  |0004: invoke-virtual {v0, v5}, Landroid/content/res/Resources;.getString:(I)Ljava/lang/String;
```

`AbsStructMsg.getExceptionView(...,String)`（`cls_AbsStructMsg.txt:809`，节选）：

```
1fcb6e: 2203 0302  |0011: new-instance v3, Landroid/widget/RelativeLayout;
1fcb90: 2205 0c02  |0022: new-instance v5, Landroid/widget/TextView;
1fcb9a: 6e20 7b09 6500 |0027: invoke-virtual {v5, v6}, Landroid/widget/TextView;.setText:(Ljava/lang/CharSequence;)V
1fcba2: 1500 6041  |002b: const/high16 v0, #int 1096810496 // #4160  (16.0f)
1fcbac: 1b06 2509 0000 |0030: const-string/jumbo v6, "#777777"
1fcbe2: 1402 4403 027f |004b: const v2, #7f020344        ; 背景 drawable
1fcbfa: 1402 f951 0b7f |0057: const v2, #7f0b51f9        ; 视图 id
```

`AbsStructMsg.getInCompatibleView(Context,View,String)`（`cls_AbsStructMsg.txt:895`）：同构造，颜色 `#000000`（`1fcc78: const-string/jumbo v3, "#000000"`），padding 取自 `BaseChatItemLayout.n / .o`。

### 3.6 解析失败返回 null 的两条支路

`StructMsgFactory.a([BI)`（`cls_StructMsgFactory.txt:316`）：

```
200c12: 7110 d829 0400 |0047: invoke-static {v4}, ...StructMsgFactory;.b:(Ljava/lang/String;)Z
200c1a: 7130 e83e 9408 |004b: invoke-static {v4, v9, v8}, ...EmotcationConstants;.beforXml:(...)
200c22: 7110 d429 0400 |004f: invoke-static {v4}, ...StructMsgFactory;.a:(Ljava/lang/String;)Ljava/lang/String;   ← 非法字符返回 null
200c6e: 7020 83e7 4500 |0075: invoke-direct {v5, v4}, Ljava/io/ByteArrayInputStream;.<init>:([B)V
200c84: 6e20 f72a 8400 |0080: invoke-virtual {v4, v8}, ...StructMsgParserHandler;.a:(Z)V
200ca6: 6e10 f52a 0400 |0091: invoke-virtual {v4}, ...StructMsgParserHandler;.a:()Lcom/tencent/mobileqq/structmsg/AbsStructMsg;
200cca: 1108          |00a3: return-object v8
```

`StructMsgFactory.a(String)` 非法字符分支（`cls_StructMsgFactory.txt:640` 附近）：

```
1b0a 3d03 0000  |009b: const-string/jumbo v10, " ContainInvalidCharacter return null ,invalid char  = "
```

`StructMsgFactory.a([B)`（`cls_StructMsgFactory.txt:181-211`）按 serviceID 选类，**无 appid 白名单**：

```
2009f8: 3326 0900  |0016: if-ne v6, v2(2), 001f    → StructMsgForAudioShare
200a0a: 1234       |001f: const/4 v4, #3
200a0c: 3246 2900  |0020: if-eq v6, 3, 0049        → StructMsgForHypertext
200a10: 1304 5200  |0022: const/16 v4, #82
200a14: 3246 2500  |0024: if-eq v6, 82, 0049
200a18: 1304 7800  |0026: const/16 v4, #120
200a1c: 3346 0300  |0028: if-ne v6, 120, 002b
200a32: 1304 9600  |0033: const/16 v4, #150
200a3a: 2204 1d0d  |0037: new-instance v4, StructMsgSubImageVideo
200a46: 2204 fe0c  |003d: new-instance v4, StructMsgForGeneralShare
200a52: 2204 090d  |0043: new-instance v4, StructMsgForImageShare
```

### 3.7 消息类索引位置（供后续单类反编译）

| 类 | dex | 备注 |
|---|---|---|
| `com.tencent.mobileqq.structmsg.StructMsgFactory` | classes13.dex | `analysis/dex13.txt` |
| `com.tencent.mobileqq.structmsg.AbsStructMsg` | classes13.dex | `analysis/cls_AbsStructMsg.txt` |
| `com.tencent.mobileqq.structmsg.StructMsgForGeneralShare` | classes13.dex | `dex13.txt` |
| `com.tencent.mobileqq.activity.aio.item.StructingMsgItemBuilder` | classes8.dex | `analysis/cls_StructingMsgItemBuilder.txt`（7082 行） |
| `com.tencent.mobileqq.data.MessageForStructing` | classes9.dex | — |

---

## 4. Hook 方案

### 4.1 目标

让 8.8.50 忽略「版本异常」标记，走 `StructMsgForGeneralShare` 的**通用分享卡片**正常渲染路径（`getView` 的 `0x0115` 分支），而不是返回 `getVersionExceptionView()` 的灰字气泡。

### 4.2 Hook 点（按优先级）

| # | 类 | 方法签名 | 时机 | 动作 |
|---|---|---|---|---|
| **A（主）** | `com.tencent.mobileqq.structmsg.StructMsgForGeneralShare` | `readExternal(Ljava/io/ObjectInput;)V` | after | 反射 `setBoolean(mMsgException,false)`，吞掉 `structmsg_version_error` 的结果 |
| **B（主）** | 同上 | `getView(Landroid/content/Context;Landroid/view/View;Lcom/tencent/mobileqq/activity/aio/OnLongClickAndTouchListener;Landroid/os/Bundle;)Landroid/view/View;` | before | 再清一次 `mMsgException`（对象可能来自 DB/缓存反序列化，未再走 `readExternal`） |
| C（兜底） | `com.tencent.mobileqq.structmsg.AbsStructMsg` | `getVersionExceptionView(...)` | before | 不建议返回 null（`getView` 会对返回值 `setId/setTag`，NPE） |
| D（兜底·解析失败） | `com.tencent.mobileqq.structmsg.StructMsgFactory` | `a([BI)Lcom/tencent/mobileqq/structmsg/AbsStructMsg;` | after | 若返回 null，用 `StructMsgFactory.a()Lcom/tencent/mobileqq/structmsg/StructMsgForGeneralShare;` 造空实例并回填 `mMsgServiceID` / `mMsgUrl` / `mMsgActionData`（需自己从 XML 解，成本高，仅在前 2 个 hook 无效时启用） |
| E（serviceID 未注册时） | `com.tencent.mobileqq.structmsg.StructMsgItemLayout` 的 serviceID→layoutId 映射 | — | — | 8.8.50 中未找到该类（`dexwhere.py` 无命中），若确认为此路径需先定位新类名 |

**推荐组合：A + B**。两者都只改一个 boolean 字段，不改数据流，最稳。

### 4.3 为什么这样改能生效

`getView` 的判定是唯一开关（`0x00cc → 0x00ce`）：

```java
if (this.mMsgException) {
    QLog.d(TAG, "struct msg has version exception! uin:" + uin + " msgVersion:" + messageVersion + " version:" + mVersion);
    View v = getVersionExceptionView(context, convertView, listener, bundle);
    v.setId(someId); v.setTag(someId, this);
    return v;
}
// ↓ 0x0115 起：正常渲染（StructMsgItemViewFactory / 通用卡片）
```

把 `mMsgException` 置 false 后，8.8.50 会用它**已成功读到的字段**（`mMsgServiceID`、`mMsgTitle`、`mMsgBrief`、`mMsgUrl`、`mMsgActionData`、`mItems` 等）走 `StructMsgForGeneralShare` 通用卡片渲染 —— 这正是「通用分享卡片」路径，与高版本显示一致（前提是 serviceID 对应的 layout 在 8.8.50 存在）。

### 4.4 QAuxiliary 风格 Kotlin 草稿

> 仅作报告草稿，未写入 `source/`。

```kotlin
/*
 * QAuxiliary - An Xposed module for QQ/TIM
 * 草稿：强制渲染版本异常的通用分享卡片（QQ 8.8.50 验证）
 */
package cc.ioctl.hook.msg

import io.github.qauxv.base.annotation.FunctionHookEntry
import io.github.qauxv.base.annotation.UiItemAgentEntry
import io.github.qauxv.hook.CommonSwitchFunctionHook
import io.github.qauxv.util.Initiator
import io.github.qauxv.util.Log
import io.github.qauxv.util.xpcompat.XC_MethodHook
import io.github.qauxv.util.xpcompat.XposedBridge
import java.lang.reflect.Field

@FunctionHookEntry
@UiItemAgentEntry
object ForceRenderStructMsgVersionException :
    CommonSwitchFunctionHook("ForceRenderStructMsgVersionException") {

    override val name = "强制渲染版本异常的分享卡片"
    override val description =
        "忽略 structmsg_version_error 标记，用已解析字段走 StructMsgForGeneralShare 通用卡片渲染"
    override val isAvailable: Boolean
        get() = Initiator.loadClassOrNull(CLS_GENERAL_SHARE) != null

    private const val TAG = "ForceRenderStructMsg"
    private const val CLS_GENERAL_SHARE = "com.tencent.mobileqq.structmsg.StructMsgForGeneralShare"
    private const val FIELD_EXCEPTION = "mMsgException"

    /** 沿继承链找 mMsgException（AbsShareMsg / AbsStructMsg 各版本字段归属不同） */
    private fun findExceptionField(cls: Class<*>): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                return c.getDeclaredField(FIELD_EXCEPTION).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun clearExceptionFlag(obj: Any?) {
        if (obj == null) return
        try {
            val f = findExceptionField(obj.javaClass) ?: return
            if (f.getBoolean(obj)) {
                f.setBoolean(obj, false)
                Log.d(TAG, "cleared mMsgException on ${obj.javaClass.name}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "clear mMsgException failed", t)
        }
    }

    override fun onEnable() {
        val cls = Initiator.loadClass(CLS_GENERAL_SHARE)

        // A) 解析阶段：吞掉 structmsg_version_error 造成的标记
        XposedBridge.hookAllMethods(cls, "readExternal", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                clearExceptionFlag(param.thisObject)
            }
        })

        // B) 渲染阶段：对象可能来自 DB / 缓存，未再走 readExternal
        XposedBridge.hookAllMethods(cls, "getView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                clearExceptionFlag(param.thisObject)
            }
        })
    }
}
```

对应 `DexKit` 目标（若不想硬编码类名，可加一个 `DexKitTarget` 按字符串 `"struct msg has version exception! uin : "` 定位 `StructMsgForGeneralShare`）：

```kotlin
object StructMsgForGeneralShareClass : DexKitTarget() {
    override val targetType: TargetType = TargetType.CLASSES
    override val findMethod: FindMethod = FindMethod.METHOD_STRING
    override val str: String = "struct msg has version exception! uin : "
}
```

### 4.5 验证步骤（真机）

1. 装 QAuxiliary + 开启该功能，冷启动 QQ 8.8.50。
2. 接收样本消息（小黑盒 `com.tencent.tuwen.lua`）。
3. `logcat | grep -E "ForceRenderStructMsg|struct msg has version exception"`：
   - 出现 `cleared mMsgException on ...StructMsgForGeneralShare` → hook 命中
   - 若同时仍看到 `struct msg has version exception` 日志但气泡正常 → B 生效
4. 视觉检查：卡片应显示 title / preview 图 / `tag=小黑盒` / `tagIcon`；若只显示空壳，说明字段确实缺失（见风险 1）。
5. 回滚：关闭开关即恢复原行为（只改运行时字段，无持久化副作用）。

---

## 5. 风险与未验证项

### 5.1 风险

1. **字段缺失导致卡片残缺**：`mMsgException` 的成因是「读取错位/字段不足」。忽略标记后，`mMsgTitle` / `mMsgBrief` / `mItems` 可能为空或错位，卡片可能只显示一个空壳或部分内容。**这是最可能的结果**，需真机确认残缺程度。
2. **serviceID 对应的 layout 在 8.8.50 不存在**：此时 `StructMsgForGeneralShare.getView` 走正常分支后仍可能 fallback 到 `getInCompatibleView`（黑字气泡），hook A/B 无效，需走 D/E。
3. **`mMsgException` 在 8.8.50 有多个赋值点**：`dex13.txt` 中 `iput-boolean ... mMsgException` 共 11 处，其中 `AbsShareMsg` 的 3 处（`1583222 / 1583295 / 1583791`）经核对是**构造函数默认值初始化**（`v1=0`，即 false），真正置 true 的是：
   - `StructMsgForGeneralShare.readExternal` catch 分支 `0x0418`（`1941014`，`structmsg_version_error`）
   - `StructMsgForGeneralShare.readExternal` 内 `0x03b2`（`1940963`），由 `0x00c8: if-lt v1, v3, 03b2` 分支跳入（版本号比较的另一条置位路径，比较对象待确认）
   - `StructMsgForAudioShare`（`1934910 / 1934922`）、`StructMsgForImageShare`（`1943813 / 1943825`）同构 catch
   
   hook 只清字段、不拦截赋值，若某处在 `getView` **之后**再置 true（缓存回写 / 复解析），下次渲染仍异常 —— 所以必须保留 B（getView before）。音频/图片分享卡片走各自的 `readExternal`，如需覆盖应把 hook 扩展到 `AbsShareMsg` 全部子类（`hookAllMethods` 按类名逐个挂）。
4. **`readExternal` 可能被 `MessageForStructing` 缓存对象复用**：QQ 会把 structmsg 二进制存 DB，反序列化走 `StructMsgFactory.a([B)`（`readInt` + `readExternal`）。hook A 覆盖该路径；若某路径用 `StructMsgFactory.a(Bundle)` 构造，则不会经过 `readExternal`，此时 B 兜底。

### 5.2 未验证项

| 项 | 原因 | 建议验证方式 |
|---|---|---|
| 「暂不支持该消息类型，请升级至最新版本QQ查看」在 8.8.50 的具体显示路径 | 该文案资源在 23 个 dex 中零引用，也无中文字面量 | 抓包看服务端是否直接下发该文本（`msgtype=82` / tips）；或在真机 dump 该气泡的 `TextView.getText()` 来源 |
| 样本消息的 `serviceID` / `messageVersion` | 样本只给了 JSON（`com.tencent.tuwen.lua`），未给二进制 msgData | 从 QQ DB（`nt_msg.db` / `msg.db`）取 `msgData`，前 8 字节即 `serviceID` + `messageVersion` |
| `StructMsgItemLayout` 的 serviceID→layout 映射 | `dexwhere.py` 在 23 dex 中无该类命中（8.8.50 已重构） | 用 `dexscan.py` 扫 `StructMsgItemViewFactory` 相关类，或 DexKit 按 layout 常量定位 |
| `com.tencent.tuwen.lua` 在 8.8.50 是否走 Ark 而非 structmsg | 未定位到该 app 名的处理类 | `dexwhere.py "tuwen"` / 扫 `ArkAppConfig` 白名单表 |
| 服务端是否按 `ver` 字段（样本 `"ver":"0.0.0.1"`）做灰度 | 无服务端侧证据 | 抓包对比高版本/8.8.50 的 `msgData` 字节差异 |

---

## 6. 复现材料（本次分析产物）

| 文件 | 内容 |
|---|---|
| `analysis/res_dump.txt` | aapt2 全量资源（6.4 MB），含 `0x7f120d19` / `0x7f1225f8` / `0x7f1228f6` |
| `analysis/dex4.txt` / `dex8.txt` / `dex13.txt` / `dex19.txt` | dexdump 反汇编（classes4/8/13/19） |
| `analysis/cls_StructMsgFactory.txt` | `StructMsgFactory` 单类 smali（871 行） |
| `analysis/cls_AbsStructMsg.txt` | `AbsStructMsg` 单类 smali（1710 行） |
| `analysis/cls_StructingMsgItemBuilder.txt` | `StructingMsgItemBuilder` 单类 smali（7082 行） |
| `analysis/scan_const.py` | 扫 `const` 加载指定 32 位常量 → 定位引用方法 |
| `analysis/dexscan2.py` | 扫 `sget*` 引用指定 R 字段 → 定位引用方法 |
| `analysis/dexwhere.py` | 类名 → dex 文件映射 |
| `analysis/dexscan3.py` | R 类 `static_values` → 资源 id 映射（解析器待修，未产出） |
| `analysis/jadxout/sources/.../StructingMsgItemBuilder.java` | jadx 单类反编译结果 |

复现命令：

```bash
# 资源定位
aapt2 dump resources QQ_8.8.50.apk > res_dump.txt
grep -n "暂不支持该消息类型" res_dump.txt

# 资源 id → R 字段名（dexdump 会打印 static value 的十进制）
dexdump -d classes19.dex > dex19.txt
grep -n "2131889433" dex19.txt      # 0x7f120d19

# 常量引用扫描（自检用 0x7f120619，应为 464 命中）
python scan_const.py dex/ 0x7f120d19,0x7f1225f8

# 单类 jadx
pwsh -File jd.ps1 com.tencent.mobileqq.structmsg.StructMsgFactory
```

---

## 7. 一句话给决策层

8.8.50 的「版本不支持」是**服务端按客户端版本下发的 structmsg 布局与本地 `messageVersion` 分段解析不匹配**造成的，客户端侧的开关就是 `StructMsgForGeneralShare.mMsgException`；把它在 `readExternal` 之后和 `getView` 之前清掉，即可让 8.8.50 用它已解析到的字段走通用分享卡片渲染。而用户看到的那句「暂不支持该消息类型」文案在 8.8.50 客户端里是**死资源**（零引用），真正显示它的是客户端之外的路径，需抓包确认。

---

## 6. 实机验证与修正（2026-11-09，QQ 8.8.50 / versionCode 2324）

上一轮（§1–§7）把这条消息当成 **structmsg**，并给出 `mMsgException` hook。实机反馈「无效」。本节逐条纠正。

### 6.1 实机日志能证明什么、不能证明什么

`logs/full.log`（54 MB，`01-25 00:16` → `11-09 13:12`）：

| 检索 | 结果 |
|---|---|
| `rg -a "QAuxv"` | **0 条**（QAuxiliary 的 `Log.*` 全部 tag=QAuxv，一条都没进 logcat） |
| `structmsg_version_error` / `getVersionExceptionView` / `当前版本导致消息展示异常` | **0 条** |
| `StructMsg|structmsg` | **2 条**，都是 MIUI `DragSharePoC` 捕获的功能描述文本（`清掉 StructMsgForGeneralShare 的 mMsgException 标记…`），**不是业务日志** |
| `com.tencent.mobileqq` | 21149 条，全是 `wm_on_*` / `input_focus` / `am_cpu` 等系统窗口日志 |
| LSPosed 作用域 `db_scope.txt:457-459` | `app_pkg_name=com.tencent.mobileqq` + `module_pkg_name=io.github.qauxv` → **作用域正常，模块已挂** |

结论：**这份 logcat 不含 QQ 业务日志**（QQ 的 QLog 写文件，不进 logcat），无法用它证明链路走向；但「零条版本异常字样」与「该链路根本没被触发」一致。**不能**拿日志当 hook 生效/失效的证据。

### 6.2 上一轮错在哪（三条假设全部不成立）

**❌ 假设 1：这条消息是 structmsg**

样本 JSON 的顶层结构是 `{"app":"com.tencent.tuwen.lua","view":"news","meta":{"news":{…}},"config":{…}}` —— 这是 **Ark 消息**的标准结构（`app` / `view` / `meta` / `config` / `prompt`），对应 `MessageForArkApp`（classes9.dex）+ `ArkAppItemBubbleBuilder`（classes8.dex）。

structmsg 是 XML（`<msg serviceID="…" templateID="…" action="…">`），字段是 `mMsgServiceID` / `mMsgUrl` / `mItems`，与样本 JSON 完全不同。上一轮把「有 `app`/`view` 字段」误读成 structmsg 的 XML 属性。

旁证：
- `com.tencent.tuwen.lua` 在全部 23 个 dex 中 **0 命中**（`grep -c -a -F` 逐文件验证）
- `ArkAppItemBubbleBuilder` 里存在 `forward_ark_app_name` / `forward_ark_app_view` / `forward_ark_app_ver` / `forward_ark_app_compat` 等转发 key，确认 Ark 链路在 8.8.50 是活的

**❌ 假设 2：`mMsgException` 会在这条消息上被置 true**

8.8.50 里 `mMsgException` 的写入点（`dexdump` 全量 grep，共 11 处 `iput-boolean … mMsgException`）：

| 位置 | 方法 | 写入值 | 说明 |
|---|---|---|---|
| `dex13.txt:1583222` | `AbsShareMsg.<init>()V` | `v1 = 0` | 构造初始化 false |
| `dex13.txt:1583295` | `AbsShareMsg.<init>(Bundle)V` | `v3 = 0` | 构造初始化 false |
| `dex13.txt:1583791` | `AbsShareMsg.<init>(StructMsgNode)V` | `v1 = 0` | **XML 路径的构造函数，也是 false** |
| `dex13.txt:1940963` | `StructMsgForGeneralShare.readExternal` `0x03b2` | 由 `0x00c8: if-lt v1, v3` 跳入 | 二进制路径 |
| `dex13.txt:1941014` | `StructMsgForGeneralShare.readExternal` `0x0418` | catch `structmsg_version_error` | 二进制路径 |
| `dex13.txt:1934910/1934922` | `StructMsgForAudioShare.readExternal` | catch | 二进制路径 |
| `dex13.txt:1943813/1943825` | `StructMsgForImageShare.readExternal` | catch | 二进制路径 |

关键：**8.8.50 的 `StructMsgFactory.a(QQAppInterface,IJ[BI)` 走的是 `a([BI)`（`getStructMsgFromXmlBuffByStream`，XML + SAX），不经过 `readExternal`**：

```
cls_StructMsgFactory.txt:82
200dc8: 7702 d129 1800  |0002: invoke-static/range {v24, v25}, …StructMsgFactory;.a:([BI)L…AbsStructMsg;
```

`readExternal` 只属于 `StructMsgFactory.a([B)`（`ObjectInputStream.readInt()` 二进制反序列化）那条支路。XML 路径下 `mMsgException` 恒为构造时的 `false`，**hook 改它等于空操作**——这正是「hook 挂上但完全无效且不报错」的原因。

**❌ 假设 3：`getInCompatibleView` 是「暂不支持」的渲染点**

`grep -n getInCompatibleView dex13.txt` 只有两处：字段名表（`1119099`）和定义（`1119107`），**零调用者**，是死代码。

### 6.3 正确方向（Ark 链路，未完全定位）

```
MessageForArkApp (classes9.dex)
   → ArkAppItemBubbleBuilder (classes8.dex, 2084 行 smali)
   → com.tencent.ark.* 容器 (ArkAppView / ArkViewImplement)
   → 渲染失败 fallback
```

`ArkAppItemBubbleBuilder` 已确认的方法：
- `a(ChatMessage,ViewHolder,View,BaseChatItemLayout,OnLongClickAndTouchListener)View` — getView
- `b(MessageForArkApp)V`（281 insns）— 含两处 `Context.getString`，但查证是**发送状态文案**，不是「暂不支持」
- `a(Context,MessageForArkApp)V` / `b(Context,MessageForArkApp)V` — 加载/重试

**未验证**：8.8.50 显示「暂不支持该消息类型，请升级至最新版本QQ查看」的确切位置。已知的两个候选资源（`0x7f120d19` / `0x7f1225f8`）在 23 个 dex 中零引用，所以文案大概率仍是**服务端下发**，客户端只做文本气泡展示。

### 6.4 本轮代码修正

`source/app/src/main/java/io/github/qauxv/hook/experimental/ForceShowHighVersionStructMsg.kt`：

1. **字段定位改为确定性**：只按名 `mMsgException` 沿继承链精确取（该字段名在 8.8.50 smali 里是明文，r8 未改名），**删除**「遍历所有 boolean 字段、优先名字含 exception/error、只剩一个就用」的猜测逻辑 —— 该继承链上有 `mHasSource` / `mHasAdSource` / `mMsgException` 等多个 boolean，猜错会静默改错字段。
2. 保留 `readExternal` after + `getView` before 双 hook（对**二进制 structmsg 路径**仍然正确且无害）。
3. 明确边界：**该 hook 不覆盖 Ark 消息**，对本例（`com.tencent.tuwen.lua`）无效。

### 6.5 下一步（按性价比排序）

1. **取真机 QLog**：`/sdcard/Android/data/com.tencent.mobileqq/files/QLog/` 或 `Tencent/msflogs/com/tencent/mobileqq/`，搜 `StructMsg` / `ArkApp` / `ark` 关键字，确认这条消息落到哪个类。
2. **抓 `msgData`**：从 DB 取该消息的 `msgData`，看前 4 字节 msgtype 是 `-5008`（Ark）还是 `-2011`（structmsg），一锤定音。
3. 若确认 Ark：hook 点应放在 `ArkAppItemBubbleBuilder` 的 getView 入口，或在 `MessageForArkApp` 层把 `ark_app_message.appName` 替换为 8.8.50 已支持的内置 app；最差兜底是把气泡降级为文本显示 `prompt`（`[分享]《卡莉亚的炼金工房》…`），至少内容可见。

---

## 7. Ark 链路（2026-11-09 续）

样本：`{"app":"com.tencent.tuwen.lua","view":"news","ver":"0.0.0.1","prompt":"[分享]《卡莉亚的炼金工房》…","meta":{"news":{"app_type":1,"appid":1105910806,"title":"…","desc":"…","preview":"…","tag":"小黑盒","jumpUrl":"https://api.xiaoheihe.cn/…"}},"config":{"type":"normal","forward":1}}`

类位置：`MessageForArkApp` → classes9.dex；`ArkAppItemBubbleBuilder` / `ArkAioContainerWrapper` / `ArkAppContainer` → classes8.dex（反汇编见 `analysis/dex8.txt`、`analysis/cls_ArkAppItemBubbleBuilder.txt`）。

### 7.1 问题 1：`ArkAppItemBubbleBuilder` 拿到这条消息后走哪条分支

**结论：走正常 `ArkAppView` 渲染，Java 层没有任何「暂不支持」的判定分支。**

`ArkAppItemBubbleBuilder.a(ChatMessage,ViewHolder,View,BaseChatItemLayout,OnLongClickAndTouchListener)View`（`cls_ArkAppItemBubbleBuilder.txt:1059`，dex 偏移 `188f6c`）的执行序列：

```
188fa8 |0016: iget-object v5, v6, MessageForArkApp;.ark_app_message:Lcom/tencent/mobileqq/data/ArkAppMessage;
188fb0 |001a: invoke-virtual {v4, v5}, ArkAppMessage$Config;.fromString:(Ljava/lang/String;)Z   ← 解析 config
188fcc |0028: const v10, #7f0e01f4                                                             ← inflate layout
188fe2 |0033: findViewById → check-cast ArkAppView      → Holder.c
188ff8 |003e: findViewById → check-cast LinearLayout    → Holder.d
18900e |0049: findViewById → check-cast ImageView       → Holder.e
189024 |0054: findViewById → check-cast TextView        → Holder.f
189044 |0064: findViewById → check-cast ArkAppLoadLayout → Holder.b
1890d0 |00aa: ArkTipsManager.b() / b(J)Z                                                      ← 提示管理
189104 |00c4: new-instance ArkAioContainerWrapper
18911a |00cf: invoke-virtual {v9, v10}, ArkAioContainerWrapper;.a:(L…SessionInfo;)V
18917a |00ff: iget appName
18917e |0101: iget appView
189186 |0105: iget appMinVersion
18918e |0109: iget metaList
1891b2 |011b: invoke-virtual/range {v9,v10,v11,v12,v13,v14,v15,v16},
              ArkAioContainerWrapper;.a:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;
                                        ;Ljava/lang/String;FL…MessageForArkApp;L…SessionInfo;)Z
1891b8 |011e: new-instance ArkAppItemBubbleBuilder$1      ← 注意：无 move-result，返回值被丢弃
1891c6 |0125: ArkAppView;.setLoadCallback:(Lcom/tencent/ark/ArkViewImplement$LoadCallback;)V
1891fe |0141: MessageForArkApp;.isRectangleBorder / setBorderType / setAlignLeft / setOnTouchListener
1892fe |01c1: return-object v9
```

**关键点**：`0x011b` 调用的 `ArkAioContainerWrapper.a(...)` 返回 `Z`，但下一条直接 `new-instance`，**没有 `move-result`** —— 初始化失败的结果被彻底丢弃，`getView` 照样返回那个 `ArkAppView` 容器（内容为空）。

真正判定在 `ArkAioContainerWrapper.a(...)`（`dex8.txt:1800177`，偏移 `186d50`）：

```
186d90 |0018: invoke-super/range {…}, ArkAppContainer;.a:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;
                                          ;Ljava/lang/String;FL…SessionInfo;)Z
186d96 |001b: move-result v9
186d9a |001d: if-nez v9, 0038                       ← super.init 返回 false
186db4 |002a: const-string/jumbo v11, "super.init return false!!! wrapper: %h."
186dc2 |0031: const-string/jumbo v11, "ArkApp.ArkAioContainerWrapper"
186dce |0037: return v0                            ← 返回 false，只打一条 QLog
186dee |0047: return v10                           ← 成功返回 true
```

→ **app 初始化失败时，Java 层唯一的反应是 `QLog.d("ArkApp.ArkAioContainerWrapper", 4, "super.init return false!!! wrapper: %h.")`，不抛异常、不改 View、不显示任何提示。** 后续「暂不支持」的显示发生在 native `libark` 容器内部（`ArkAppView` 的 loadCallback 里），Java 侧拿不到分支。

**这条 QLog 就是排查用的锚点**：真机 QLog 里若出现 `super.init return false`，即确认是 Ark app 包初始化失败（app 不在本地 / 版本不满足）。

### 7.2 问题 2：`app` 名如何决定能否渲染，`com.tencent.tuwen.lua` 本地有没有

**结论：8.8.50 不内置该 app 的任何配置，完全依赖 `ArkAppMgr` 运行时下发/下载。**

- `tar -tf apks/QQ_8.8.50.apk | grep -i ark` 共 83 条，**全部是误匹配**（`benchmark/*.json`、`*.geojson`、`*_dark_icon.png`、`mapcfg_park.png`）。真实的 ark 资源只有两个 layout：`r/g/ark.xml`、`r/i/ark.xml`，以及 `r/k/ark_app_manager_panel_icon.png`。**assets 里没有任何 ark app 配置 json**。
- `com.tencent.tuwen.lua` 在全部 23 个 dex 中 **0 命中**（逐文件 `grep -c -a -F`）。
- `ArkAppItemBubbleBuilder` 里只有三个硬编码特例：`com.tencent.troopapp`、`com.tencent.test.troopapp`、`com.tencent.giftmall.giftark`（`cls_ArkAppItemBubbleBuilder.txt` 字符串表），没有 `tuwen.lua`。

`ArkAppMessage`（classes9.dex，22 个实例字段，用自写 dex 解析器 `analysis/fld.py` 导出）：

```
appDesc, appId, appMinVersion, appName, appView, compatibleText, config,
containStructMsg, forwardID, from, isShow, mAppList, mExtra, mSourceActionData,
mSourceAd, mSourceName, mSourceUrl, mSource_A_ActionData, mText, metaList,
promptText, uniSeq
```

→ `appName` / `appView` / `appMinVersion` / `metaList` 全部原样传给 `ArkAioContainerWrapper.a(...)`，由 Ark 容器去本地缓存目录（`ArkAppMgr.getAppPathFromLocal`）找 app 包；找不到就 `super.init` 返回 false。

### 7.3 问题 3：降级路径的文本从哪来，能不能塞 jumpUrl

**结论：气泡文本用的是 `MessageForArkApp.msg`（消息摘要），不是 `promptText` 现场拼接；`promptText` / `compatibleText` 是另外两个字段。**

`getView` 里的绑定（`cls_ArkAppItemBubbleBuilder.txt:1201-1202`）：

```
1891d2 |012b: iget-object v3, v6, MessageForArkApp;.msg:Ljava/lang/String;        ← 消息摘要
1891d6 |012d: iput-object v3, v5, ArkAppItemBubbleBuilder$Holder;.a:Ljava/lang/String;
```

`ArkAppMessage` 里另有：
- `promptText`（field@93b9）—— 分享提示 `[分享]《卡莉亚的炼金工房》…`，被 `dex8.txt:1560481 (18a3e8)`、`1561741 (1897e2)`、`1561756 (189824)` 读取
- `compatibleText`（field@93ae）—— 不兼容提示，被 `dex8.txt:1560493 (18a424)` 读取

**可以塞 jumpUrl**：`metaList` 是 `{"news":{"jumpUrl":"https://api.xiaoheihe.cn/…"}}` 形式的 JSON 字符串，Java 层可直接解析第一个 view 对象的 `jumpUrl`；兜底还有 `mSourceUrl` 字段。点击用标准 `Intent.ACTION_VIEW` 即可，无需 Ark 容器参与。

### 7.4 问题 4：`meta.news` 这种旧格式 8.8.50 是否还认

**结论：Java 层不解析 `view` 类型，`news` 是否被支持由 native libark 决定，Java 侧无法判定（未验证）。**

证据：`getView` 只做 `iget appView` / `iget metaList` 然后整串透传：

```
18917e |0101: iget-object v11, v11, ArkAppMessage;.appView:Ljava/lang/String;
18918e |0109: iget-object v14, v14, ArkAppMessage;.metaList:Ljava/lang/String;
1891b2 |011b: invoke-virtual/range {…}, ArkAioContainerWrapper;.a:(appName, appView, appMinVersion, metaList, density, msg, session)Z
```

Java 侧没有 `ArkAppXmlParser`、没有 view 白名单、没有 `"news"` 字面量（`cls_ArkAppItemBubbleBuilder.txt` 字符串表里没有 `news`）。`view:"news"` + `meta.news` 的解析全部在 `libark` 的 C++ 侧。

→ 8.8.50 是否认 `news` 这个旧图文格式，**只能靠真机验证**（看 Ark 容器是否渲染出内容），静态分析到 Java 层为止。

### 7.5 方案选择

| 方案 | 可行性 | 理由 |
|---|---|---|
| **A：未知 app 走通用图文卡片** | ❌ 不可行 | Java 层没有「未知 app → 通用卡片」的兜底分支；渲染完全在 native `libark`，`ArkAppContainer.a(...)` 返回 false 后 Java 侧不做任何补救（返回值被 `getView` 丢弃）。要改只能 patch native，超出模块能力 |
| **B：降级为可点击文本** | ✅ 已实现 | `ArkAppItemBubbleBuilder.getView` 是 Java 方法，`hookBefore` 设 `param.result` 即可跳过整个 Ark 渲染，用 `promptText`/`mText`/`compatibleText` + `metaList.jumpUrl` 拼 TextView |

**选定方案 B**，代码：`source/app/src/main/java/io/github/qauxv/hook/experimental/ArkFallbackToText.kt`

实现要点：
1. hook `ArkAppItemBubbleBuilder` 里「5 个参数 + 返回 View」的方法（`getView` 被混淆成 `a`，用签名特征定位，不硬编码方法名）。
2. `hookBefore` 里从 `param.args[0]`（`MessageForArkApp`）取 `ark_app_message`，再读 `promptText` → `mText` → `compatibleText` 依次兜底。
3. `param.args[3]`（`BaseChatItemLayout`，是 ViewGroup）取 `Context`，构造 `TextView` 并 `param.result = textView` —— 原方法不再执行，Ark 容器不初始化，避免 native 侧空渲染。
4. 链接：`metaList` 解析第一个 view 对象的 `jumpUrl`，兜底 `mSourceUrl`；点击 `Intent.ACTION_VIEW` 打开。
5. 字段全部按名反射读取（`ark_app_message` / `promptText` / `mText` / `compatibleText` / `metaList` / `mSourceUrl`），不猜类型。

**代价**：开启后**所有** Ark 消息都变文本气泡（不只是这条），所以默认关闭、描述里写明。若要只对 `com.tencent.tuwen.lua` 生效，在 `replaceWithText` 里加一行 `if (appName != "com.tencent.tuwen.lua") return` 即可。

### 7.6 未验证项

1. 8.8.50 的 native `libark` 是否支持 `view:"news"` + `meta.news`（需真机 QLog 里看 `super.init return false` 是否出现）。
2. 该消息的 `msgtype` 是否真是 `-5008`（Ark）—— 需从 DB 取 `msgData` 前 4 字节确认。
3. `ArkTipsManager`（`getView` 的 `0x00aa` 处调用）是否参与「暂不支持」提示 —— 未展开。
