# 独立验收 Spring 重启：核实8080唯一监听进程身份后精确停止，隐藏启动批准Maven入口。
param([ValidateRange(1,60)][int]$JwtMinutes=60)
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Private="$task7Root/.runtime/task7-independent"
# base Python 仅用于 Windows 进程诊断；产品/测试仍使用批准的 pinnT、Microsoft JDK17。
$task7Owner=& 'C:/Users/caid0/anaconda3/python.exe' -c "import psutil; ids={c.pid for c in psutil.net_connections(kind='tcp') if c.status=='LISTEN' and c.laddr.port==8080}; assert len(ids)==1; p=psutil.Process(ids.pop()); assert 'com.fandazi.FandaziApplication' in ' '.join(p.cmdline()); print(p.pid)"
if($LASTEXITCODE -ne 0){throw '监听者来源核对失败'}
Stop-Process -Id ([int]$task7Owner)
. "$task7Root/scripts/Initialize-FandaziEnvironment.ps1"
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY='';$env:NO_PROXY='*'
$env:TASK2_FIXTURES_ENABLED='false';$env:TASK8_FAILURES_ENABLED='false';$env:SEED_ENABLED='false';$env:SPRING_PROFILES_ACTIVE='default'
$env:JWT_EXPIRATION_MINUTES="$JwtMinutes";$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
# 访问日志只保留方法、路径、状态，不保留账号、正文和JWT；按本轮专属目录隔离。
$env:SERVER_TOMCAT_ACCESSLOG_ENABLED='true';$env:SERVER_TOMCAT_ACCESSLOG_DIRECTORY="$task7Private/http-access"
$env:SERVER_TOMCAT_ACCESSLOG_PATTERN='%t %m %U %s';$env:SERVER_TOMCAT_ACCESSLOG_BUFFERED='false'
$task7Launch=Start-Process -FilePath 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' -ArgumentList '-q','spring-boot:run' -WorkingDirectory "$task7Root/backend" -WindowStyle Hidden -PassThru -RedirectStandardOutput "$task7Private/spring-$JwtMinutes.out.log" -RedirectStandardError "$task7Private/spring-$JwtMinutes.err.log"
$task7Ready=$false
for($task7Attempt=0;$task7Attempt -lt 45;$task7Attempt++){
 try{$null=Invoke-RestMethod http://127.0.0.1:8080/api/stores/cities -NoProxy -TimeoutSec 1;$task7Ready=$true;break}catch{Start-Sleep -Seconds 1}
}
if(!$task7Ready){throw '重启后接口不健康'}
@{at=(Get-Date -Format o);previousPid=[int]$task7Owner;launcherPid=$task7Launch.Id;jwtMinutes=$JwtMinutes;fixtures=$false;faults=$false;profile='default';entry='com.fandazi.FandaziApplication'} | ConvertTo-Json | Tee-Object -FilePath "$task7Root/docs/verification/task7-independent-evidence/spring-$JwtMinutes-start.json"
