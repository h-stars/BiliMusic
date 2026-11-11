<#
  BiliMusic 构建脚本（Windows 原生 PowerShell 版）

  对应原 build.sh 的八步流程，去掉两个外部依赖：
    - 不需要 bash     （Windows 没有 bash）
    - 不需要 python3  （第 5 步打包 dex 改用 PackDex.java）

  用法：
    .\build.ps1              # 正常构建
    .\build.ps1 -Clean       # 先清理 build 目录
    .\build.ps1 -SkipChecks  # 跳过环境自检（调试用）

  环境变量可覆盖（一般不用设，脚本会自动找 toolchain）：
    JAVA_HOME / SDK / ANDROID_JAR / KS_PASS / KS_ALIAS
#>
[CmdletBinding()]
param(
    [switch]$Clean,
    [switch]$SkipChecks
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ------------------------------------------------------------------ 控制台
# 切 UTF-8：Windows 控制台默认 GBK(936)，而 aapt2/javac 的报错是 UTF-8，
# 不切的话中文报错全是乱码，排查时反而丢掉最关键的信息。
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
    chcp 65001 > $null 2>&1
} catch { }

function Say($msg) { Write-Host $msg }
function Fail($msg) { Write-Host "!!! $msg" -ForegroundColor Red; exit 1 }

# 写无 BOM 的 UTF-8 文件。
# 为什么不用 Set-Content -Encoding UTF8：PowerShell 5.1 会加 BOM，
# 而 javac 的 @源列表、R8 的 proguard 配置都不认开头的 BOM。
# 为什么不用 -Encoding Default：中文系统是 GBK，会把中文注解写坏。
function WriteUtf8NoBom($path, $text) {
    [System.IO.File]::WriteAllText($path, [string]$text, (New-Object System.Text.UTF8Encoding($false)))
}

# 调用原生命令并返回 {Output, ExitCode}。
# 为什么需要它：PowerShell 5.1 把「原生命令写 stderr」视为 NativeCommandError，
# 配合 ErrorActionPreference='Stop' 会直接终止脚本。而 javac 的
# 「注: 某些输入文件使用已过时的 API」、aapt2 的版本号都走 stderr，
# 于是明明成功也会中断构建。5.1 没有 $PSNativeCommandUseErrorActionPreference，
# 只能在这一层临时降级。
function Invoke-Native {
    param([string]$Exe, [string[]]$ArgList)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Exe @ArgList 2>&1 | Out-String
        return @{ Output = $out; ExitCode = $LASTEXITCODE }
    } finally {
        $ErrorActionPreference = $prev
    }
}

# ------------------------------------------------------------------ 路径
$Root   = $PSScriptRoot
$Parent = Split-Path -Parent $Root
$Out    = Join-Path $Root 'build'
$Resc   = Join-Path $Out 'resc'
$Gen    = Join-Path $Out 'gen'
$Obj    = Join-Path $Out 'obj'
$Dex    = Join-Path $Out 'dex'
$Tmp    = Join-Path $Out 'tmp'
$Dist   = Join-Path $Root 'dist'   # 历史版本归档，不随 -Clean 清除

# ------------------------------------------------------------------ 环境探测
# toolchain 布局（便携式，整个 BiliMusicDev 文件夹可直接删掉）：
#   toolchain\jdk-17\bin\javac.exe
#   toolchain\sdk\build-tools\34.0.0\{aapt2,zipalign}.exe
#   toolchain\sdk\build-tools\34.0.0\lib\{d8,apksigner}.jar
#   toolchain\sdk\platforms\android-34\android.jar
if (-not $SkipChecks) {
    $tcRoot = Join-Path $Parent 'toolchain'
    if (-not $env:JAVA_HOME) {
        $cand = @(
            (Join-Path $tcRoot 'jdk-17'),
            (Join-Path $Root 'toolchain\jdk-17')
        ) | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\javac.exe') } | Select-Object -First 1
        if ($cand) { $env:JAVA_HOME = $cand }
    }
    if (-not $env:SDK) {
        $cand = @(
            (Join-Path $tcRoot 'sdk\build-tools\34.0.0'),
            (Join-Path $Root 'sdk')
        ) | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'aapt2.exe') } | Select-Object -First 1
        if ($cand) { $env:SDK = $cand }
    }
    if (-not $env:ANDROID_JAR) {
        $cand = @(
            (Join-Path $tcRoot 'sdk\platforms\android-34\android.jar'),
            (Join-Path $Root 'sdk\android.jar')
        ) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
        if ($cand) { $env:ANDROID_JAR = $cand }
    }
}

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    Fail "找不到 JDK。请设置 JAVA_HOME 指向 JDK 17 根目录。`n    期望位置: $Parent\toolchain\jdk-17"
}
$Java    = Join-Path $env:JAVA_HOME 'bin\java.exe'
$Javac   = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$Keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

