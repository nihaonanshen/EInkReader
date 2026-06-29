# EInkReader Rust 交叉编译脚本 (Windows PowerShell 版)
# 需要在安装了 Android NDK + Rust Android targets 的环境中运行

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$CrateDir = Join-Path $ScriptDir "einkreader-core"
$JniLibDir = Join-Path $ScriptDir "..\app\src\main\jniLibs"

Write-Host "=== EInkReader Rust Core Build ==="

# 检查 cargo
if (!(Get-Command cargo -ErrorAction SilentlyContinue)) {
    Write-Error "cargo not found. Install Rust: https://rustup.rs"
    exit 1
}

# 确保 Android 目标已安装
Write-Host "=== Checking Rust Android targets ==="
$targets = @("aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android", "i686-linux-android")
$installed = rustup target list --installed
foreach ($t in $targets) {
    if ($installed -notcontains $t) {
        Write-Host "Installing target: $t"
        rustup target add $t
    }
}

# 尝试自动查找 NDK
$ndkBase = Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk"
if (!(Test-Path $ndkBase)) {
    $ndkBase = Join-Path $env:ProgramFiles "Android\Sdk\ndk"
}
if (Test-Path $ndkBase) {
    $ndkDirs = Get-ChildItem $ndkBase -Directory
    if ($ndkDirs) {
        $env:ANDROID_NDK_HOME = $ndkDirs[0].FullName
        Write-Host "Found NDK: $env:ANDROID_NDK_HOME"
    }
}

if (-not $env:ANDROID_NDK_HOME) {
    Write-Warning "ANDROID_NDK_HOME not set. Cross-compilation may fail."
    Write-Warning "Set it via: `$env:ANDROID_NDK_HOME = `"path\to\ndk`""
}

Set-Location $CrateDir

Write-Host "=== Building for arm64-v8a ==="
cargo build --target aarch64-linux-android --release

Write-Host "=== Building for armeabi-v7a ==="
cargo build --target armv7-linux-androideabi --release

Write-Host "=== Copying .so files ==="
$null = New-Item -ItemType Directory -Force (Join-Path $JniLibDir "arm64-v8a")
$null = New-Item -ItemType Directory -Force (Join-Path $JniLibDir "armeabi-v7a")
$null = New-Item -ItemType Directory -Force (Join-Path $JniLibDir "x86_64")

Copy-Item "$CrateDir\target\aarch64-linux-android\release\libeinkreader_core.so" (Join-Path $JniLibDir "arm64-v8a")
Copy-Item "$CrateDir\target\armv7-linux-androideabi\release\libeinkreader_core.so" (Join-Path $JniLibDir "armeabi-v7a")
Copy-Item "$CrateDir\target\x86_64-linux-android\release\libeinkreader_core.so" (Join-Path $JniLibDir "x86_64")

Write-Host "=== Done! ==="
Get-ChildItem -Path $JniLibDir -Recurse -Filter "*.so" | Select-Object FullName, Length
