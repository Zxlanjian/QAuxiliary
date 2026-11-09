# QAuxiliaryFix — QAuxiliary × QQ 8.8.50 兼容修复

本目录是基于上游 [cinit/QAuxiliary](https://github.com/cinit/QAuxiliary) `main@4791fc9`
（对应安装包 `1.6.1.r3054.4791fc9`）的本地修复工程，目标是让模块在 QQ 8.8.50（versionCode 2324）
上尽可能恢复功能，并合入两个自研实验性 hook。

产物：`artifacts/QAuxiliary-1.6.1-qq8850-debug-20261109.apk`（20 MB，`:app:assembleDebug` 实测可出包）。
上游 fork：<https://github.com/Zxlanjian/QAuxiliary>（分支 `main` / `feat/qq8850-compat`）。

## 目录

| 路径 | 内容 |
| --- | --- |
| `source/` | QAuxiliary 源码（git clone + 子模块 + 本次改动） |
| `apks/` | `QQ_8.8.50.apk`、`QAuxiliary_1.6.1.r3054.4791fc9.apk`；`apks/dex/` 为解出的 23 个 dex |
| `references/hooks/` | 用户导出的 82 个 hook 方法清单（1 个主进程成功清单 + 81 个失效清单） |
| `analysis/` | 方法签名索引、比对结果、反编译产物、修复报告、构建日志 |
| `docs/` | 分享卡片逆向报告、改动记录 |
| `tools/` | `dex_index.py`（dex 方法索引）、`compare_hooks.py`（清单比对）、`build.ps1`（构建包装） |
| `artifacts/` | 构建产物 |

## 构建环境（本机实测通过）

| 项 | 值 |
| --- | --- |
| JDK | `D:\DSH-Builds\_scratch\tools\jdk21\jdk-21.0.13+9`（AGP 9.4 需 17+，用 21） |
| Android SDK | `D:\Android\sdk`（compileSdk 37.0、build-tools 37.0.0） |
| Gradle | `D:\DSH-Builds\_scratch\tools\gradle-9.7.1\bin\gradle.bat`（wrapper 同为 9.7.1） |
| NDK | **29.0.13599879**（LSPlant 要求 r29+） |
| CMake | **3.31.0**（LSPlant 要求 3.28+） |
| Ninja | **1.12.1**（`D:\DSH-Builds\_scratch\tools\ninja\ninja.exe`，CMake 3.31 自带的 1.10.2 太旧） |

`source/local.properties`（不入库）：

```properties
sdk.dir=D\:\\Android\\sdk
qauxv.override.ninja.path=D\:\\DSH-Builds\\_scratch\\tools\\ninja\\ninja.exe
```

> NDK 与 CMake 用上游默认版本，不再需要 `qauxv.override.ndk.version` / `qauxv.override.cmake.version`。
> 只覆盖 ninja 路径。

## 构建命令

```powershell
# Kotlin 编译验证（约 1 分钟）
pwsh -File tools/build.ps1 -Task ":app:compileDebugKotlin"

# 出 APK（约 3 分钟，含 native）
pwsh -File tools/build.ps1 -Task ":app:assembleDebug"
# 产物：source/app/build/outputs/apk/debug/app-debug.apk
```

等价的手工命令：

```powershell
$env:JAVA_HOME='D:\DSH-Builds\_scratch\tools\jdk21\jdk-21.0.13+9'
$env:ANDROID_HOME='D:\Android\sdk'
cd D:\DSH-Builds\QAuxiliaryFix\source
D:\DSH-Builds\_scratch\tools\gradle-9.7.1\bin\gradle.bat --no-daemon :app:assembleDebug
```

## 四个必须知道的坑（全部已踩过）

### 1. 子模块会被拉成「有 .git 但工作区为空」

`git clone --depth 1` + `git submodule update --init --recursive --depth 1` 在 Git for Windows + MSYS 下
会留下空工作区，表现为：

- `Unresolved reference 'mobileqq' / 'AppRuntime'` → `libs/stub/qq-stub` 空
- `Unresolved reference 'XPopupUtils'` → `libs/xView/XPopup` 空
- `CMake Error: ... dex_builder does not contain a CMakeLists.txt` → LSPlant 的嵌套子模块空

修复：对所有空子模块执行 `git checkout -f HEAD`，并确认文件数：

```bash
cd source
find . -name .git -type f -not -path "./.git/*" | while read f; do
  d=$(dirname "$f"); n=$(find "$d" -type f -not -path "*/.git*" | wc -l)
  [ "$n" -lt 3 ] && ( cd "$d" && git checkout -f HEAD )
done
```

LSPlant 的 `dex_builder` / `parallel_hashmap` 需要单独补：

```bash
cd source/libs/LSPlant/lsplant/src/main/jni/external
git clone https://github.com/LSPosed/DexBuilder.git dex_builder
cd dex_builder && git checkout ac7fb2230954ee311808bad469b0db501f31bfb8
git submodule update --init --depth 1 external/parallel_hashmap
```

### 2. 子模块 HEAD 必须与父仓库 pin 的 commit 一致

`+` 前缀表示不一致。DexKit 不一致会直接炸出一片
`Unresolved reference 'findMethod' / 'descriptor' / 'paramTypes' / 'declaredClass'`。

```bash
for m in libs/dexkit/DexKit libs/fmt libs/libunwindstack libs/mmkv/MMKV; do
  pin=$(git ls-tree HEAD "$m" | awk '{print $3}')
  git -C "$m" fetch --depth 1 origin "$pin" && git -C "$m" checkout -q "$pin"
done
git submodule status   # 每行首字符不能是 '-' 或 '+'
```

### 3. `com.github.plattysoft:Leonids:1746429` 拉不到

它是 `WeatherView:3.0.0` 的传递依赖，只发布在 `maven.tmpfs.dev`，该仓库当前返回 502，
jitpack / mavenCentral / 阿里云镜像均无此 artifact。

已在 `app/build.gradle.kts` 对 `libs.weatherView` 加：

```kotlin
exclude(group = "com.github.plattysoft", module = "Leonids")
```

影响面：节日天气粒子动画（`name/mikanoshi/customiuizer/holidays/HolidayHelper.java`），
只在启用节日特效时才会加载该类；主流程不受影响。

### 4. Ninja 版本必须 ≥ 1.11

CMake 3.31 自带 Ninja 1.10.2，而 LSPlant / DexBuilder 的源文件会被判定为「可能使用 C++20 modules」，
触发 `The Ninja generator does not support C++20 modules using Ninja version 1.10.2`。
用 `qauxv.override.ninja.path` 指向 1.12.1 的 `ninja.exe` 即可（见上方 local.properties）。

## 产物

`artifacts/` 下按构建日期存放；APK 用 AGP debug key 签名，与官方 CI 包签名不同，
不能覆盖安装官方包（需先卸载）。
