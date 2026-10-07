<# Task8 本地服务启动器：独占已核实的目标端口，保留每轮日志，禁止重新准备旧业务数据。 #>
param([ValidateSet('Spring','Agent')][string]$Service='Spring', [switch]$EnableOwnedOrderFailure)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$logRoot=Join-Path $root '.runtime/task8-resumed'
[IO.Directory]::CreateDirectory($logRoot) | Out-Null
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$port=if($Service -eq 'Spring'){8080}else{8091}
$expected=if($Service -eq 'Spring'){'com.fandazi.FandaziApplication'}else{'service.app:create_app'}
# 仅在端口实际属于本项目服务时允许重启，不能停止其他 Java 或 Python 任务。
$listener=Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
if($listener){
    $owner=Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    if($owner.CommandLine -notlike "*$expected*"){throw '端口属于其他服务，拒绝停止'}
    Stop-Process -Id $owner.ProcessId
    Start-Sleep -Seconds 2
}
$env:HTTP_PROXY=''; $env:HTTPS_PROXY=''; $env:ALL_PROXY=''; $env:NO_PROXY='*'
$env:TASK2_FIXTURES_ENABLED='false'; $env:SEED_ENABLED='false'
$env:TASK8_FAILURES_ENABLED=if($EnableOwnedOrderFailure){'true'}else{'false'}
if($Service -eq 'Spring'){
    . (Join-Path $PSScriptRoot 'Initialize-FandaziEnvironment.ps1')
    # 故障只能在明确开启的开发环境使用；普通流程不加载故障 Bean，默认 JWT 仍为六十分钟。
    $env:SPRING_PROFILES_ACTIVE=if($EnableOwnedOrderFailure){'development'}else{'default'}
    $env:JWT_EXPIRATION_MINUTES='60'
    $env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
    $binary='D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd'
    $arguments=@('-q','spring-boot:run')
    $working=Join-Path $root 'backend'
    $health='http://127.0.0.1:8080/api/stores/cities'
}else{
    # 新 Agent 必须读自己的数据库配置；清除本进程继承的 Spring 业务库连接变量。
    foreach($name in @('DB_URL','DB_USERNAME','DB_PASSWORD')){Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue}
    $secret=Get-Content -LiteralPath (Join-Path $root '.runtime/service-secrets.json') -Raw | ConvertFrom-Json
    $env:AGENT_INTERNAL_TOKEN=$secret.internalToken
    $env:AGENT_BUILD_VERSION="$(git -C D:/project/-Demo-master rev-parse --short HEAD)-task8-working-tree"
    $env:LANGCHAIN_TRACING_V2='false'
    $binary='C:/Users/caid0/anaconda3/envs/pinnT/python.exe'
    $arguments=@('-m','uvicorn','service.app:create_app','--factory','--host','127.0.0.1','--port','8091')
    $working='D:/project/-Demo-master'
    $health='http://127.0.0.1:8091/internal/v1/health'
}
$process=Start-Process -FilePath $binary -ArgumentList $arguments -WorkingDirectory $working -WindowStyle Hidden -PassThru -RedirectStandardOutput "$logRoot/$Service-$stamp.out.log" -RedirectStandardError "$logRoot/$Service-$stamp.err.log"
# 创建进程不是启动成功；等待真实接口，不把冷启动阶段算作业务失败或伪造健康结果。
$ready=$false
for($attempt=0;$attempt -lt 60;$attempt++){
    try{$null=Invoke-RestMethod -NoProxy -Uri $health -TimeoutSec 2;$ready=$true;break}catch{Start-Sleep -Seconds 1}
}
if(!$ready){throw '服务健康检查未通过，详情见本轮私有日志'}
[ordered]@{service=$Service;launcherPid=$process.Id;port=$port;failureInjection=[bool]$EnableOwnedOrderFailure;at=$stamp} | ConvertTo-Json
