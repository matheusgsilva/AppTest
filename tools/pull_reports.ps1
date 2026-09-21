$ErrorActionPreference = "Stop"
$adb = "adb"
$out = Join-Path $PSScriptRoot "..\captures"
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "Pulling CameraFrameLab files..."
& $adb pull "/sdcard/DCIM/CameraFrameLab" (Join-Path $out "CameraFrameLab")
Write-Host "Done: $out"
