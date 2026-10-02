# Task7 来源健康与普通JWT实测：不输出密钥或令牌，只保存来源、PID、期限、网络和开关声明。
param([string]$Label='release',[int]$ExpectedLifetimeSeconds=3600)
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$account=Get-Content "$root/.runtime/task7/account.json" -Raw | ConvertFrom-Json
if(!$account.username.StartsWith('task7_')) {throw '非本任务账号'}
$body=@{username=$account.username;password=$account.password} | ConvertTo-Json
$auth=Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/auth/login' -Method Post -ContentType 'application/json' -Body $body -NoProxy
# JWT只解码公开期限字段，绝不修改签名/内容或保存整段令牌到公开证据。
$part=$auth.token.Split('.')[1].Replace('-','+').Replace('_','/')
while($part.Length%4) {$part+='='}
$claims=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($part)) | ConvertFrom-Json
$lifetime=[long]$claims.exp-[long]$claims.iat
if($lifetime -ne $ExpectedLifetimeSeconds) {throw '普通JWT期限不符合当前阶段'}
$agent=Invoke-RestMethod 'http://127.0.0.1:8091/internal/v1/health' -NoProxy
if($agent.entryPoint -ne 'service.app:create_app' -or $agent.sourceRoot -ne 'D:\project\-Demo-master') {throw '新Agent来源不符'}
$processes=@()
foreach($port in @(8080,8091)) {
 $listener=Get-NetTCPConnection -State Listen -LocalPort $port | Select-Object -First 1
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
 $expected=if($port -eq 8080) {'com.fandazi.FandaziApplication'} else {'service.app:create_app'}
 if($process.CommandLine -notlike "*$expected*") {throw '监听者来源不符'}
 $processes+=@{port=$port;pid=$process.ProcessId;entryPoint=$expected}
}
$adb='C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe'
$network=(& $adb -s emulator-5554 shell dumpsys connectivity | Select-String 'Active default network').Line
if($network -match 'none') {throw '尚未恢复网络'}
@{at=(Get-Date -Format o);ordinaryJwtSeconds=$lifetime;agent=$agent;listeners=$processes;activeDefaultNetwork=$network;task2FixturesEnabled=$false;task8FailuresEnabled=$false;sourceRunnerSha256=(Get-FileHash "$root/scripts/Start-Task7Services.ps1").Hash} | ConvertTo-Json -Depth 6 | Set-Content "$root/docs/verification/task7-evidence/health-$Label.json" -Encoding utf8
Write-Output "Task7 $Label health verified JWT=$lifetime seconds"
