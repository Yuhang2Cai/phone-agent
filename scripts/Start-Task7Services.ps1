# Task7 服务运行器：仅重启已核实的新 Agent/Spring，关闭所有开发夹具开关，不操作业务资源。
param([ValidateSet('Spring','Agent')][string]$Service = 'Spring', [ValidateRange(1,1440)][int]$JwtExpirationMinutes = 60)
$ErrorActionPreference = 'Stop'
# 本地双服务和模型请求均禁用环境代理，避免将 localhost 或内部鉴权流量送到外部代理。
$env:HTTP_PROXY=''; $env:HTTPS_PROXY=''; $env:ALL_PROXY=''; $env:NO_PROXY='*'
$root = Split-Path -Parent $PSScriptRoot
$port = if ($Service -eq 'Spring') { 8080 } else { 8091 }
$expected = if ($Service -eq 'Spring') { 'com.fandazi.FandaziApplication' } else { 'service.app:create_app' }
# 先核对监听进程职责，仅停止目标端口拥有者，禁止泛杀 Java/Python 或其他任务进程。
$listener = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue | Select-Object -First 1
if ($listener) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)"
    if ($owner.CommandLine -notlike "*$expected*") { throw '目标端口拥有者不符合已批准服务，拒绝重启' }
    Stop-Process -Id $owner.ProcessId
    Start-Sleep -Seconds 2
}
$runtime = Join-Path $root '.runtime'
if ($Service -eq 'Spring') {
    . (Join-Path $PSScriptRoot 'Initialize-FandaziEnvironment.ps1')
    $env:TASK2_FIXTURES_ENABLED = 'false'
    $env:TASK8_FAILURES_ENABLED = 'false'
    $env:SEED_ENABLED = 'false'
    $env:SPRING_PROFILES_ACTIVE = 'default'
    # 普通 JWT 生命周期配置用于自然过期验证，禁止使用开发故障或夹具开关；结束恢复默认六十分钟。
    $env:JWT_EXPIRATION_MINUTES = "$JwtExpirationMinutes"
    # 普通访问日志仅记录时间、方法、路径和状态，核实自然过期产生的真实 401，不记录令牌或请求体。
    $env:SERVER_TOMCAT_ACCESSLOG_ENABLED = 'true'
    $env:SERVER_TOMCAT_ACCESSLOG_DIRECTORY = (Join-Path $runtime 'task7-http-access')
    $env:SERVER_TOMCAT_ACCESSLOG_PATTERN = '%t %m %U %s'
    $env:SERVER_TOMCAT_ACCESSLOG_BUFFERED = 'false'
    $env:JAVA_HOME = 'C:/Users/caid0/.jdks/ms-17.0.17'
    $process = Start-Process -FilePath 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -ArgumentList '-q','spring-boot:run' -WorkingDirectory (Join-Path $root 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'task7-spring.out.log') -RedirectStandardError (Join-Path $runtime 'task7-spring.err.log')
} else {
    # Agent 独立数据库/模型配置由其 .env 加载，不能继承 Spring 的业务 DB_URL 覆盖 dianping_intent。
    foreach ($name in @('DB_URL','DB_USERNAME','DB_PASSWORD')) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
    $secrets = Get-Content -LiteralPath (Join-Path $runtime 'service-secrets.json') -Raw | ConvertFrom-Json
    $env:AGENT_INTERNAL_TOKEN = $secrets.internalToken
    $env:AGENT_BUILD_VERSION = 'c339237-task7-working-tree'
    $env:LANGCHAIN_TRACING_V2 = 'false'
    $env:TASK2_FIXTURES_ENABLED = 'false'
    $env:TASK8_FAILURES_ENABLED = 'false'
    $process = Start-Process -FilePath 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -ArgumentList '-m','uvicorn','service.app:create_app','--factory','--host','127.0.0.1','--port','8091' -WorkingDirectory 'D:/project/-Demo-master' -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'task7-agent.out.log') -RedirectStandardError (Join-Path $runtime 'task7-agent.err.log')
}
# 输出仅含进程角色与 PID，不输出密钥、账户或数据库连接字符串。
$health = if ($Service -eq 'Spring') { 'http://127.0.0.1:8080/api/stores/cities' } else { 'http://127.0.0.1:8091/internal/v1/health' }
# 服务进程创建不等于健康，等待实际接口，防止冷启动窗口被误算成模型/协议失败。
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    try { $null = Invoke-RestMethod -Uri $health -NoProxy -TimeoutSec 2; $ready = $true; break }
    catch { Start-Sleep -Seconds 1 }
}
if (!$ready) { throw '服务启动后未通过健康接口，请查看忽略目录运行日志' }
[pscustomobject]@{Service=$Service;LauncherPid=$process.Id;Port=$port} | ConvertTo-Json
