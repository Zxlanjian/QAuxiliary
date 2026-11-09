# QQSettingMe 侧滑栏家族 × QQ 8.8.50(2324) 修复方案

> 目标客户端：QQ 8.8.50，versionCode 2324
> 源码：`D:\DSH-Builds\QAuxiliaryFix\source`（main 分支，含前几轮修复）
> 证据索引：`analysis/methods_8.8.50.txt`（1 122 776 条签名）、`apks/dex/`（23 个 dex）、
> `analysis/jadx_qv`、`analysis/jadx_sm`（jadx 1.5.1 反编译产物）、`references/hooks/`（实机堆栈）

## 0. 通用根因模型

三个 `NoSuchMethodException: DexTarget: XXX` 全部来自同一条链路：

```
BaseFunctionHook.initialize()            (BaseFunctionHook.kt:64)
  → initOnce()
    → DexKit.requireMethodFromCache(t)   (DexKit.kt:135-137)
      → loadMethodFromCache(t) == null   → throw NoSuchMethodException("DexTarget: " + t.name)
```

`loadMethodFromCache` 为空只有两种原因：**该 target 的 DexKit 预扫描 0 命中**，或**预扫描根本没跑**。
四个目标逐个验证后，全部属于「特征串/类名在 8.8.50 里不存在 → 0 命中」。
`SimplifyQQSettingMe2` 是另一个异常族（`Initiator.loadClass` 抛 `ClassNotFoundException`），单独处理。

---

## 1. 侧滑栏精简 — `cc/ioctl/hook/sideswipe/SimplifyQQSettingMe.kt`

### 根因

不是「DexKit 在旧客户端上匹配失败但类还在」，而是**该类在 8.8.50 整包不存在**。
`QQSettingMeABTestHelper` 是 8.9.90+ 引入的 AB 开关 helper（9.x 的 V9/ZPlan 侧滑栏配套），
8.8.50 的侧滑栏实现里没有任何等价物。

### 证据

| 检查 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| target 定义 | `DexKitTarget.kt:941-946` | `UsingStringVector`，特征串 `["isZplanExpGroup: ", "QQSettingMeABTestHelper"]`，`findMethod = true` |
| 类是否存在 | `rg QQSettingMeABTestHelper analysis/methods_8.8.50.txt` | **0 命中** |
| 特征串是否存在 | `rg -a "isZplanExpGroup" apks/dex/` | **0 命中** |
| 同家族 target | `DexKitTarget.kt:897-918`（`QQ_SETTING_ME_CONFIG_CLASS`） | 特征串 `"超级QQ秀"`、`"财富小金库"` 在 23 个 dex 中 **0 命中** → 该 target 在 8.8.50 同样匹配不到 |
| 实机堆栈 | `references/hooks/SimplifyQQSettingMe-1794195196749.txt` | `DexKit.requireMethodFromCache(DexKit.kt:136)` ← `SimplifyQQSettingMe.kt:357` |

堆栈定位到 357 行，正是「下拉形象展示」分支里的
`DexKit.requireMethodFromCache(QQSettingMeABTestHelper_isZplanExpGroup_Old)`。

### 结论

**能修（主功能）；子项「下拉形象展示」不可修。**

- 主功能（隐藏侧滑栏条目）在 8.8.50 走的是 `com.tencent.mobileqq.activity.QQSettingMe` 的
  构造函数 + 布局遍历路径（`initOnce` 里 `requireMinQQVersion(QQ_8_9_25)` 为 false 的分支），
  **完全不依赖这三个 DexKit target**，只是被 target 预扫描拖崩了整个 `initOnce`。
- 「下拉形象展示」在 8.8.50 没有对应 AB 开关，属于版本缺失，只能跳过并记日志。

### 改动

