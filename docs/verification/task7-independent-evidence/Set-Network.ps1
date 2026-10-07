# 独立真实网络验证：关闭或恢复默认网络，实际 dumpsys 为准，不用开关值代替断网事实。
param([ValidateSet('off','on')][string]$Mode,[string]$Label)
$ErrorActionPreference='Stop'
$task7Adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
if($Mode -eq 'off'){
 & $task7Adb -s emulator-5554 shell cmd connectivity airplane-mode enable
 & $task7Adb -s emulator-5554 shell svc wifi disable
 & $task7Adb -s emulator-5554 shell svc data disable
}else{
 & $task7Adb -s emulator-5554 shell cmd connectivity airplane-mode disable
 & $task7Adb -s emulator-5554 shell svc wifi enable
 & $task7Adb -s emulator-5554 shell svc data enable
}
# 网络切换异步完成，最多等20秒；未达到真实预期则保留状态并失败。
$task7Reached=$false
for($task7Attempt=0;$task7Attempt -lt 20;$task7Attempt++){
 $task7Default=(& $task7Adb -s emulator-5554 shell dumpsys connectivity | Select-String 'Active default network').Line
 if(($Mode -eq 'off' -and $task7Default -match 'none') -or ($Mode -eq 'on' -and $task7Default -notmatch 'none' -and $task7Default)){$task7Reached=$true;break}
 Start-Sleep -Seconds 1
}
$task7Evidence=@{at=(Get-Date -Format o);mode=$Mode;activeDefaultNetwork=$task7Default;reached=$task7Reached;wifi=(& $task7Adb -s emulator-5554 shell settings get global wifi_on);data=(& $task7Adb -s emulator-5554 shell settings get global mobile_data);airplane=(& $task7Adb -s emulator-5554 shell settings get global airplane_mode_on)}
$task7Evidence | ConvertTo-Json | Tee-Object -FilePath "C:/fdz-build/docs/verification/task7-independent-evidence/network-$Label.json"
if(!$task7Reached){throw '没有达到真实默认网络状态'}
