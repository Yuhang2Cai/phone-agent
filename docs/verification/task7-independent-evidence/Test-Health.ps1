# 独立健康验证：只登录本轮新账号，检查普通期限、当前双服务、默认网络，不输出凭据。
param([string]$Label='release',[int]$ExpectedLifetimeSeconds=3600)
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Evidence="$task7Root/docs/verification/task7-independent-evidence/health-$Label.json"
if(Test-Path $task7Evidence){throw '禁止覆盖首次健康记录'}
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY='';$env:NO_PROXY='*'
$task7Account=Get-Content "$task7Root/.runtime/task7-independent/account.json" -Raw | ConvertFrom-Json
if(!$task7Account.username.StartsWith('task7_')){throw '非本轮新账号'}
$task7Body=@{username=$task7Account.username;password=$task7Account.password} | ConvertTo-Json
$task7Auth=Invoke-RestMethod 'http://127.0.0.1:8080/api/auth/login' -Method Post -ContentType 'application/json' -Body $task7Body -NoProxy
# 只解码公开时间字段以核实真实期限；不修改签名、内容，不把整段 JWT 写入证据。
$task7Part=$task7Auth.token.Split('.')[1].Replace('-','+').Replace('_','/')
while($task7Part.Length%4){$task7Part+='='}
$task7Claims=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($task7Part)) | ConvertFrom-Json
$task7Lifetime=[long]$task7Claims.exp-[long]$task7Claims.iat
if($task7Lifetime -ne $ExpectedLifetimeSeconds){throw '普通JWT期限不符'}
$task7Agent=Invoke-RestMethod 'http://127.0.0.1:8091/internal/v1/health' -NoProxy
if($task7Agent.entryPoint -ne 'service.app:create_app' -or $task7Agent.sourceRoot -ne 'D:\project\-Demo-master'){throw '新Agent来源不符'}
# psutil 只做进程诊断，返回明确的环境开关与入口，不采集其余敏感环境字段。
$task7Processes=& 'C:/Users/caid0/anaconda3/python.exe' -c "import json,psutil; out=[]; ports={8080,8091}; listeners={c.laddr.port:c.pid for c in psutil.net_connections(kind='tcp') if c.status=='LISTEN' and c.laddr.port in ports}; assert set(listeners)==ports; [(out.append(dict(port=port,pid=pid,entryMatched=('com.fandazi.FandaziApplication' if port==8080 else 'service.app:create_app') in ' '.join(psutil.Process(pid).cmdline()),environment={k:psutil.Process(pid).environ().get(k) for k in ['TASK2_FIXTURES_ENABLED','TASK8_FAILURES_ENABLED','SPRING_PROFILES_ACTIVE','JWT_EXPIRATION_MINUTES']}))) for port,pid in listeners.items()]; print(json.dumps(out))"
if($LASTEXITCODE -ne 0){throw '进程诊断失败'}
$task7Listeners=$task7Processes | ConvertFrom-Json
foreach($task7Listener in $task7Listeners){
 if(!$task7Listener.entryMatched -or $task7Listener.environment.TASK2_FIXTURES_ENABLED -ne 'false' -or $task7Listener.environment.TASK8_FAILURES_ENABLED -ne 'false'){throw '入口或隔离开关不符'}
}
$task7Spring=$task7Listeners | Where-Object port -eq 8080
if($task7Spring.environment.SPRING_PROFILES_ACTIVE -ne 'default' -or [int]$task7Spring.environment.JWT_EXPIRATION_MINUTES*60 -ne $ExpectedLifetimeSeconds){throw 'Spring配置不符'}
$task7Network=(& 'C:/Users/caid0/AppData/Local/Android/Sdk/platform-tools/adb.exe' -s emulator-5554 shell dumpsys connectivity | Select-String 'Active default network').Line
if($task7Network -match 'none' -or !$task7Network){throw '默认网络尚未恢复'}
@{at=(Get-Date -Format o);ordinaryJwtSeconds=$task7Lifetime;iat=$task7Claims.iat;exp=$task7Claims.exp;agent=$task7Agent;listeners=$task7Listeners;activeDefaultNetwork=$task7Network;httpProxyDisabled=$true} | ConvertTo-Json -Depth 7 | Set-Content $task7Evidence -Encoding utf8
Write-Output "独立健康 $Label 普通JWT=$task7Lifetime 秒"
