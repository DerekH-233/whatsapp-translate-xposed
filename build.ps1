# WhatsApp 翻译助手 - Gradle-free build
# javac -> d8 -> aapt2 compile/link -> repack(assets+dex) -> zipalign -> apksigner
#
# Requirements: JDK 17+, Android SDK (build-tools + platforms/android-34), Python 3.
# Nothing else, and no committed keystore: one is generated locally on first run.
param(
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Find-Sdk {
    $cands = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $env:ANDROID_SDK)
    $cands += @(
        'C:\Users\DKSan\scoop\apps\android-clt\current',
        "$env:LOCALAPPDATA\Android\Sdk",
        'C:\Android\Sdk'
    )
    foreach ($c in $cands) {
        if ($c -and (Test-Path (Join-Path $c 'platforms\android-34\android.jar'))) { return $c }
    }
    throw 'Android SDK not found. Set ANDROID_HOME to a SDK containing platforms/android-34.'
}

$SDK  = Find-Sdk
$btDir = Get-ChildItem (Join-Path $SDK 'build-tools') -Directory |
         Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$BT   = $btDir.FullName
$PLAT = "$SDK\platforms\android-34\android.jar"
$D8   = Get-ChildItem (Join-Path $SDK 'cmdline-tools') -Recurse -Filter 'd8.bat' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
$JAVA = (Get-Command javac -ErrorAction SilentlyContinue).Source
$JAR  = (Get-Command jarsigner -ErrorAction SilentlyContinue).Source
if (-not $JAVA) { throw 'javac not found on PATH.' }
if (-not $JAR) { throw 'jarsigner not found on PATH.' }

# Signing key resolution, in order:
#   1. $env:LSTRANS_KEYSTORE            (explicit override)
#   2. ../watrans.keystore              (existing local install, keeps upgrade path)
#   3. ./debug.keystore                 (generated on first build, gitignored)
# Never commit any of them.
$ksOverride = $env:LSTRANS_KEYSTORE
$parentKs = Join-Path (Split-Path -Parent $root) 'watrans.keystore'
if ($ksOverride -and (Test-Path $ksOverride)) {
    $KS = $ksOverride; $KSPW = 'watrans123'; $KSAL = 'watrans'
} elseif (Test-Path $parentKs) {
    $KS = $parentKs; $KSPW = 'watrans123'; $KSAL = 'watrans'
} else {
    $KS = Join-Path $root 'debug.keystore'; $KSPW = 'android'; $KSAL = 'androiddebugkey'
    if (-not (Test-Path $KS)) {
        $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
        if (-not $keytool) { throw 'keytool not found; install a JDK.' }
        Write-Host '[build] no keystore found -> generating local debug.keystore'
        & $keytool -genkeypair -keystore $KS -alias $KSAL -keyalg RSA -keysize 2048 `
            -validity 10950 -storepass $KSPW -keypass $KSPW `
            -dname 'CN=WhatsApp Translate Debug, O=Local, C=CN' | Out-Null
    }
}
Write-Host "[build] keystore: $KS (alias $KSAL)"

$out      = Join-Path $root 'build'
$clsStub  = Join-Path $out 'classes-stub'
$clsApp   = Join-Path $out 'classes-app'
$dexDir   = Join-Path $out 'dex'
$flatDir  = Join-Path $out 'res-flat'
$genDir   = Join-Path $out 'gen'
$outApk   = Join-Path $root 'LSTrans-unsigned.apk'
$finalApk = Join-Path $root 'LSTrans.apk'

# Wipe only the directories this script owns; build/ itself also holds the
# downloaded libxposed jars, which must survive between runs.
foreach ($d in @($clsStub, $clsApp, $dexDir, $flatDir, $genDir)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Force -Path $out | Out-Null }

function Say($m) { Write-Host "[build] $m" }

# libxposed Modern API artifacts, fetched by tools/fetch_libxposed.ps1.
$lxDir  = Join-Path $out 'libxposed'
$lxApi  = Join-Path $lxDir 'api.jar'
$lxSvc  = Join-Path $lxDir 'service.jar'
if (-not (Test-Path $lxApi) -or -not (Test-Path $lxSvc)) {
    throw "libxposed jars missing; run tools\fetch_libxposed.ps1 first"
}

# 1) compile app sources against android.jar + the libxposed API.
#    The API is compile-only: the framework supplies those classes at runtime,
#    and bundling them would break the module's own classloader.
$appFiles = Get-ChildItem -Path (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Say "javac app ($($appFiles.Count) files)"
& $JAVA -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath $PLAT `
    -classpath "$lxApi;$lxSvc" -d $clsApp @appFiles
if ($LASTEXITCODE -ne 0) { throw 'javac app failed' }

# 2) dex the app classes plus the service artifact (its XposedProvider has to
#    ship inside the APK so the framework can hand us a service binder).
Say 'd8'
$appCls = Get-ChildItem -Path $clsApp -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $D8 --min-api 29 --output $dexDir --lib $PLAT --no-desugaring @appCls $lxSvc
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Get-ChildItem $dexDir | ForEach-Object { Say "  dex: $($_.Name) $($_.Length) bytes" }

# 4) compile + link resources + manifest -> base apk
Say 'aapt2 compile'
$flatZip = Join-Path $out 'res-flat.zip'
& "$BT\aapt2.exe" compile --dir (Join-Path $root 'res') -o $flatZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Say 'aapt2 link'
& "$BT\aapt2.exe" link `
    -I $PLAT `
    --manifest (Join-Path $root 'AndroidManifest.xml') `
    --java $genDir `
    --min-sdk-version 29 `
    --target-sdk-version 34 `
    --version-code 3 `
    --version-name 1.2.0 `
    -o $outApk `
    $flatZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# 5) add classes.dex, assets/ and META-INF/xposed/ into the linked apk
Say 'package assets + dex + META-INF'
$repack = Join-Path $root 'repack.py'
$dexPath = Join-Path $dexDir 'classes.dex'
$assetsPath = Join-Path $root 'assets'
python $repack $outApk $dexPath $assetsPath $root
if ($LASTEXITCODE -ne 0) { throw 'repack failed' }

# 6) align + sign
Say 'zipalign'
$aligned = Join-Path $out 'aligned.apk'
if (Test-Path $aligned) { Remove-Item $aligned -Force }
& "$BT\zipalign.exe" -f -p 4 $outApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Say 'sign (v2+v3)'
if (Test-Path $finalApk) { Remove-Item $finalApk -Force }
& "$BT\apksigner.bat" sign --ks $KS --ks-pass "pass:$KSPW" --ks-key-alias $KSAL `
    --key-pass "pass:$KSPW" --v2-signing-enabled true --v3-signing-enabled true `
    --out $finalApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& "$BT\apksigner.bat" verify $finalApk | Out-Null
Say "verify exit=$LASTEXITCODE"
$h = (Get-FileHash $finalApk -Algorithm SHA256).Hash.ToLower()
$len = (Get-Item $finalApk).Length
Say "OK -> $finalApk ($len bytes)"
Say "sha256 $h"

if (-not $SkipInstall) {
    Say 'adb install -r'
    adb install -r -d $finalApk | ForEach-Object { Say "  $_" }
}
