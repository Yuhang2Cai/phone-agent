# Task7 最新 APK 安装：先卸载产品旧包及干扰包，核对不存在后安装，并比对设备实际 APK 字节。
$ErrorActionPreference='Stop'
$adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$root='C:/fdz-build'
$apk="$root/android-app/app/build/outputs/apk/debug/app-debug.apk"
$before=@{}
foreach($package in @('com.fandazi.app','com.dirror.music')) {
    $before[$package]=@(& $adb -s emulator-5554 shell pm path $package)
    $null=& $adb -s emulator-5554 uninstall $package 2>&1
    if((& $adb -s emulator-5554 shell pm path $package) -match 'package:') { throw '卸载后仍存在旧包' }
}
# 设备网络配置只采集状态，不擅自更改；后续断网实验必须恢复本次初始值。
$network=@{wifi=(& $adb -s emulator-5554 shell settings get global wifi_on);data=(& $adb -s emulator-5554 shell settings get global mobile_data);airplane=(& $adb -s emulator-5554 shell settings get global airplane_mode_on)}
$result=& $adb -s emulator-5554 install $apk
if($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') { throw '最新 APK 安装失败' }
$path=(& $adb -s emulator-5554 shell pm path com.fandazi.app).Trim().Replace('package:','')
$null=& $adb -s emulator-5554 pull $path "$root/.runtime/task7/device-installed.apk"
if($LASTEXITCODE -ne 0) { throw '无法取得设备安装字节' }
$local=(Get-FileHash -LiteralPath $apk).Hash.ToLowerInvariant()
$device=(Get-FileHash -LiteralPath "$root/.runtime/task7/device-installed.apk").Hash.ToLowerInvariant()
if($local -ne $device) { throw '本地与设备 APK 摘要不一致' }
@{installedAt=(Get-Date -Format o);apkSha256=$local;deviceSha256=$device;beforePackages=$before;afterOldPackagesAbsent=$true;initialNetwork=$network} | ConvertTo-Json -Depth 5 | Set-Content "$root/docs/verification/task7-evidence/apk-installed.json" -Encoding utf8
Write-Output "Task7 APK installed and matched: $local"