1. 新增 `resolveSimplifyQQSettingMeTargets()`：按宿主版本裁剪预扫描列表。
   - `>= 9.0.20` → `[QQ_SETTING_ME_CONFIG_CLASS, ..._isZPlanExpGroup_New]`（保持上游行为）
   - `>= 8.9.90` → `[QQ_SETTING_ME_CONFIG_CLASS, ..._isZplanExpGroup_Old]`
   - **否则（含 8.8.50）→ `emptyArray()`**，`isPreparationRequired` 直接为 false，不再有 0 命中预扫描
   - 整体包 `try/catch`，任何异常回落空数组（hostInfo 未初始化时也不会炸）
   - 返回类型写成 `kotlin.Array<DexKitTarget>`：本文件 `import java.lang.reflect.Array`
     会遮蔽 `kotlin.Array`，不限定就报 `No type arguments expected for 'class Array : Any'`（实测踩到）
2. 「下拉形象展示」分支加 `classExists("com.tencent.mobileqq.QQSettingMeABTestHelper")` 门控，
   不存在则 `Log.w` 后跳过，不再调 `requireMethodFromCache`。
3. 保留 `QQ_SETTING_ME_CONFIG_CLASS` 在 9.x 分支的使用（8.8.50 不进入该分支）。

---

## 2. 新版侧滑栏精简 — `io/github/horange321/SimplifyQQSettingMe2.kt`

### 根因

`com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3` 是 9.x 的「部件化侧滑栏」实现，
8.8.50 是 `QQSettingMe`（View 容器）+ `qqsettingme.*Processor` 的旧架构，该 Part 类不存在。

### 证据

| 检查 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| 目标类 | `rg "^Lcom/tencent/mobileqq/parts/QQSettingMe" analysis/methods_8.8.50.txt` | **0 命中** |
| 实机堆栈 | `references/hooks/SimplifyQQSettingMe2-1794195222696.txt` | `Initiator.loadClass(Initiator.java:107)` ← `SimplifyQQSettingMe2.kt:69` |
| 旧架构存在性 | `rg "^Lcom/tencent/mobileqq/activity/qqsettingme/" analysis/methods_8.8.50.txt` | 200+ 类：`QQSettingMeMenuConfigProcessor`、`QQSettingMeBizBean`、`QQSettingMeMenuConfigBean`、`QQSettingMe*Processor` 全在 |
| 条目 id | `rg -a "d_album\|d_favorite\|d_document\|d_qqwallet\|d_vip_identity\|d_decoration\|d_vip_card" apks/dex/` | 7 个 key **全部命中**，与本 hook 的 `map` key 完全一致 |
| 隐藏点 | `analysis/jadx_sm/.../QQSettingMe.java:659`、`:861` | `inflate2.setVisibility(a(qQSettingMeBizBean) ? 8 : 0);` — 私有方法 `boolean a(QQSettingMeBizBean)` 就是可见性判定 |
| 方法签名 | `methods_8.8.50.txt` | `QQSettingMe;->a(Lcom/tencent/mobileqq/activity/qqsettingme/config/QQSettingMeBizBean;)Z`、`QQSettingMeBizBean;->a()Ljava/lang/String;`、`QQSettingMe;->b(Ljava/lang/String;)Landroid/view/View;`（= `getItemView(bizId)`） |

`QQSettingMe.a(QQSettingMeBizBean)` 在两处被调用：建列表（659）和刷新可见性（861），
所以 hook 它返回 `true` 的效果是「条目建出来就被 GONE，且后续刷新也不会重新显示」——
比事后 `removeView` 安全（不破坏 `this.ap` 索引），比 `setViewZeroSize` 干净（不留占位）。

### 结论

**能修（替代实现：隐藏指定条目，最小可用路径）。**

### 改动

1. `initOnce()` 首行加 `classExists("com.tencent.mobileqq.parts.QQSettingMeMenuPanelPartV3")` 判断，
   不存在则走 `initLegacySettingMe()`；原 Part 逻辑原样保留（9.x 继续可用）。
2. 新增 `initLegacySettingMe()`：
   - 反射 `QQSettingMeBizBean.a()`（`declaredMethods.first { name=="a" && 无参 && returnType==String }`）拿 bizId
   - 反射 `QQSettingMe.a(QQSettingMeBizBean)`（`returnType==boolean && 单参 && paramTypes[0]==bizBeanClass`），
     不硬编码混淆名
   - `hookAfterIfEnabled`：`map[bizId] in activeItems` 时 `param.result = true`
   - 任一步找不到方法 → `Log.w` + 返回 false（不抛）
