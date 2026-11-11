# 构建说明

这个项目**不使用 Gradle**，直接用 Android SDK 自带的命令行工具构建。
整个流程可以在 Android 手机上的 Ubuntu 环境（proot）里完成。

---

## 为什么不用 Gradle

| 用 Gradle | 本项目 |
|---|---|
| 首次构建下载数百 MB 依赖 | 只需约 150 MB 的 SDK 工具 |
| 在 aarch64 手机上经常失败 | 纯脚本，可控 |
| 隐式配置多 | 八步流程全部可见 |

代价是要自己写构建脚本，但一个 App 只需要一份。

---

## 前置：准备工具链

只需要准备一次，都放在工作目录，不动系统环境。

### 目录规划

```
<workspace>/
├── tools/
│   ├── qemu/qemu-x86_64-static   # 跑 aapt2 用
│   ├── x86root/                  # x86_64 运行库
│   └── jdkroot/                  # JDK 17
└── sdk/
    ├── aapt2                     # x86_64 二进制
    ├── zipalign                  # x86_64 二进制
    ├── lib64/libc++.so           # zipalign 依赖
    ├── android.jar               # API 34
    ├── d8.jar                    # 含 R8
    └── apksigner.jar
```

### 为什么需要 qemu

Google 官方的 `aapt2` 和 `zipalign` **只有 x86_64 版本**。
在 arm64 手机上要用 **qemu 用户态模拟**才能运行。

其余工具（`javac`、`d8`、`apksigner`）都是纯 Java，用 arm64 的 JDK 即可。

### 1. qemu（静态二进制）

```bash
mkdir -p tools/debs && cd tools/debs
apt-get download qemu-user-static

# 解 deb（没有 dpkg-deb 时手工解 ar）
python3 - <<'EOF'
import os, tarfile
data = open([f for f in os.listdir('.') if f.endswith('.deb')][0], 'rb').read()
pos = 8
while pos + 60 <= len(data):
    hdr = data[pos:pos+60]
    name = hdr[0:16].decode().strip()
    size = int(hdr[48:58].decode().strip())
    body = data[pos+60:pos+60+size]
    if name.startswith('data.tar'):
        ext = name.split('.')[-1]
        open('/tmp/d.tar.' + ext, 'wb').write(body)
        break
    pos += 60 + size + (size % 2)
EOF

# 解 zstd
node -e 'const z=require("zlib"),f=require("fs");
f.writeFileSync("/tmp/d.tar", z.zstdDecompressSync(f.readFileSync("/tmp/d.tar.zst")))'

# 取出静态二进制
python3 - <<'EOF'
import tarfile, os, shutil
t = tarfile.open('/tmp/d.tar')
os.makedirs('../qemu', exist_ok=True)
for m in t.getmembers():
    b = os.path.basename(m.name)
    if b in ('qemu-x86_64-static',) and m.isfile():
        p = '../qemu/' + b
        with open(p, 'wb') as w:
            shutil.copyfileobj(t.extractfile(m), w)
        os.chmod(p, 0o755)
EOF

../qemu/qemu-x86_64-static --version
```

### 2. x86_64 运行库

aapt2 是动态链接的，需要 x86_64 的 C 库。

```bash
cd tools/debs
node -e '
const fs=require("fs");
(async()=>{
  const items=[
    ["https://mirrors.tuna.tsinghua.edu.cn/ubuntu/pool/main/g/glibc/","libc6_2.39-0ubuntu8.9_amd64.deb"],
    ["https://mirrors.tuna.tsinghua.edu.cn/ubuntu/pool/main/g/gcc-14/","libgcc-s1_14.2.0-4ubuntu2~24.04.1_amd64.deb"]
  ];
  for(const [base,name] of items){
    const r=await fetch(base+name);
    fs.writeFileSync(name, Buffer.from(await r.arrayBuffer()));
  }
})()'
```

解包后**必须补齐动态链接器路径**：

```bash
SYS=tools/x86root
mkdir -p $SYS/lib64 $SYS/lib
cp $SYS/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 $SYS/lib64/
cp $SYS/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 $SYS/lib/
```

### 3. Android SDK

```bash
cd sdk

# build-tools r34
node -e '
const fs=require("fs");
(async()=>{
  const r=await fetch("https://dl.google.com/android/repository/build-tools_r34-linux.zip");
  fs.writeFileSync("build-tools.zip", Buffer.from(await r.arrayBuffer()));
})()'

# platform 34（android.jar）
node -e '
const fs=require("fs");
(async()=>{
  const r=await fetch("https://dl.google.com/android/repository/platform-34-ext7_r03.zip");
  fs.writeFileSync("platform-34.zip", Buffer.from(await r.arrayBuffer()));
})()'

# 取关键文件
python3 - <<'EOF'
import zipfile, os
z = zipfile.ZipFile('build-tools.zip')
for n in z.namelist():
    base = n.split('/')[-1]
    if base in ('aapt2', 'zipalign'):
        open(base, 'wb').write(z.read(n)); os.chmod(base, 0o755)
    if base in ('d8.jar', 'apksigner.jar'):
        open(base, 'wb').write(z.read(n))
    if base == 'libc++.so':
        os.makedirs('lib64', exist_ok=True)
        open('lib64/' + base, 'wb').write(z.read(n))

z2 = zipfile.ZipFile('platform-34.zip')
open('android.jar', 'wb').write(z2.read('android-34/android.jar'))
EOF
```