if (-not $env:SDK -or -not (Test-Path -LiteralPath (Join-Path $env:SDK 'aapt2.exe'))) {
    Fail "找不到 Android build-tools。请设置 SDK 指向 build-tools\34.0.0 目录。`n    期望位置: $Parent\toolchain\sdk\build-tools\34.0.0"
}
$Aapt2     = Join-Path $env:SDK 'aapt2.exe'
$Zipalign  = Join-Path $env:SDK 'zipalign.exe'
$D8Jar     = Join-Path $env:SDK 'lib\d8.jar'
$SignerJar = Join-Path $env:SDK 'lib\apksigner.jar'
$ZXing     = Join-Path $env:SDK 'zxing-core.jar'

if (-not $env:ANDROID_JAR -or -not (Test-Path -LiteralPath $env:ANDROID_JAR)) {
    Fail "找不到 android.jar。请设置 ANDROID_JAR。`n    期望位置: $Parent\toolchain\sdk\platforms\android-34\android.jar"
}

foreach ($f in @($Aapt2, $Zipalign, $D8Jar, $SignerJar, $ZXing, $env:ANDROID_JAR)) {
    if (-not (Test-Path -LiteralPath $f)) { Fail "缺少文件: $f" }
}

# ------------------------------------------------------------------ 版本号
$VersionCode = $env:VERSION_CODE
$VersionName = $env:VERSION_NAME
$verFile = Join-Path $Root 'version.properties'
if (Test-Path -LiteralPath $verFile) {
    foreach ($line in Get-Content -LiteralPath $verFile -Encoding UTF8) {
        if ($line -match '^\s*versionCode\s*=\s*(\d+)') { if (-not $VersionCode) { $VersionCode = $Matches[1] } }
        if ($line -match '^\s*versionName\s*=\s*(.+?)\s*$') { if (-not $VersionName) { $VersionName = $Matches[1] } }
    }
}
if (-not $VersionCode) { $VersionCode = '1' }
if (-not $VersionName) { $VersionName = '1.0.0' }

$MinSdk = 24
$TargetSdk = 34

Say ''
Say '=============================================='
Say ' BiliMusic 构建'
Say '=============================================='
Say "   Java : $env:JAVA_HOME"
Say "    SDK : $env:SDK"
Say "   版本 : $VersionName ($VersionCode)"
Say "   输出 : $Out"
Say ''