3. `isAvailable` 改为 `classExists(PartV3) || classExists(QQSettingMe)`，8.8.50 满足后半段。

---

## 3. 禁止新样式侧滑栏 — `com/xiaoniu/hook/DisableQQSettingMeV9.kt`

### 根因

整个功能压在一个 AB 开关上：`QQSettingMeABTestHelper.isV9ExpGroup()`。
8.8.50 **连宿主类和 V9 侧滑栏都不存在**，因此没有「新样式」可禁，也没有替代 hook 点。

### 证据

| 检查 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| target 定义 | `DexKitTarget.kt:948-953` | `UsingStringVector`，特征串 `["isV9ExpGroup: ", "QQSettingMeABTestHelper"]` |
| 类 | `rg QQSettingMeABTestHelper analysis/methods_8.8.50.txt` | **0 命中** |
| 特征串 | `rg -a "isV9ExpGroup" apks/dex/` | **0 命中** |
| V9 视图类 | `rg "QQSettingMeViewV9" analysis/methods_8.8.50.txt` | **0 命中**（`com.tencent.mobileqq.activity.QQSettingMeViewV9` / `com.tencent.mobileqq.QQSettingMeViewV9` 都没有） |
| 实机堆栈 | `references/hooks/DisableQQSettingMeV9-1794195260172.txt` | `DexKit.requireMethodFromCache(DexKit.kt:136)` ← `DisableQQSettingMeV9.kt:45` |

### 结论

**不可修。** 8.8.50 没有 V9 侧滑栏实现，功能语义为空；也不存在可替代的 hook 点。

### 改动

1. 新增 `resolveV9Targets()`：`>= 8.9.90` 才预扫描 `QQSettingMeABTestHelper_isV9ExpGroup`，否则 `emptyArray()`。
2. `override val isAvailable = classExists("com.tencent.mobileqq.QQSettingMeABTestHelper")`
   → 8.8.50 上条目从设置里消失（写法对齐 `im/mingxi/BlockLinkInfo.kt:25`）。
3. `initOnce()` 内再加一层 `classExists` 门控：历史配置残留 `enabled=true` 时也只打日志、不抛异常。

---

## 4. 隐藏 QQ 能量值 — `io/github/fusumayuki/hook/HideQQValue.kt`

### 根因

**典型「类在、特征串没了」**：DexKit target 靠 `"能量值:"` 定位方法，
8.8.50 里这个串不存在，但目标方法本体还在 —— 所以改用直接反射。

### 证据

| 检查 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| target 定义 | `DexKitTarget.kt:955-960` | `UsingStr`，`traitString = ["能量值:"]`，`declaringClass = "com/tencent/mobileqq/vas/qqvaluecard/view/QQValuePagView"` |
| 特征串 | `rg -a "能量值" apks/dex/` | 只在 `classes11.dex` 命中 1 次，是独立词条 `能量值`（邻近 `脏话`/`腾讯图书VIP`），**`"能量值:"` 不存在** |
| 目标类 | `rg "^Lcom/tencent/mobileqq/vas/qqvaluecard/" analysis/methods_8.8.50.txt` | `QQValuePagView`、`QQValueInfoManage`、`QQValueInfoItem` **全部存在** |
| 目标方法 | `QQValuePagView;->a(Ljava/lang/String;Ljava/lang/String;Z)V` | **存在** |
| 方法语义 | `analysis/jadx_qv/.../QQValuePagView.java:231` | `public void a(String str, String str2, boolean z)` → `setVisibility(8/0)` + `QQValueInfoManage.a().a(str2, this)`，即能量值 PagView 的绑定/显示入口 |
| 实机堆栈 | `references/hooks/HideQQValue-1794195238322.txt` | `DexKit.requireMethodFromCache(DexKit.kt:136)` ← `HideQQValue.kt:75` |

### 结论

**能修。** 目标方法存在，只是 DexKit 的特征串在新旧版本间漂移了。改为按签名反射，
不依赖混淆名（`a`/`x`/`y`/`z` 逐版本变），也不走 DexKit 缓存。

### 改动

