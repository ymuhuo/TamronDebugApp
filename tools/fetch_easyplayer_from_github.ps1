$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ThirdPartyDir = Join-Path $RootDir "third_party"
$RepoDir = Join-Path $ThirdPartyDir "EasyPlayer-RTSP-Android"

New-Item -ItemType Directory -Force -Path $ThirdPartyDir | Out-Null

if (Test-Path (Join-Path $RepoDir ".git")) {
    git -C $RepoDir pull --ff-only
} else {
    git clone --depth 1 --branch dev https://github.com/EasyDarwin/EasyPlayer-RTSP-Android.git $RepoDir
}

Write-Host ""
Write-Host "官方 EasyPlayer 已获取到：$RepoDir"
Write-Host "官方工程的 library 模块位于：$(Join-Path $RepoDir 'library')"
Write-Host "注意：官方模块较旧，可能包含 Android support 依赖、旧 compileSdk 和 native so。建议先保留当前 :easyplayer-android 兼容模块。"
