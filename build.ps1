<#
  AI-ssistants - build a standalone rooted-device AI assistant APK.

    .\build.ps1              build + sign  ->  release\AI-ssistants-v<ver>.apk
    .\build.ps1 -Deploy      also `adb install -r` the APK
    .\build.ps1 -NoClean     skip the clean step (faster when nothing changed)

  No Gradle: aapt2 + javac + d8 + zipalign + apksigner, same proven pipeline the
  Causentry module uses. The Android SDK is taken from, in order:
  $env:AI_SSISTANTS_SDK, the sibling Causentry checkout, %LOCALAPPDATA%\Android\Sdk.
#>
[CmdletBinding()]
param(
  [switch]$Deploy,
  [switch]$NoClean,
  [string]$Version,
  [string]$Sdk,
  [string]$Serial
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$Src = Join-Path $Root "app-src"
$Build = Join-Path $Root "build"
$Release = Join-Path $Root "release"
$Ks = Join-Path $Root "ai-ssistants.keystore"
$KsAlias = "aissistants"
$KsPass = "aissistants"

function Say($m, $c = "Gray") { Write-Host $m -ForegroundColor $c }
function Die($m) { Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

function Read-Version {
  $mf = Join-Path $Src "manifest\AndroidManifest.xml"
  $m = Select-String -Path $mf -Pattern 'android:versionName="([^"]+)"' | Select-Object -First 1
  if ($m) { return $m.Matches[0].Groups[1].Value }
  return "1.0.0"
}

function Find-Java {
  if ($env:JAVA_HOME) {
    $c = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $c) { return $c }
  }
  $cands = @()
  $cands += Get-ChildItem "C:\Program Files\Java" -Filter "jdk*" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "bin\java.exe" }
  $cands += Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Filter "jdk*" -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName "bin\java.exe" }
  foreach ($c in $cands) { if (Test-Path $c) { return $c } }
  $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($onPath) { return $onPath.Source }
  Die "no JDK found - install JDK 17+ or set JAVA_HOME"
}

if (-not $Version) { $Version = Read-Version }

$Java = Find-Java
$JavaBin = Split-Path -Parent $Java
$Javac = Join-Path $JavaBin "javac.exe"
$Keytool = Join-Path $JavaBin "keytool.exe"

