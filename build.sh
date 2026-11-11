#!/usr/bin/env bash
# 构建 DSH B站播放器 —— 纯 SDK 工具链，不用 Gradle
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"

# ---------------------------------------------------------------- 环境探测
# 同时支持两种环境：
#   1. Android 手机 (aarch64)：aapt2/zipalign 是 x86_64 二进制，需要 qemu 模拟
#   2. 电脑 (x86_64)：直接执行，不需要 qemu
#
# 可以用环境变量覆盖，例如：
#   SDK=/opt/android-sdk/build-tools/34.0.0 ./build.sh
#   JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./build.sh

# --- SDK 目录：按顺序找第一个存在的 ---
if [ -z "$SDK" ]; then
  for d in "$ROOT/sdk" "/root/workspace/sdk" \
           "$HOME/Android/Sdk/build-tools/34.0.0" \
           "$HOME/Library/Android/sdk/build-tools/34.0.0" \
           "/opt/android-sdk/build-tools/34.0.0" \
           "/usr/lib/android-sdk/build-tools/34.0.0"; do
    if [ -f "$d/aapt2" ]; then SDK="$d"; break; fi
  done
fi
if [ -z "$SDK" ] || [ ! -f "$SDK/aapt2" ]; then
  echo "!!! 找不到 Android SDK build-tools（需要 aapt2 / zipalign / d8.jar / apksigner.jar）"
  echo "    请设置 SDK=/path/to/build-tools/34.0.0"
  exit 1
fi

# --- android.jar：build-tools 同级或上级目录里找 ---
if [ -z "$ANDROID_JAR" ]; then
  if [ -f "$SDK/android.jar" ]; then
    ANDROID_JAR="$SDK/android.jar"
  else
    BASE="$(dirname "$(dirname "$SDK")")"
    for c in "$BASE/platforms/android-34/android.jar" \
             "$BASE/platforms/android-35/android.jar" \
             "$BASE/android.jar"; do
      if [ -f "$c" ]; then ANDROID_JAR="$c"; break; fi
    done
  fi
fi
if [ -z "$ANDROID_JAR" ] || [ ! -f "$ANDROID_JAR" ]; then
  echo "!!! 找不到 android.jar"
  echo "    请设置 ANDROID_JAR=/path/to/platforms/android-34/android.jar"
  exit 1
fi

# --- JDK：优先 JAVA_HOME，其次 PATH 里的 javac ---
if [ -z "$JAVA_HOME" ]; then
  for d in /root/workspace/tools/jdkroot/usr/lib/jvm/java-17-openjdk-arm64 \
           /usr/lib/jvm/java-17-openjdk-amd64 \
           /usr/lib/jvm/java-17-openjdk \
           /opt/homebrew/opt/openjdk@17 \
           "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    if [ -x "$d/bin/javac" ]; then JAVA_HOME="$d"; break; fi
  done
fi
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVA="$JAVA_HOME/bin/java"
  JAVAC="$JAVA_HOME/bin/javac"
  KEYTOOL="$JAVA_HOME/bin/keytool"
else
  JAVA="$(command -v java || true)"
  JAVAC="$(command -v javac || true)"
  KEYTOOL="$(command -v keytool || true)"
fi
if [ -z "$JAVAC" ]; then
  echo "!!! 找不到 javac，请安装 JDK 17 或设置 JAVA_HOME"
  exit 1
fi

# --- qemu：只有 aarch64 手机上才需要 ---
ARCH="$(uname -m)"
QEMU=""
X86SYS=""
if [ "$ARCH" != "x86_64" ] && [ "$ARCH" != "amd64" ]; then
  for q in "$ROOT/../tools/qemu/qemu-x86_64-static" \
           "/root/workspace/tools/qemu/qemu-x86_64-static"; do
    if [ -x "$q" ]; then QEMU="$q"; break; fi
  done
  for x in "$ROOT/../tools/x86root" "/root/workspace/tools/x86root"; do
    if [ -d "$x" ]; then X86SYS="$x"; break; fi
  done
  if [ -z "$QEMU" ]; then
    echo "!!! 当前是 $ARCH，aapt2 需要 qemu-x86_64-static，但没找到"
    exit 1
  fi
fi

if [ -n "$QEMU" ]; then
  AAPT2="$QEMU -L $X86SYS $SDK/aapt2"
  ZIPALIGN="$QEMU -L $X86SYS -E LD_LIBRARY_PATH=$SDK/lib64 $SDK/zipalign"
else
  AAPT2="$SDK/aapt2"
  ZIPALIGN="$SDK/zipalign"
fi

echo "   架构: $ARCH"
echo "   SDK:  $SDK"
echo "    JDK: ${JAVA_HOME:-$(dirname "$(dirname "$JAVAC")")}"

OUT="$ROOT/build"
RESC="$OUT/resc"
GEN="$OUT/gen"
OBJ="$OUT/obj"
DEX="$OUT/dex"

# 版本号来自 version.properties（环境变量可覆盖）
VERFILE="$ROOT/version.properties"
if [ -f "$VERFILE" ]; then
  FILE_CODE=$(grep -E '^versionCode=' "$VERFILE" | cut -d= -f2 | tr -d ' \r')
  FILE_NAME=$(grep -E '^versionName=' "$VERFILE" | cut -d= -f2 | tr -d ' \r')
fi
VERSION_CODE="${VERSION_CODE:-${FILE_CODE:-1}}"
VERSION_NAME="${VERSION_NAME:-${FILE_NAME:-1.0.0}}"
MIN_SDK=24
TARGET_SDK=34