# ---- 历史版本保护（放在最前面，避免白编译一轮才发现版本号没升）----
# 规则（用户要求）：
#   1) 每次有更新都要递增版本号，否则新旧包无法区分
#   2) 新包生成后，旧版本不删除，一律留在 dist/ 里
# dist 里已有同名版本 => 说明版本号没升，直接拦下来，
# 避免把已经发布出去的包覆盖掉（会导致用户手里的版本无法追溯）。
New-Item -ItemType Directory -Path $Dist -Force | Out-Null
$archiveName = "BiliMusic-" + $VersionName + ".apk"
$archivePath = Join-Path $Dist $archiveName
if (Test-Path -LiteralPath $archivePath) {
    Say ''
    Say "!!! dist 里已经有 $archiveName" -ForegroundColor Yellow
    Say '    说明版本号没升，本次构建会覆盖已发布的包。' -ForegroundColor Yellow
    Say '    请先改 version.properties 再构建：' -ForegroundColor Yellow
    Say '      小更新 -> versionName 最后一位 +1（如 1.0.16 -> 1.0.17）' -ForegroundColor Yellow
    Say '      大更新 -> versionName 中间那位 +1（如 1.0.17 -> 1.1.0）' -ForegroundColor Yellow
    Say '      versionCode 每次都要 +1' -ForegroundColor Yellow
    Fail '版本号未递增，已中止（不允许覆盖历史包）'
}
Say "   归档 : $Dist\$archiveName"
# ------------------------------------------------------------------ 清理
if ($Clean -and (Test-Path -LiteralPath $Out)) {
    Remove-Item -LiteralPath $Out -Recurse -Force
}
foreach ($d in @($Resc, $Gen, $Obj, $Dex, $Tmp)) {
    if (Test-Path -LiteralPath $d) { Remove-Item -LiteralPath $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}

# ------------------------------------------------------------------ 1 编译资源
Say '== 1/8 编译资源 =='
$resDir = Join-Path $Root 'res'
$r = Invoke-Native $Aapt2 @('compile', '--dir', $resDir, '-o', $Resc)
if ($r.Output.Trim()) { ($r.Output -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*$' }) | ForEach-Object { Write-Host "   $_" } }
if ($r.ExitCode -ne 0) { Fail 'aapt2 compile 失败' }

# ------------------------------------------------------------------ 2 链接资源
Say '== 2/8 链接资源 =='
$flats = @(Get-ChildItem -LiteralPath $Resc -Filter *.flat -File | Sort-Object Name | ForEach-Object FullName)
if ($flats.Count -eq 0) { Fail '没有编译出任何 .flat 资源' }

$linkArgs = @(
    'link'
    '-I', $env:ANDROID_JAR
    '--manifest', (Join-Path $Root 'AndroidManifest.xml')
    '-o', (Join-Path $Out 'base.apk')
    '--java', $Gen
    # R 类固定生成在 com.star.bilimusic 下（Java 源码的包名没有改），
    # 而 AndroidManifest 的 package 已经换成 com.stars.bilimusic 用来区分安装身份。
    # 不加这一行的话 R 会跟着清单走，NotificationHelper 里的
    # import com.star.bilimusic.R 就会编译失败。
    '--custom-package', 'com.star.bilimusic'
    '--min-sdk-version', $MinSdk
    '--target-sdk-version', $TargetSdk
    '--version-code', $VersionCode
    '--version-name', $VersionName
)
# assets 目录可选：原来 build.sh 靠「失败后重试」，这里先判断再决定
$assetsDir = Join-Path $Root 'assets'
$hasAssets = (Test-Path -LiteralPath $assetsDir) -and ((Get-ChildItem -LiteralPath $assetsDir -Force | Measure-Object).Count -gt 0)
if ($hasAssets) { $linkArgs += @('-A', $assetsDir) }
$linkArgs += $flats

$r = Invoke-Native $Aapt2 $linkArgs
if ($r.Output.Trim()) { ($r.Output -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*$' }) | ForEach-Object { Write-Host "   $_" } }
if ($r.ExitCode -ne 0) { Fail 'aapt2 link 失败' }

# ------------------------------------------------------------------ 3 编译 Java
Say '== 3/8 编译 Java =='
$srcList = Join-Path $Out 'sources.txt'
$files = @()
$files += Get-ChildItem -LiteralPath (Join-Path $Root 'src') -Recurse -Filter *.java -File
$files += Get-ChildItem -LiteralPath $Gen -Recurse -Filter *.java -File -ErrorAction SilentlyContinue
if ($files.Count -eq 0) { Fail '没找到任何 .java 源文件' }

# javac 的 @文件 按行读，每行是一个参数。两个坑：
#   1) 路径含空格必须用引号包住，否则会被拆成两个 token
#   2) @文件 里反斜杠是转义符会被吃掉，所以统一换成正斜杠，
#      Java 在 Windows 上同样认正斜杠。
WriteUtf8NoBom $srcList (($files | ForEach-Object { '"' + ($_.FullName -replace '\\','/') + '"' }) -join "`r`n")

$javacLog = Join-Path $Out 'javac.log'
$javacArgs = @(
    '-encoding', 'UTF-8'
    '-nowarn'
    '-source', '8'
    '-target', '8'
    '-bootclasspath', $env:ANDROID_JAR
    '-classpath', "$env:ANDROID_JAR;$ZXing"
    '-d', $Obj
    "@$srcList"
)
$r = Invoke-Native $Javac $javacArgs
$javacOut = $r.Output
WriteUtf8NoBom $javacLog $javacOut
$javacRc = $r.ExitCode

# 判断成败以「是否产出 class」为准，而不是单看退出码：
# javac 会把「注: 某些输入文件使用已过时的 API」写到 stderr。
$clsCount = @(Get-ChildItem -LiteralPath $Obj -Recurse -Filter *.class -File -ErrorAction SilentlyContinue).Count
# 三重判据，任一成立就算失败：
#   1) javac 退出码非 0            —— 最可靠
#   2) 输出里出现「错误:」或 error: —— 兜底（退出码被包装层改写的极端情况）
#   3) 一个 class 都没产出          —— 防静默漏编
# 之前只靠正则，中文「错误:」因编码问题匹配不上，导致编译失败还打印 BUILD OK。
$hasRealError = ($javacRc -ne 0) -or
                ($javacOut -match '错误:') -or
                ($javacOut -match 'error:')
if ($hasRealError -or $clsCount -lt 1) {
    Say '!!! javac 失败，错误如下：'
    ($javacOut -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*注:' -and $_ -notmatch '^\s*Note:' } | Select-Object -First 30) | ForEach-Object { Write-Host "   $_" }
    exit 1
}
Say "   源文件 $($files.Count) 个，class $clsCount 个"
# 防漏编：源文件数与 class 数不该差太多。内部类会让 class 更多，
# 但绝不会少于源文件数——少了就说明有文件没编译成功。
if ($clsCount -lt $files.Count) {
    Fail ("可疑：源文件 {0} 个，却只产出 {1} 个 class，可能有文件漏编" -f $files.Count, $clsCount)
}

# ------------------------------------------------------------------ 4 转 dex
Say '== 4/8 转 dex (R8) =='
$keepFile = Join-Path $Out 'keep.txt'
$keepText = @'
-keep class com.star.bilimusic.** { *; }
# 二维码只用到 qrcode + common 包和少数顶层类，其余 ZXing 全部裁掉。
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
'@
WriteUtf8NoBom $keepFile $keepText

$classFiles = @(Get-ChildItem -LiteralPath $Obj -Recurse -Filter *.class -File | ForEach-Object FullName)

$r8Args = @(
    '-cp', $D8Jar
    'com.android.tools.r8.R8'
    '--release'
    '--lib', $env:ANDROID_JAR
    '--min-api', $MinSdk
    '--output', $Dex
    '--pg-conf', $keepFile
    $ZXing
) + $classFiles

$r = Invoke-Native $Java $r8Args
$dexPath = Join-Path $Dex 'classes.dex'
if (-not (Test-Path -LiteralPath $dexPath)) {
    Say '   R8 失败，退回 d8'
    ($r.Output -split "`r?`n" | Where-Object { $_ -match '(?i)error' } | Select-Object -First 5) | ForEach-Object { Write-Host "   $_" }
    $d8Args = @(
        '-cp', $D8Jar
        'com.android.tools.r8.D8'
        '--release'
        '--lib', $env:ANDROID_JAR
        '--min-api', $MinSdk
        '--output', $Dex
        $ZXing
    ) + $classFiles
    $r = Invoke-Native $Java $d8Args
    if (-not (Test-Path -LiteralPath $dexPath)) {
        Say '!!! R8 和 d8 都失败了'
        ($r.Output -split "`r?`n" | Where-Object { $_ } | Select-Object -First 20) | ForEach-Object { Write-Host "   $_" }
        exit 1
    }
}

# ------------------------------------------------------------------ 5 打包 dex
Say '== 5/8 打包 dex =='
# 原脚本用 python3 的 zipfile；这里改用 JDK 自带的 java.util.zip，
# 编译一个小工具完成同样的事，从而摆脱 python 依赖。
$packSrc = Join-Path $Tmp 'PackDex.java'
$packDexText = @'
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * 把 classes.dex 写进 aapt2 生成的 APK（本质是 zip）。
 * 替代原 build.sh 里的 python3 zipfile 段，让构建不依赖 python。
 */
public final class PackDex {
    public static void main(String[] args) throws Exception {
        String apkPath = args[0];
        String dexPath = args[1];
        File apk = new File(apkPath);
        File tmp = new File(apkPath + ".tmp");
        byte[] buf = new byte[8192];

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(apk));
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmp))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if ("classes.dex".equals(name)) continue;   // 旧 dex 丢掉
                ZipEntry out = new ZipEntry(name);
                out.setTime(0L);                            // 固定时间戳，构建可复现
                if (e.getMethod() == ZipEntry.STORED) {
                    // resources.arsc 是 STORED 的，必须保持 STORED，
                    // 否则部分系统会拒绝安装。STORED 需要预先知道大小和 CRC。
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int n;
                    while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                    byte[] data = bos.toByteArray();
                    out.setMethod(ZipEntry.STORED);
                    out.setSize(data.length);
                    out.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    out.setCrc(crc.getValue());
                    zout.putNextEntry(out);
                    zout.write(data);
                } else {
                    zout.putNextEntry(out);
                    int n;
                    while ((n = zin.read(buf)) > 0) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
            ZipEntry dexEntry = new ZipEntry("classes.dex");
            dexEntry.setTime(0L);
            zout.putNextEntry(dexEntry);
            Files.copy(Paths.get(dexPath), zout);
            zout.closeEntry();
        }

        Files.move(tmp.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        System.out.println(" dex packed");
    }
}
'@
WriteUtf8NoBom $packSrc $packDexText

$packOut = Join-Path $Tmp 'pack'
New-Item -ItemType Directory -Path $packOut -Force | Out-Null
$r = Invoke-Native $Javac @('-encoding', 'UTF-8', '-nowarn', '-d', $packOut, $packSrc)
if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $packOut 'PackDex.class'))) {
    Say '!!! PackDex 编译失败'
    ($r.Output -split "`r?`n" | Where-Object { $_ } | Select-Object -First 20) | ForEach-Object { Write-Host "   $_" }
    exit 1
}

$r = Invoke-Native $Java @('-cp', $packOut, 'PackDex', (Join-Path $Out 'base.apk'), $dexPath)
if ($r.Output.Trim()) { ($r.Output -split "`r?`n" | Where-Object { $_ }) | ForEach-Object { Write-Host "   $_" } }
if ($r.ExitCode -ne 0) { Fail '打包 dex 失败' }

# ------------------------------------------------------------------ 6 对齐
Say '== 6/8 对齐 =='
$aligned = Join-Path $Out 'aligned.apk'
if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned -Force }
$r = Invoke-Native $Zipalign @('-f', '-p', '4', (Join-Path $Out 'base.apk'), $aligned)
if ($r.Output.Trim()) { ($r.Output -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*$' }) | ForEach-Object { Write-Host "   $_" } }
if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $aligned)) { Fail 'zipalign 失败' }

