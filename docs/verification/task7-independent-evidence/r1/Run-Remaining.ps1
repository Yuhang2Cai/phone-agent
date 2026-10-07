# R1只补两个未完成的自然401返回；普通JWT实测120秒，不改设备时间/令牌，不启用故障夹具。
$ErrorActionPreference='Stop'
$task7Base='C:/fdz-build/docs/verification/task7-independent-evidence'
$task7Started=Get-Date -Format o
$task7Exit=0
New-Item -ItemType Directory -Force 'C:/fdz-build/.runtime/task7-independent/r1' | Out-Null
try{
 & "$task7Base/r1/Start-Spring.ps1" -JwtMinutes 2
 & "$task7Base/Test-Health.ps1" -Label r1-natural-expiry -ExpectedLifetimeSeconds 120
 # 每个目标独立普通登录，先定位原资源，再按该次实际登录时间自然等待；不重复主链或售后动作。
 foreach($task7Entry in @('order','voucher')){
  & "$task7Base/Run-Maestro.ps1" -Flow task7-401-prepare.yaml -Label "r1-401-$task7Entry-prepare" -Entry $task7Entry
  if($LASTEXITCODE -ne 0){throw "R1准备失败:$task7Entry"}
  & "$task7Base/r1/Wait-Expiry.ps1" -Entry $task7Entry -LifetimeSeconds 120
  & 'C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe' -s emulator-5554 shell input keyevent 3
  & "$task7Base/Run-Maestro.ps1" -Flow task7-401-return.yaml -Label "r1-401-$task7Entry-return" -Entry $task7Entry
  if($LASTEXITCODE -ne 0){throw "R1原目标返回失败:$task7Entry"}
 }
}catch{
 $task7Exit=1
 Write-Output $_.Exception.Message
}finally{
 # 无论UI成功或失败，都精确重启所属Spring恢复默认期限并强停APP，服务保留健康，不泛杀进程。
 & 'C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe' -s emulator-5554 shell am force-stop com.fandazi.app
 & "$task7Base/r1/Start-Spring.ps1" -JwtMinutes 60
 & "$task7Base/Test-Health.ps1" -Label r1-release -ExpectedLifetimeSeconds 3600
}
@{startedAt=$task7Started;finishedAt=(Get-Date -Format o);exitCode=$task7Exit;scope='仅原订单/券自然401；已过用例不重跑'} | ConvertTo-Json | Set-Content "$task7Base/r1/remaining-result.json" -Encoding utf8
exit $task7Exit