**验证 aapt2 能运行**（关键一步）：

```bash
QEMU=tools/qemu/qemu-x86_64-static
SYS=tools/x86root
$QEMU -L $SYS sdk/aapt2 version
# 期望：Android Asset Packaging Tool (aapt) 2.19-...
```

### 4. JDK 17

```bash
cd sdk
apt-get download openjdk-17-jdk-headless openjdk-17-jre-headless
```

解包到 `tools/jdkroot` 后，**必须修复断掉的符号链接**：

```bash
python3 - <<'EOF'
import os, shutil
root = 'tools/jdkroot'
jvm = root + '/usr/lib/jvm/java-17-openjdk-arm64'
fixed = 0
for dp, _, fs in os.walk(jvm):
    for n in fs:
        p = os.path.join(dp, n)
        if os.path.islink(p):
            t = os.readlink(p)
            if t.startswith('/etc/java-17-openjdk/'):
                real = root + t
                if os.path.exists(real):
                    os.remove(p); shutil.copy2(real, p); fixed += 1
print("修复:", fixed)   # 约 21 个
EOF
```

**不修会怎样**：`javac` 报 `Error loading java.security`，`keytool` 报 `Couldn't parse jurisdiction policy files`。

**JDK 路径**在 `build.sh` 顶部，按实际情况改。

### 5. ZXing（二维码）

```bash
cd sdk
node -e '
const fs=require("fs");
(async()=>{
  const r=await fetch("https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar");
  fs.writeFileSync("zxing-core.jar", Buffer.from(await r.arrayBuffer()));
})()'
```

---

## 构建

```bash
./build.sh
```

输出：`build/final.apk`

### 环境自动探测

`build.sh` 会检测架构并自动选择路径：

| 架构 | aapt2 调用方式 |
|---|---|
| `aarch64`（Android 手机） | 经 qemu 模拟 |
| `x86_64`（电脑） | 直接执行 |

自动查找：SDK 目录、`android.jar`、JDK。找不到时可用环境变量指定：

```bash
SDK=/path/to/build-tools/34.0.0 ./build.sh
```

构建开始时会打印实际使用的路径：

```
   架构: x86_64
   SDK:  /home/user/Android/Sdk/build-tools/34.0.0
    JDK:  /usr/lib/jvm/java-17-openjdk
```

**在电脑上不需要准备 qemu 和 x86root**，那两样只在手机上有用。

### 八步流程

| 步骤 | 工具 | 说明 |
|---|---|---|
| 1 | `aapt2 compile` | 编译 `res/` 为 `.flat` |
| 2 | `aapt2 link` | 生成 `base.apk` + `R.java` |
| 3 | `javac` | 编译 Java |
| 4 | `R8` | 转 dex + 裁剪 |
| 5 | `python zipfile` | 把 dex 塞进 apk |
| 6 | `zipalign -p 4` | 对齐（签名前提） |
| 7 | `apksigner` | v2/v3 签名 |
| 8 | `apksigner verify` | 校验 |

### 版本号

改 `version.properties`：

```properties
versionCode=9
versionName=1.0.8
```

`versionCode` 每次发版必须递增，否则系统拒绝覆盖安装。

### 签名配置

首次构建会自动生成密钥。密码从 `keystore.properties` 读取：

```properties
KS_PASS=你的密码
KS_ALIAS=musicbox
```

该文件已在 `.gitignore` 中，**不会提交**。仓库里只有 `keystore.properties.example`。

也可以用环境变量覆盖：

```bash
KS_PASS='xxx' ./build.sh
```

---

## 常见问题

### aapt2 报 `Could not open '/lib64/ld-linux-x86-64.so.2'`

qemu 的 sysroot 里缺动态链接器。把 `ld-linux-x86-64.so.2` 复制到 `$SYS/lib64/` 和 `$SYS/lib/`。

### zipalign 报 `libc++.so: cannot open shared object file`

从 build-tools zip 里取出 `lib64/libc++.so`，构建脚本已用 `-E LD_LIBRARY_PATH` 传入。

### javac 报 `Error loading java.security`

JDK 解包后符号链接断了，按上文「JDK 17」一节修复。

### 构建成功但 App 闪退

检查 `build.sh` 是否吞掉了 javac 错误。本项目的脚本在 javac 失败时会 `exit 1` 并打印错误——**不要加 `|| true`**。

### APK 体积突然跳了 4 KB

正常现象。APK 签名块要求 4 KB 对齐，代码稍多一点跨过边界，就会多出一整页填充。

---

## 体积参考

| 项目 | 大小 |
|---|---|
| 空壳 App | ~13 KB |
| 本项目 | ~100 KB |
| dex 未压缩 | ~200 KB |
| 工具链总占用 | ~700 MB |
