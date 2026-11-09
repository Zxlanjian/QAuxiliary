# QAuxiliaryFix — QAuxiliary × QQ 8.8.50 兼容修复

本目录是基于上游 [cinit/QAuxiliary](https://github.com/cinit/QAuxiliary) `main@4791fc9`
（对应安装包 `1.6.1.r3054.4791fc9`）的本地修复工程，目标是让模块在 QQ 8.8.50（versionCode 2324）
上尽可能恢复功能，并合入两个自研实验性 hook。

## 目录

| 路径 | 内容 |
| --- | --- |
| `source/` | QAuxiliary 源码（git clone，含子模块） |
| `apks/` | `QQ_8.8.50.apk`、`QAuxiliary_1.6.1.r3054.4791fc9.apk`；`apks/dex/` 为解出的 23 个 dex |
| `references/hooks/` | 用户导出的 82 个 hook 方法清单（1 个主进程成功清单 + 81 个失效清单） |
| `analysis/` | 方法签名索引、比对结果、反编译产物、修复报告 |
| `docs/` | 分享卡片逆向报告 |
| `tools/` | `dex_index.py`（dex 方法索引）、`compare_hooks.py`（清单比对）、`build.ps1`（构建包装） |
| `artifacts/` | 构建产物 |

## 构建环境（本机已验证）

| 项 | 值 |
| --- | --- |
| JDK | `D:\DSH-Builds\_scratch\tools\jdk21\jdk-21.0.13+9` |
| Android SDK | `D:\Android\sdk`（compileSdk 37.0、build-tools 37.0.0 已装） |
| Gradle | `D:\DSH-Builds\_scratch\tools\gradle-9.7.1\bin\gradle.bat`（wrapper 同为 9.7.1） |
| NDK | 28.2.13676358（上游默认 29.0.13599879，本机未装，已用 `local.properties` 覆盖） |
| CMake | 3.22.1（上游默认 3.31.0，已覆盖；仅影响 native 编译） |

`source/local.properties`（不入库）：

```properties
sdk.dir=D\:\\Android\\sdk
qauxv.override.ndk.version=28.2.13676358
qauxv.override.cmake.version=3.22.1
```

## 构建命令

```powershell
# Kotlin 编译验证
pwsh -File tools/build.ps1 -Task ":app:compileDebugKotlin"

# 出 APK
pwsh -File tools/build.ps1 -Task ":app:assembleDebug"
```

等价的手工命令：

```powershell
$env:JAVA_HOME='D:\DSH-Builds\_scratch\tools\jdk21\jdk-21.0.13+9'
$env:ANDROID_HOME='D:\Android\sdk'
cd D:\DSH-Builds\QAuxiliaryFix\source
D:\DSH-Builds\_scratch\tools\gradle-9.7.1\bin\gradle.bat --no-daemon :app:assembleDebug
```

## 三个必须知道的坑

1. **子模块必须先 checkout 出内容**
   `git clone --depth 1` 后 `git submodule update --init --recursive --depth 1` 在本机（Git for Windows + MSYS）
   会把部分子模块拉成「有 `.git` 文件但工作区为空」的状态，表现为 `Unresolved reference 'mobileqq'`
   （stub 缺失）或 `Unresolved reference 'XPopupUtils'`（XPopup 缺失）。
   修复：对每个空子模块执行 `git checkout -f HEAD`，并确认目录非空：

   ```bash
   cd source
   for m in libs/stub/qq-stub libs/xView/XPopup libs/xView/EasyAdapter libs/linux-syscall-support; do
     ( cd "$m" && git checkout -f HEAD )
   done
   git submodule status   # 每行首字符不能是 '-' 或 '+'
   ```

   子模块 HEAD 必须与父仓库 pin 的 commit 一致（`+` 表示不一致）。对齐方法：

   ```bash
   for m in libs/dexkit/DexKit libs/fmt libs/libunwindstack libs/mmkv/MMKV; do
     pin=$(git ls-tree HEAD "$m" | awk '{print $3}')
     git -C "$m" fetch --depth 1 origin "$pin" && git -C "$m" checkout -q "$pin"
   done
   ```

   DexKit 版本不一致会直接炸出一片 `Unresolved reference 'findMethod' / 'descriptor' / 'paramTypes'`。

2. **`com.github.plattysoft:Leonids:1746429` 拉不到**
   它是 `WeatherView:3.0.0` 的传递依赖，只发布在 `maven.tmpfs.dev`，该仓库当前返回 502，
   jitpack / mavenCentral / 阿里云镜像均无此 artifact。
   已在 `app/build.gradle.kts` 对 `libs.weatherView` 加 `exclude(group = "com.github.plattysoft", module = "Leonids")`。
   影响面：节日天气粒子动画（`name/mikanoshi/customiuizer/holidays/HolidayHelper.java`），
   仅在启用节日特效时才会加载该类；主流程不受影响。

3. **native 部分需要 NDK 29 + CMake 3.31**
   LSPlant 要求 NDK r29+ / CMake 3.28+。本机用 28.2 / 3.22 覆盖时 **Kotlin 与 Java 编译正常，
   但 `assembleDebug` 的 native 步骤可能失败**。需要完整 APK 时安装 NDK 29.0.13599879 与 CMake 3.31.0，
   或删掉 `local.properties` 里两行 override。

## 产物

`artifacts/` 下按构建日期存放；APK 未做自定义签名（沿用 AGP debug key），
与官方 CI 包签名不同，不能覆盖安装官方包。
