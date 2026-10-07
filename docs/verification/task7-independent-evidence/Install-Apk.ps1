# 独立验收安装：先卸载两个规定包，再安装本轮最新构建，并拉取设备 APK 验证字节。
$ErrorActionPreference='Stop'
$task7Adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$task7Root='C:/fdz-build';$task7Public="$task7Root/docs/verification/task7-independent-evidence";$task7Private="$task7Root/.runtime/task7-independent"
$task7Apk="$task7Root/android-app/app/build/outputs/apk/debug/app-debug.apk"
$task7Before=@{}
foreach($task7Package in @('com.fandazi.app','com.dirror.music')){
 $task7Before[$task7Package]=@(& $task7Adb -s emulator-5554 shell pm path $task7Package)
 $null=& $task7Adb -s emulator-5554 uninstall $task7Package 2>&1
 if((& $task7Adb -s emulator-5554 shell pm path $task7Package) -match 'package:'){throw '旧包未移除'}
}
$task7AbsentAt=Get-Date -Format o
$task7Network=@{wifi=(& $task7Adb -s emulator-5554 shell settings get global wifi_on);data=(& $task7Adb -s emulator-5554 shell settings get global mobile_data);airplane=(& $task7Adb -s emulator-5554 shell settings get global airplane_mode_on);activeDefaultNetwork=(& $task7Adb -s emulator-5554 shell dumpsys connectivity | Select-String 'Active default network').Line}
$task7Install=& $task7Adb -s emulator-5554 install $task7Apk
if($LASTEXITCODE -ne 0 -or $task7Install -notcontains 'Success'){throw '安装失败'}
$task7Path=(& $task7Adb -s emulator-5554 shell pm path com.fandazi.app).Trim().Replace('package:','')
$null=& $task7Adb -s emulator-5554 pull $task7Path "$task7Private/device-installed.apk"
if($LASTEXITCODE -ne 0){throw '设备APK拉取失败'}
$task7Local=(Get-FileHash $task7Apk).Hash;$task7Device=(Get-FileHash "$task7Private/device-installed.apk").Hash
if($task7Local -ne $task7Device){throw 'APK字节不一致'}
@{installedAt=(Get-Date -Format o);oldPackagesAbsentAt=$task7AbsentAt;beforePackages=$task7Before;apkSha256=$task7Local;deviceSha256=$task7Device;initialNetwork=$task7Network} | ConvertTo-Json -Depth 5 | Tee-Object -FilePath "$task7Public/apk-installed.json"
