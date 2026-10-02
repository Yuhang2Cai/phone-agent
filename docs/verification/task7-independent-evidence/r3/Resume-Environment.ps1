# R3续验环境恢复：保留暂停前测试产物，只启动已批准且当前未监听的服务和原AVD，不初始化业务数据。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build'
$task7Public="$task7Root/docs/verification/task7-independent-evidence/r3"
$task7Private="$task7Root/.runtime/task7-independent/r3/resume"
New-Item -ItemType Directory -Force $task7Private | Out-Null
if(Test-Path "$task7Public/resume-environment-start.json"){throw '禁止覆盖续验首次环境记录'}
# 只归档本验收首次pytest时间窗内产生的日志；不放宽快照源审计，不删除或修改Git文件。
$task7Generated="$task7Root/.runtime/task7-delivery-r2/agent/logs/agent_20261002.log"
$task7File=Get-Item -LiteralPath $task7Generated
$task7First=Get-Content "$task7Public/snapshot-agent-result.json" -Raw | ConvertFrom-Json
if($task7File.CreationTime -lt [datetime]$task7First.startedAt -or $task7File.LastWriteTime -gt [datetime]$task7First.finishedAt){throw '日志时间不属于原独立pytest，停止归档'}
$task7LogHash=(Get-FileHash -LiteralPath $task7Generated).Hash
$task7Archive="$task7Private/first-agent-generated.log"
Move-Item -LiteralPath $task7Generated -Destination $task7Archive
if((Get-FileHash -LiteralPath $task7Archive).Hash -ne $task7LogHash){throw '日志归档字节变化'}
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY='';$env:NO_PROXY='*'
$env:TASK2_FIXTURES_ENABLED='false';$env:TASK8_FAILURES_ENABLED='false'
$task7Started=@()
# 原MySQL容器已经运行；环境脚本仅读取其凭据，Spring明确关闭seed以保护所有原事实。
. "$task7Root/scripts/Initialize-FandaziEnvironment.ps1"
if(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue){throw '8080出现监听者，重新核对后再启动'}
$env:SEED_ENABLED='false';$env:SPRING_PROFILES_ACTIVE='default';$env:JWT_EXPIRATION_MINUTES='60'
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$env:SERVER_TOMCAT_ACCESSLOG_ENABLED='true';$env:SERVER_TOMCAT_ACCESSLOG_DIRECTORY="$task7Private/http-access"
$env:SERVER_TOMCAT_ACCESSLOG_PATTERN='%t %m %U %s';$env:SERVER_TOMCAT_ACCESSLOG_BUFFERED='false'
$task7Spring=Start-Process -FilePath 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -ArgumentList '-q','spring-boot:run' -WorkingDirectory "$task7Root/backend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$task7Private/spring.out.log" -RedirectStandardError "$task7Private/spring.err.log"
$task7Started+=@{role='Spring';launcherPid=$task7Spring.Id}
# 新Agent入口保持原项目和pinnT；清除Spring连接变量，禁止跨数据库继承。
foreach($task7Name in @('DB_URL','DB_USERNAME','DB_PASSWORD')){Remove-Item "Env:$task7Name" -ErrorAction SilentlyContinue}
if(Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue){throw '8091出现监听者，重新核对后再启动'}
$task7Secrets=Get-Content "$task7Root/.runtime/service-secrets.json" -Raw | ConvertFrom-Json
$env:AGENT_INTERNAL_TOKEN=$task7Secrets.internalToken;$env:AGENT_BUILD_VERSION='c339237-task7-working-tree';$env:LANGCHAIN_TRACING_V2='false'
$task7Agent=Start-Process -FilePath 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -ArgumentList '-m','uvicorn','service.app:create_app','--factory','--host','127.0.0.1','--port','8091' -WorkingDirectory 'D:/project/-Demo-master' -WindowStyle Hidden -PassThru -RedirectStandardOutput "$task7Private/agent.out.log" -RedirectStandardError "$task7Private/agent.err.log"
$task7Started+=@{role='Agent';launcherPid=$task7Agent.Id}
# 启动现有AVD，不wipe、不重建设备；保持原端口与数据，装机步骤另行按规定卸载应用。
$task7Emulator=Start-Process -FilePath 'C:/Users/caid0/AppData/Local/Android/Sdk/emulator/emulator.exe' -ArgumentList '-avd','Medium_Phone_API_36.1','-port','5554','-no-window','-no-audio','-no-boot-anim' -WindowStyle Hidden -PassThru -RedirectStandardOutput "$task7Private/emulator.out.log" -RedirectStandardError "$task7Private/emulator.err.log"
$task7Started+=@{role='Emulator';launcherPid=$task7Emulator.Id}
@{at=(Get-Date -Format o);initialSpringRefused=$true;initialAdbDevices=0;mysqlExistingContainerRunning=$true;archivedGeneratedLogSha256=$task7LogHash;generatedLogBytes=$task7File.Length;started=$task7Started;readyVerified=$false;seedEnabled=$false;fixtures=$false;faults=$false;jwtMinutes=60;initialDiagnosticAccessDeniedPreserved=$true} | ConvertTo-Json -Depth 5 | Set-Content "$task7Public/resume-environment-start.json" -Encoding utf8
Write-Output '已启动原双服务及AVD；健康状态待独立核对'