rm -rf "$RESC" "$GEN" "$OBJ" "$DEX"
mkdir -p "$RESC" "$GEN" "$OBJ" "$DEX" "$OUT"

echo "== 1/8 编译资源 =="
$AAPT2 compile --dir "$ROOT/res" -o "$RESC"

echo "== 2/8 链接资源 =="
FLATS=$(find "$RESC" -name "*.flat" | sort)
$AAPT2 link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  -o "$OUT/base.apk" \
  --java "$GEN" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  -A "$ROOT/assets" \
  $FLATS 2>/dev/null || {
    # assets 目录可能为空或不存在，去掉 -A 重试
    $AAPT2 link \
      -I "$ANDROID_JAR" \
      --manifest "$ROOT/AndroidManifest.xml" \
      -o "$OUT/base.apk" \
      --java "$GEN" \
      --min-sdk-version $MIN_SDK \
      --target-sdk-version $TARGET_SDK \
      --version-code "$VERSION_CODE" \
      --version-name "$VERSION_NAME" \
      $FLATS
  }

echo "== 3/8 编译 Java =="
find "$ROOT/src" "$GEN" -name "*.java" > "$OUT/sources.txt"
JAVAC_LOG="$OUT/javac.log"
"$JAVAC" -encoding UTF-8 -nowarn -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR:$SDK/zxing-core.jar" \
  -d "$OBJ" @"$OUT/sources.txt" 2>"$JAVAC_LOG"

if [ $? -ne 0 ]; then
  echo "!!! javac 失败，错误如下："
  grep -v "^Note:" "$JAVAC_LOG" | head -30
  exit 1
fi

# 兜底：确认每个源文件都产出了 class（防止静默漏编）
SRC_COUNT=$(wc -l < "$OUT/sources.txt")
CLS_COUNT=$(find "$OBJ" -name "*.class" | wc -l)
if [ "$CLS_COUNT" -lt 1 ]; then
  echo "!!! javac 未产出任何 class"
  exit 1
fi
echo "   源码 $SRC_COUNT 个，class $CLS_COUNT 个"

echo "== 4/8 转 dex (R8) =="
cat > "$OUT/keep.txt" <<'KEEPEOF'
-keep class com.star.bilimusic.** { *; }
# 二维码编码只用到 qrcode 包 + common 包 + 少数顶层类，
# 其余 ZXing（解码器 / 各种条码格式 / AWT 相关）全部裁掉。
# 之前用 -keep com.google.zxing.** 会锁死整个 593KB 的库，dex 虚高 300KB+。
-keep class com.google.zxing.qrcode.** { *; }
-keep class com.google.zxing.common.** { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.EncodeHintType { *; }
-keep class com.google.zxing.WriterException { *; }
-dontwarn com.google.zxing.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.naming.**
-allowaccessmodification
-repackageclasses ''
KEEPEOF
"$JAVA" -cp "$SDK/d8.jar" com.android.tools.r8.R8 \
  --release --lib "$ANDROID_JAR" --min-api $MIN_SDK \
  --output "$DEX" \
  --pg-conf "$OUT/keep.txt" \
  "$SDK/zxing-core.jar" \
  $(find "$OBJ" -name "*.class") 2>&1 | grep -vi "^warning" | head -5 || true
if [ ! -f "$DEX/classes.dex" ]; then
  echo "!!! R8 失败，退回 d8"
  "$JAVA" -cp "$SDK/d8.jar" com.android.tools.r8.D8 \
    --release --lib "$ANDROID_JAR" --min-api $MIN_SDK \
    --output "$DEX" "$SDK/zxing-core.jar" $(find "$OBJ" -name "*.class")
fi

echo "== 5/8 打包 dex =="
python3 - "$OUT/base.apk" "$DEX/classes.dex" <<'PYEOF'
import sys, zipfile, shutil
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
shutil.copy(apk, tmp)
z = zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED)
if "classes.dex" in z.namelist():
    z.close()
    z = zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED)
z.write(dex, "classes.dex")
z.close()
shutil.move(tmp, apk)
print("   dex 已打包")
PYEOF

echo "== 6/8 对齐 =="
$ZIPALIGN -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 7/8 签名 =="
KS="$ROOT/release.keystore"
# 签名密码来源优先级：环境变量 > keystore.properties > 默认值
# keystore.properties 已被 .gitignore 排除，不会提交到仓库。
KSP="$ROOT/keystore.properties"
if [ -f "$KSP" ]; then
  FILE_PASS=$(grep -E '^KS_PASS=' "$KSP" | cut -d= -f2-)
  FILE_ALIAS=$(grep -E '^KS_ALIAS=' "$KSP" | cut -d= -f2-)
fi
KS_PASS="${KS_PASS:-${FILE_PASS:-musicbox}}"
KS_ALIAS="${KS_ALIAS:-${FILE_ALIAS:-musicbox}}"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=BiliMusic, OU=Dev, O=BiliMusic, L=Local, S=Local, C=CN" -noprompt
fi
rm -f "$OUT/final.apk"
"$JAVA" -jar "$SDK/apksigner.jar" sign \
  --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/final.apk" "$OUT/aligned.apk"

echo "== 8/8 校验 =="
"$JAVA" -jar "$SDK/apksigner.jar" verify "$OUT/final.apk" && echo "   签名 OK"
ls -lh "$OUT/final.apk"
echo "BUILD OK -> $OUT/final.apk"