1. 去掉构造参数里的 `arrayOf(QQValueMethod)`（不再预扫描该 target，`isPreparationRequired` → false）。
2. `initOnce()` 改为：
   ```kotlin
   val qqValueClass = Initiator.loadClass("com.tencent.mobileqq.vas.qqvaluecard.view.QQValuePagView")
   val qqValueMethod = qqValueClass.declaredMethods.firstOrNull {
       it.returnType == Void.TYPE && it.parameterTypes.size == 3 &&
       it.parameterTypes[0] == String::class.java &&
       it.parameterTypes[1] == String::class.java &&
       it.parameterTypes[2] == Boolean::class.javaPrimitiveType
   } ?: return false
   ```
3. 保留原 hook 语义（`hookBeforeIfEnabled` + `isClickable = false` + `result = null`）与
   `HiddenVipIconForSe.optimizeQLevel(10, this)` 调用。
4. 新增 `isAvailable`（`QQValuePagView` 类探测），早期无能量值的版本自动隐藏条目。

---

## 5. 汇总

| # | 功能 | 结论 | 一句话依据 |
| --- | --- | --- | --- |
| 1 | 侧滑栏精简 | **能修** | 主功能走 `activity.QQSettingMe` 构造/布局路径，不依赖 DexKit；按版本裁剪 target + 「下拉形象展示」classExists 门控 |
| 2 | 新版侧滑栏精简 | **能修（替代实现）** | `QQSettingMeMenuPanelPartV3` 不存在，改 hook 8.8.50 的 `QQSettingMe.a(QQSettingMeBizBean)Z`（jadx:659/861 的可见性判定），按 bizId 隐藏 |
| 3 | 禁止新样式侧滑栏 | **不可修** | `QQSettingMeABTestHelper` 与 `QQSettingMeViewV9` 在 8.8.50 均 0 命中，无「新样式」可禁；加 `isAvailable` 探测让条目消失 |
| 4 | 隐藏 QQ 能量值 | **能修** | 特征串 `"能量值:"` 消失但方法 `QQValuePagView.a(String,String,boolean)` 仍在（jadx:231），改按签名反射 |

### 改动文件清单

| 文件 | 改动 |
| --- | --- |
| `source/app/src/main/java/cc/ioctl/hook/sideswipe/SimplifyQQSettingMe.kt` | 版本化 target 裁剪 + 下拉形象展示门控 |
| `source/app/src/main/java/io/github/horange321/SimplifyQQSettingMe2.kt` | 新增 `initLegacySettingMe()` 替代实现 + isAvailable |
| `source/app/src/main/java/com/xiaoniu/hook/DisableQQSettingMeV9.kt` | target 裁剪 + `isAvailable` + initOnce 门控 |
| `source/app/src/main/java/io/github/fusumayuki/hook/HideQQValue.kt` | 去掉 DexKit 依赖，改按签名反射 + isAvailable |

未改动 `io/github/qauxv/hook/experimental/`、`core/`、`dsl/`、`fragment/`、`util/ClassAvailability.kt`；
`util/dexkit/DexKitTarget.kt` 的 target 定义全部保留（9.x 分支继续使用）。

### 编译

```
pwsh -NoProfile -File D:\DSH-Builds\QAuxiliaryFix\tools\build.ps1 -Task ":app:compileDebugKotlin"

BUILD SUCCESSFUL in 1m 8s
101 actionable tasks: 4 executed, 97 up-to-date
```

（只剩仓库既有的 unchecked-cast / deprecated 警告，无 `e:` 错误。）

过程中踩到并修掉的两个编译错误，记录备查：

| 错误 | 原因 | 修法 |
| --- | --- | --- |
| `Syntax error: Unclosed comment`（SimplifyQQSettingMe.kt:405） | KDoc 里写了 `apks/dex/*.dex`，`/*` 在 Kotlin 块注释中会开启**嵌套注释**，导致整个文件注释不闭合 | 注释里改写成「解压出的 23 个 dex」，三个文件同类写法一并修掉 |
| `No type arguments expected for 'class Array : Any'`（SimplifyQQSettingMe.kt:85） | 文件里 `import java.lang.reflect.Array` 遮蔽了 `kotlin.Array` | 返回类型显式写 `kotlin.Array<DexKitTarget>` |

