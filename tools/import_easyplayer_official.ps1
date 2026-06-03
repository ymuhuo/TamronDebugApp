param(
    [string]$RepoZipUrl = "https://github.com/EasyDarwin/EasyPlayer-RTSP-Android/archive/refs/heads/dev.zip"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Tmp = Join-Path $Root ".tmp_easyplayer_import"
$Zip = Join-Path $Tmp "easyplayer-dev.zip"
$Extract = Join-Path $Tmp "extract"
$Target = Join-Path $Root "easyplayer-android\src\main"

Write-Host "Root: $Root"
Write-Host "Download: $RepoZipUrl"

Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Tmp | Out-Null
New-Item -ItemType Directory -Force $Extract | Out-Null

Invoke-WebRequest -Uri $RepoZipUrl -OutFile $Zip
Expand-Archive -Force -Path $Zip -DestinationPath $Extract

$LibraryMain = Get-ChildItem -Recurse -Directory $Extract | Where-Object {
    $_.FullName -like "*EasyPlayer-RTSP-Android-*\library\src\main"
} | Select-Object -First 1

if (-not $LibraryMain) {
    throw "未找到官方 library/src/main，请检查下载包结构。"
}

Write-Host "Official library: $($LibraryMain.FullName)"
Remove-Item -Recurse -Force $Target -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Target | Out-Null
Copy-Item -Recurse -Force (Join-Path $LibraryMain.FullName "*") $Target

# 官方老代码使用 android.support.annotation，现代工程统一替换为 androidx.annotation
Get-ChildItem -Recurse -Path $Target -Include *.java | ForEach-Object {
    $text = Get-Content $_.FullName -Raw
    $text = $text -replace "android\.support\.annotation", "androidx.annotation"
    Set-Content -Path $_.FullName -Value $text -Encoding UTF8
}

python (Join-Path $Root "tools\verify_easyplayer_official.py")
Write-Host "EasyPlayer 官方源码与 so 已导入完成。"
