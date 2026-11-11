# Windows 构建说明

本文件补充 `BUILD.md`，记录**在这台电脑上**怎么构建。
原来的 `build.sh` 是给 Linux/手机端的，Windows 上请用 `build.ps1`。

---

## 一、目录结构

```
BiliMusicDev\                        ← 整个文件夹可直接删除，不留系统残留
├── toolchain\                       ← 便携式工具链，568 MB
│   ├── jdk-17\                      JDK 17.0.21（含 javac / java / keytool）
│   └── sdk\
│       ├── build-tools\34.0.0\
│       │   ├── aapt2.exe  zipalign.exe
│       │   ├── lib\d8.jar  lib\apksigner.jar
│       │   └── zxing-core.jar       ZXing 3.5.3（二维码编码）
│       └── platforms\android-34\android.jar
└── BiliMusic\                       ← 项目源码
    ├── build.ps1                    Windows 构建脚本
    ├── build.sh                     原 Linux/手机脚本（Windows 上不用）
    └── build\final.apk              产物
```

**不需要**：Gradle、Android Studio、Python、bash、WSL、qemu。

---

## 二、构建

```powershell
cd "C:\path\to\BiliMusic"
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Clean
```

成功会打印 `BUILD OK -> ...\build\final.apk`。

### 为什么要 `-ExecutionPolicy Bypass`

系统执行策略是 `RemoteSigned`，本地未签名脚本会被拦。
这里只对**本次调用**放行，不改系统设置。

### 版本号

改 `version.properties` 后重新构建即可，脚本自动读取。

---

## 三、八步流程（与 build.sh 一致）

| 步 | 工具 | 说明 |
|---|---|---|
| 1 | `aapt2 compile` | 编译 `res\` → `.flat` |
| 2 | `aapt2 link` | 链接成 `base.apk` + 生成 `R.java` |
| 3 | `javac` | 编译源码（含 R.java） |
| 4 | `R8` | 转 dex + 裁剪（失败自动退回 d8） |
| 5 | `PackDex` | 把 `classes.dex` 塞进 APK |
| 6 | `zipalign -p 4` | 对齐 |
| 7 | `apksigner` | 签名（v2 + v3） |
| 8 | `apksigner verify` | 校验 |

第 5 步原脚本用 `python3` 的 `zipfile`，这里改成 `PackDex.java`
（JDK 自带 `java.util.zip`），所以**不依赖 Python**。

---

## 四、踩过的坑（Windows 特有）

### 1. 中文目录名会让 aapt2 失败

`BiliMusic开发` 这种含中文的路径，aapt2 报
`failed to open directory`。**目录名必须纯 ASCII**，
所以这里叫 `BiliMusicDev`。

### 2. PowerShell 5.1 按 GBK 读无 BOM 的 .ps1

中文会全乱、语法解析直接失败。
**`build.ps1` 必须存成 UTF-8 带 BOM**。
（用 `edit` 类工具改完记得检查：`head -c 3 build.ps1` 应是 `239 187 191`）

### 3. `Set-Content -Encoding UTF8` 会加 BOM

javac 的 `@源列表`、R8 的 proguard 配置**都不认开头 BOM**。
脚本里统一用 `WriteUtf8NoBom` 写。

### 4. `@files` 里路径的两个坑

- 含空格必须加引号，否则 `C:\My Projects\...` 会被拆成两个 token
- 反斜杠是转义符，会被吃掉（`D:DeepSeek`），所以要换成正斜杠

### 5. 原生命令写 stderr 会被当成致命错误

javac 的「注: 某些输入文件使用已过时的 API」、aapt2 的版本号都走 stderr。
配 `ErrorActionPreference='Stop'` 会直接中断。
脚本用 `Invoke-Native` 包一层，临时降级。

### 6. 判断成败看产物，不看退出码

javac 成功但退出码可能非 0。脚本以「是否产出 class」为准，
同时检查输出里有没有真正的 `错误:`。

---

## 五、换电脑怎么迁移

整个 `BiliMusicDev` 文件夹拷走即可，工具链和源码都在里面。
换路径后 `build.ps1` 会自动按相对位置找到 `..\toolchain`。

若工具链丢失，重新下载：

| 组件 | 地址 |
|---|---|
| JDK 17 | `https://aka.ms/download-jdk/microsoft-jdk-17.0.21.0.2-windows-x64.zip` |
| build-tools 34 | `https://dl.google.com/android/repository/build-tools_r34-windows.zip` |
| platform-34 | `https://dl.google.com/android/repository/platform-34-ext7_r03.zip` |
| ZXing | `https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar` |
