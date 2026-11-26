# Task2 隔离 MySQL 升级验证：只启动指定测试副本，不建库、清库或连接业务原库。
param([Parameter(Mandatory=$true)][string]$DatabaseName)
$ErrorActionPreference = 'Stop'

# 库名白名单拒绝业务库和任何自由 SQL；凭据只通过共享环境传入子进程。
if ($DatabaseName -notmatch '^task2_upgrade_[a-f0-9]{12}$') { throw '只能验证 task2_upgrade_ 隔离副本' }
. (Join-Path $PSScriptRoot 'Initialize-FandaziEnvironment.ps1')
if (Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue) { throw '隔离端口 8082 已占用' }
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/${DatabaseName}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:JAVA_HOME = 'D:\software\IntelliJ IDEA 2025.2.4\jbr'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$env:SEED_ENABLED = 'false'
$env:SPRING_PROFILES_ACTIVE = 'default'
$env:SPRING_APPLICATION_JSON = (@{server=@{port=8082};seed=@{enabled=$false};task2=@{fixtures=@{enabled=$false}}} | ConvertTo-Json -Compress)
$mvn = 'D:\software\IntelliJ IDEA 2025.2.4\plugins\maven\lib\maven3\bin\mvn.cmd'
$launcher = $null
$started = Get-Date

function Test-OwnDescendant($Process, $Inventory, $LauncherId) {
    # Maven 将类路径放入临时参数文件，不能靠命令行出现工作区文本判断身份；使用本轮启动的父子链。
    $cursor = $Process
    $seen = @{}
    while ($cursor -and -not $seen.ContainsKey([int]$cursor.ProcessId)) {
        $seen[[int]$cursor.ProcessId] = $true
        if ($cursor.ParentProcessId -eq $LauncherId) { return $true }
        $cursor = $Inventory[[int]$cursor.ParentProcessId]
    }
    return $false
}
try {
    # 禁用种子和夹具后，Flyway/实体校验只升级副本历史，不额外创建订单或账号。
    $launcher = Start-Process -FilePath $mvn -ArgumentList '-q','spring-boot:run' -WorkingDirectory (Join-Path $workspace 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDirectory 'task2-upgrade-copy.out.log') -RedirectStandardError (Join-Path $runtimeDirectory 'task2-upgrade-copy.err.log')
    $ready = $false
    for ($attempt=0; $attempt -lt 40; $attempt++) {
        try {
            $response = Invoke-WebRequest 'http://127.0.0.1:8082/api/stores' -NoProxy -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $ready=$true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw '副本未就绪，查看 task2-upgrade-copy 日志；不得继续升级原库' }
    Write-Output "isolated-copy-health=200 database=$DatabaseName seed=false"
} finally {
    # 只清理本轮启动的进程树：绑定端口的主类须匹配且能追溯到 launcher，不终止现有双服务。
    $inventory = @{}
    Get-CimInstance Win32_Process | ForEach-Object { $inventory[[int]$_.ProcessId] = $_ }
    $listener = Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue
    foreach ($item in $listener) {
        $candidate = $inventory[[int]$item.OwningProcess]
        if ($launcher -and $candidate -and $candidate.CreationDate -ge $started -and $candidate.CommandLine -match 'com\.fandazi\.FandaziApplication' -and (Test-OwnDescendant $candidate $inventory $launcher.Id)) {
            Stop-Process -Id $candidate.ProcessId -Force -ErrorAction SilentlyContinue
        } else { Write-Warning '隔离端口进程身份不匹配，保留进程供人工定位' }
    }
    if ($launcher) {
        foreach ($candidate in $inventory.Values) {
            if ($candidate.CreationDate -ge $started -and (Test-OwnDescendant $candidate $inventory $launcher.Id)) {
                Stop-Process -Id $candidate.ProcessId -Force -ErrorAction SilentlyContinue
            }
        }
    }
    if ($launcher -and -not $launcher.HasExited) { Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue }
}
