param(
    [string]$Gradle = 'gradle',
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Keystore = "$env:USERPROFILE/.android/debug.keystore"
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & $Gradle assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)." }
    New-Item -ItemType Directory -Force dist | Out-Null
    $signer = Join-Path $Sdk 'build-tools/36.0.0/apksigner.bat'
    & $signer sign --ks $Keystore --ks-pass pass:android --ks-key-alias androiddebugkey --out dist/OfflineBookConverter-Android-0.1.16.apk build/outputs/apk/debug/OfflineBookConverter-debug-unsigned.apk
    if ($LASTEXITCODE -ne 0) { throw "APK signing failed ($LASTEXITCODE)." }
    & $signer verify dist/OfflineBookConverter-Android-0.1.16.apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
} finally { Pop-Location }