if (-not $Sdk) {
  $Sdk = $env:AI_SSISTANTS_SDK
  if (-not $Sdk) {
    $sibling = Join-Path (Split-Path -Parent $Root) "Causentry\tools\sdk"
    if (Test-Path $sibling) { $Sdk = $sibling }
  }
  if (-not $Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk" }
}

$Bt = Join-Path $Sdk "build-tools\35.0.0"
$AJar = Join-Path $Sdk "platforms\android-35\android.jar"
$aapt2 = Join-Path $Bt "aapt2.exe"
$zipalign = Join-Path $Bt "zipalign.exe"
$d8Jar = Join-Path $Bt "lib\d8.jar"
$signerJar = Join-Path $Bt "lib\apksigner.jar"

foreach ($f in @($Javac, $aapt2, $zipalign, $d8Jar, $signerJar, $AJar)) {
  if (-not (Test-Path $f)) { Die "missing toolchain file: $f`n     install build-tools 35.0.0 + platform android-35, or set AI_SSISTANTS_SDK" }
}

Say "== AI-ssistants build ==" 
Say "   root    : $Root"
Say "   java    : $Java"
Say "   sdk     : $Sdk"
Say "   version : v$Version"
New-Item -ItemType Directory -Force -Path $Release | Out-Null

if (-not $NoClean -and (Test-Path $Build)) {
  Get-ChildItem -Force $Build | ForEach-Object {
    try { Remove-Item -Recurse -Force $_.FullName -ErrorAction Stop } catch { Write-Host "   (skip locked: $($_.Name))" }
  }
}
foreach ($d in "classes", "dex", "gen", "apk", "res") {
  New-Item -ItemType Directory -Force -Path (Join-Path $Build $d) | Out-Null
}

Say "[0/6] aapt2 resources -> R.java (javac needs it, so it must run before the compile)"
& $aapt2 compile --dir (Join-Path $Src "res") -o (Join-Path $Build "res.zip")
if ($LASTEXITCODE -ne 0) { Die "aapt2 compile failed" }
& $aapt2 link -o (Join-Path $Build "apk\base.apk") -I $AJar `
  --manifest (Join-Path $Src "manifest\AndroidManifest.xml") `
  --java (Join-Path $Build "gen") `
  --min-sdk-version 26 --target-sdk-version 35 --no-version-vectors (Join-Path $Build "res.zip")
if ($LASTEXITCODE -ne 0) { Die "aapt2 link failed" }
$genR = Get-ChildItem (Join-Path $Build "gen") -Recurse -Filter R.java -ErrorAction SilentlyContinue
if (-not $genR) { Die "aapt2 did not generate R.java under $Build\gen" }

Say "[1/6] compiling java"
$javaSrc = (Get-ChildItem (Join-Path $Src "src") -Recurse -Filter *.java).FullName
if (-not $javaSrc) { Die "no sources under $Src\src" }
# R.java lives in build\gen (outside src), so add the generated sources to the compile set
$javaSrc += (Get-ChildItem (Join-Path $Build "gen") -Recurse -Filter *.java -ErrorAction SilentlyContinue).FullName
& $Javac -nowarn --release 11 -cp $AJar -d (Join-Path $Build "classes") @javaSrc
if ($LASTEXITCODE -ne 0) { Die "javac failed" }

Say "[2/6] dexing (d8)"
$classes = (Get-ChildItem (Join-Path $Build "classes") -Recurse -Filter *.class).FullName
& $Java -cp $d8Jar com.android.tools.r8.D8 --release --min-api 26 --lib $AJar --output (Join-Path $Build "dex") @classes
if ($LASTEXITCODE -ne 0) { Die "d8 failed" }

Say "[3/6] aapt2 compile + link"
& $aapt2 compile --dir (Join-Path $Src "res") -o (Join-Path $Build "res.zip")
if ($LASTEXITCODE -ne 0) { Die "aapt2 compile failed" }
& $aapt2 link -o (Join-Path $Build "apk\base.apk") -I $AJar `
  --manifest (Join-Path $Src "manifest\AndroidManifest.xml") `
  --java (Join-Path $Build "gen") `
  --min-sdk-version 26 --target-sdk-version 35 --no-version-vectors (Join-Path $Build "res.zip")
if ($LASTEXITCODE -ne 0) { Die "aapt2 link failed" }

Say "[4/6] injecting classes.dex"
Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
$dexed = Join-Path $Build "apk\dexed.apk"
Copy-Item (Join-Path $Build "apk\base.apk") $dexed -Force
$dexBytes = [IO.File]::ReadAllBytes((Join-Path $Build "dex\classes.dex"))
$za = [System.IO.Compression.ZipFile]::Open($dexed, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  $old = $za.GetEntry("classes.dex"); if ($old) { $old.Delete() }
  $e = $za.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
  $s = $e.Open(); $s.Write($dexBytes, 0, $dexBytes.Length); $s.Dispose()
} finally { $za.Dispose() }

Say "[5/6] zipalign"
$aligned = Join-Path $Build "apk\aligned.apk"
& $zipalign -f -p 4 $dexed $aligned
if ($LASTEXITCODE -ne 0) { Die "zipalign failed" }

Say "[6/6] signing"
if (-not (Test-Path $Ks)) {
  Say "      creating keystore (alias $KsAlias)"
  & $Keytool -genkeypair -keystore $Ks -alias $KsAlias -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $KsPass -keypass $KsPass -dname "CN=AI-ssistants,O=AI-ssistants,C=ID"
  if ($LASTEXITCODE -ne 0) { Die "keytool failed" }
}
$outApk = Join-Path $Release "AI-ssistants-v$Version.apk"
& $Java -jar $signerJar sign --ks $Ks --ks-key-alias $KsAlias `
  --ks-pass "pass:$KsPass" --key-pass "pass:$KsPass" --out $outApk $aligned
if ($LASTEXITCODE -ne 0) { Die "apksigner failed" }
& $Java -jar $signerJar verify $outApk | Out-Null
if ($LASTEXITCODE -ne 0) { Die "apk verification failed" }

$size = (Get-Item $outApk).Length
Say "APK : $outApk ($([math]::Round($size/1KB,1)) KB)" "Green"

if ($Deploy) {
  $adbArgs = @()
  if ($Serial) { $adbArgs += @("-s", $Serial) }
  & adb @adbArgs install -r $outApk
  if ($LASTEXITCODE -ne 0) { Die "adb install failed" }
  Say "installed on device" "Green"
}
Say "DONE" "Green"