# ------------------------------------------------------------------ 7 签名
Say '== 7/8 签名 =='
$ks = Join-Path $Root 'release.keystore'
$ksProp = Join-Path $Root 'keystore.properties'
$ksPass = $env:KS_PASS
$ksAlias = $env:KS_ALIAS
if (Test-Path -LiteralPath $ksProp) {
    foreach ($line in Get-Content -LiteralPath $ksProp -Encoding UTF8) {
        if ($line -match '^\s*KS_PASS\s*=\s*(.+?)\s*$') { if (-not $ksPass) { $ksPass = $Matches[1] } }
        if ($line -match '^\s*KS_ALIAS\s*=\s*(.+?)\s*$') { if (-not $ksAlias) { $ksAlias = $Matches[1] } }
    }
}
if (-not $ksPass)  { $ksPass = 'musicbox' }
if (-not $ksAlias) { $ksAlias = 'musicbox' }

if (-not (Test-Path -LiteralPath $ks)) {
    Say '   未找到 release.keystore，正在生成...'
    $r = Invoke-Native $Keytool @(
        '-genkeypair', '-v', '-keystore', $ks, '-alias', $ksAlias,
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10950',
        '-storepass', $ksPass, '-keypass', $ksPass,
        '-dname', 'CN=BiliMusic, OU=Dev, O=BiliMusic, L=Local, S=Local, C=CN', '-noprompt'
    )
    if ($r.ExitCode -ne 0) { Fail 'keystore 生成失败' }
}

