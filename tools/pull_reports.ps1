$ErrorActionPreference = "Stop"
$adb = "adb"
$out = Join-Path $PSScriptRoot "..\captures"
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "Pulling CameraFrameLab reports..."
& $adb pull "/sdcard/DCIM/CameraFrameLab" (Join-Path $out "reports")
Write-Host "Pulling CameraFrameLab videos..."
& $adb pull "/sdcard/Movies/CameraFrameLab" (Join-Path $out "videos")
Write-Host "Done: $out"
