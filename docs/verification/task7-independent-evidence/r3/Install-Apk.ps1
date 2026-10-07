# R3定向装机：仅接受已独立构建的两种APK，卸载规定旧包后安装并拉回验证字节，不重跑无改构建。
param([ValidateSet('workspace','projection','workspace-final')][string]$Kind='workspace')
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Public="$task7Root/docs/verification/task7-independent-evidence/r3";$task7Private="$task7Root/.runtime/task7-independent/r3/resume"
$task7Adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
if(Test-Path "$task7Public/install-$Kind.json"){throw '禁止覆盖首次装机证据'}
$task7Apk=if($Kind -eq 'projection'){"$task7Root/.runtime/task7-delivery-r2/phone/android-app/app/build/outputs/apk/debug/app-debug.apk"}else{"$task7Root/android-app/app/build/outputs/apk/debug/app-debug.apk"}
$task7Expected=if($Kind -eq 'projection'){'DD6441AD4AEBB8EB87E30283884B4441F80D141B4A43A9D58C262B6D0927FF6C'}else{'54A8AAB1BACFD29422635D42A4AD8F97B27A07F2425FCBA772A21251FF91B95B'}
if((Get-FileHash -LiteralPath $task7Apk).Hash -ne $task7Expected){throw 'APK与独立构建记录不一致'}
# 明确卸载两个历史包，缺失包只记为已无安装；卸载后仍存在则停止。
foreach($task7Package in @('com.fandazi.app','com.dirror.music')){
 $null=& $task7Adb -s emulator-5554 uninstall $task7Package 2>&1
 if((& $task7Adb -s emulator-5554 shell pm path $task7Package) -match 'package:'){throw '旧包未移除'}
}
$task7AbsentAt=Get-Date -Format o
$task7Install=& $task7Adb -s emulator-5554 install $task7Apk
if($LASTEXITCODE -ne 0 -or $task7Install -notcontains 'Success'){throw '安装失败'}
$task7Path=(& $task7Adb -s emulator-5554 shell pm path com.fandazi.app).Trim().Replace('package:','')
$null=& $task7Adb -s emulator-5554 pull $task7Path "$task7Private/installed-$Kind.apk"
if($LASTEXITCODE -ne 0){throw '拉回APK失败'}
$task7Actual=(Get-FileHash "$task7Private/installed-$Kind.apk").Hash
if($task7Actual -ne $task7Expected){throw '设备APK不符'}
@{kind=$Kind;oldPackagesAbsentAt=$task7AbsentAt;installedAt=(Get-Date -Format o);source=$task7Apk;localSha256=$task7Expected;deviceSha256=$task7Actual;passed=$true} | ConvertTo-Json | Set-Content "$task7Public/install-$Kind.json" -Encoding utf8
Write-Output "R3 $Kind 安装完成，设备SHA一致"