$final = Join-Path $Out ("BiliMusic-" + $VersionName + ".apk")
if (Test-Path -LiteralPath $final) { Remove-Item -LiteralPath $final -Force }
$signArgs = @(
    '-jar', $SignerJar
    'sign'
    '--ks', $ks
    '--ks-key-alias', $ksAlias
    '--ks-pass', "pass:$ksPass"
    '--key-pass', "pass:$ksPass"
    '--v1-signing-enabled', 'false'
    '--v2-signing-enabled', 'true'
    '--v3-signing-enabled', 'true'
    '--out', $final
    $aligned
)
$r = Invoke-Native $Java $signArgs
if ($r.Output.Trim()) { ($r.Output -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*$' }) | ForEach-Object { Write-Host "   $_" } }
if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $final)) { Fail '签名失败' }

# ------------------------------------------------------------------ 8 校验
Say '== 8/8 校验 =='
$r = Invoke-Native $Java @('-jar', $SignerJar, 'verify', $final)
if ($r.Output.Trim()) { ($r.Output -split "`r?`n" | Where-Object { $_ -and $_ -notmatch '^\s*$' }) | ForEach-Object { Write-Host "   $_" } }
if ($r.ExitCode -ne 0) { Fail '签名校验失败' }
Say '   签名 OK'

$size = (Get-Item -LiteralPath $final).Length
Say ('   体积: {0:N0} 字节 ({1:N1} KB)' -f $size, ($size / 1KB))
Say ''
# 归档：新包复制一份到 dist/，旧的都不动
Copy-Item -LiteralPath $final -Destination $archivePath -Force
Say ''
Say "已归档到: $archivePath"
Say ''
Say '---- dist 里的历史版本 ----'
Get-ChildItem -LiteralPath $Dist -Filter *.apk -File |
    Sort-Object LastWriteTime |
    ForEach-Object { Say ('   {0,-28} {1,7:N1} KB' -f $_.Name, ($_.Length / 1KB)) }
Say ''
Say "BUILD OK -> $final"
