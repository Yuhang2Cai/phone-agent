# Spring 启动脚本只使用共享本地环境，禁止写死数据库密码、JWT 或内部令牌。
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\Initialize-FandaziEnvironment.ps1')
$env:JAVA_HOME = 'D:\software\IntelliJ IDEA 2025.2.4\jbr'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$env:SEED_ENABLED = 'true'
$env:SEED_PATH = Join-Path $workspace 'data\seed'
$env:SPRING_APPLICATION_JSON = (@{seed=@{enabled=$true;path=$env:SEED_PATH}} | ConvertTo-Json -Compress)
$mvn = 'D:\software\IntelliJ IDEA 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd'
$backend = Join-Path $workspace 'backend'
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) { throw '后端 8080 端口已占用，请核对运行来源' }
$process = Start-Process -FilePath $mvn -ArgumentList '-q','spring-boot:run' -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDirectory 'backend.out.log') -RedirectStandardError (Join-Path $runtimeDirectory 'backend.err.log')
Write-Output "backend-start-requested pid=$($process.Id) database=$database agentPort=$agentPort"
