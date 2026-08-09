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
$targets = @("aarch64-linux-android", "armv7-linux-androideabi")
$installed = rustup target list --installed
foreach ($t in $targets) {
    if ($installed -notcontains $t) {
        Write-Host "Installing target: $t"
        rustup target add $t
    }
}

# ⚠️ Android 4.4 (API 19) 兼容性：
# NDK 27 最低支持 API 21，编出的 .so 引用 dl_iterate_phdr（API 19 bionic 无此符号，
# 且 Android linker 在 dlopen 时强制解析所有 UND 符号，--allow-shlib-undefined 无效）
# → 加载失败回退 Java。必须：
#   1) 优先使用 NDK r20b（含 android-19 平台库）
#   2) 链接 dl_stub.o（在 .so 内部提供 dl_iterate_phdr 实现，返回 0——
#      该函数仅用于 Rust std backtrace 符号化，正常运行不影响任何功能）
$StubObj = Join-Path $ScriptDir "stub\dl_stub.o"
if (Test-Path $StubObj) {
    $env:RUSTFLAGS = "-C link-arg=$StubObj"
    Write-Host "Using dl_iterate_phdr stub: $StubObj"
} else {
    Write-Warning "dl_stub.o 不存在（先编译 stub），构建可能失败"
}

# 优先 r20b，其次 r21+（按版本号从低到高自动选择，最低支持 API 19）
$ndkBase = Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk"
if (!(Test-Path $ndkBase)) {
    $ndkBase = Join-Path $env:ProgramFiles "Android\Sdk\ndk"
}
$ndkDirs = @()
if (Test-Path $ndkBase) {
    $ndkDirs = Get-ChildItem $ndkBase -Directory | Where-Object {
        $_.Name -match '^android-ndk-r(\d+)'
    } | Sort-Object { [int]($_.Name -replace '^android-ndk-r(\d+).*', '$1') }
}
# 排除仍为 .zip 的残留目录（解压中断产物）
$ndkDirs = $ndkDirs | Where-Object { Test-Path (Join-Path $_.FullName "source.properties") }

if ($ndkDirs) {
    $env:ANDROID_NDK_HOME = $ndkDirs[0].FullName
    Write-Host "Found NDK: $env:ANDROID_NDK_HOME"
} else {
    Write-Warning "No usable NDK found under $ndkBase"
}

if (-not $env:ANDROID_NDK_HOME) {
    Write-Warning "ANDROID_NDK_HOME not set. Cross-compilation may fail."
    Write-Warning "Set it via: `$env:ANDROID_NDK_HOME = `"path\to\ndk`""
} else {
    # 配置 Windows 交叉编译工具链（clang.cmd 包装器；ar 用对应版本）
    $NdkBin = Join-Path $env:ANDROID_NDK_HOME "toolchains\llvm\prebuilt\windows-x86_64\bin"
    if (Test-Path (Join-Path $NdkBin "aarch64-linux-android21-clang.cmd")) {
        Write-Host "NDK toolchain: $NdkBin"
        $env:CC_aarch64_linux_android = Join-Path $NdkBin "aarch64-linux-android21-clang.cmd"
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $NdkBin "aarch64-linux-android21-clang.cmd"
        $env:CC_armv7_linux_androideabi = Join-Path $NdkBin "armv7a-linux-androideabi21-clang.cmd"
        $env:CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER = Join-Path $NdkBin "armv7a-linux-androideabi21-clang.cmd"
        # ar：r20b 用 arm-linux-androideabi-ar，r21+ 用 llvm-ar
        if (Test-Path (Join-Path $NdkBin "arm-linux-androideabi-ar.exe")) {
            $env:AR_armv7_linux_androideabi = Join-Path $NdkBin "arm-linux-androideabi-ar.exe"
            $env:AR_aarch64_linux_android = Join-Path $NdkBin "aarch64-linux-android-ar.exe"
        } else {
            $env:AR_armv7_linux_androideabi = Join-Path $NdkBin "llvm-ar.exe"
            $env:AR_aarch64_linux_android = Join-Path $NdkBin "llvm-ar.exe"
        }
    }
}

Set-Location $CrateDir

Write-Host "=== Building for arm64-v8a ==="
# 只构建 lib（bin test_books 的 -march 参数在 r20b 下与 .cmd 包装器有兼容问题，且不需要）
cargo build --target aarch64-linux-android --release --lib

Write-Host "=== Building for armeabi-v7a ==="
cargo build --target armv7-linux-androideabi --release --lib

Write-Host "=== Copying .so files ==="
$null = New-Item -ItemType Directory -Force (Join-Path $JniLibDir "arm64-v8a")
$null = New-Item -ItemType Directory -Force (Join-Path $JniLibDir "armeabi-v7a")

Copy-Item "$CrateDir\target\aarch64-linux-android\release\libeinkreader_core.so" (Join-Path $JniLibDir "arm64-v8a")
Copy-Item "$CrateDir\target\armv7-linux-androideabi\release\libeinkreader_core.so" (Join-Path $JniLibDir "armeabi-v7a")

Write-Host "=== Done! ==="
Get-ChildItem -Path $JniLibDir -Recurse -Filter "*.so" | Select-Object FullName, Length
