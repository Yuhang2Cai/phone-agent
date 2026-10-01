# 本模块准备双服务共享的本地环境。凭据来自容器或忽略目录，不能输出到日志或使用源码常量。
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$runtimeDirectory = Join-Path $workspace '.runtime'
$secretFile = Join-Path $runtimeDirectory 'service-secrets.json'
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null

function New-LocalSecret {
    # 密钥用操作系统随机源生成，只在本机共享；重启读取同一个文件，避免暂停中的确认失效。
    $bytes = New-Object byte[] 48
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

if (Test-Path -LiteralPath $secretFile) {
    $secrets = Get-Content -LiteralPath $secretFile -Raw | ConvertFrom-Json
} else {
    $secrets = [pscustomobject]@{internalToken=(New-LocalSecret); jwtSecret=(New-LocalSecret)}
    # 此处生成运行期文件，不是源码；Git 忽略规则防止密钥进入提交。
    $secrets | ConvertTo-Json | Set-Content -LiteralPath $secretFile -Encoding UTF8
}
if (-not $env:AGENT_INTERNAL_TOKEN) { $env:AGENT_INTERNAL_TOKEN = $secrets.internalToken }
if (-not $env:JWT_SECRET) { $env:JWT_SECRET = $secrets.jwtSecret }
if ([Text.Encoding]::UTF8.GetByteCount($env:AGENT_INTERNAL_TOKEN) -lt 32) { throw '内部密钥至少需要 32 字节' }

# 容器配置为现有开发机身份来源。两个库分别用于业务与 Agent，不能互相覆盖。
if (-not ($env:DB_USERNAME -and $env:DB_PASSWORD)) {
    $docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
    $container = if ($env:FANDAZI_MYSQL_CONTAINER) { $env:FANDAZI_MYSQL_CONTAINER } else { 'mysql-server' }
    $items = & $docker inspect $container --format '{{json .Config.Env}}'
    if ($LASTEXITCODE -ne 0) { throw '无法读取 MySQL 容器环境，请先启动 Docker 与 MySQL' }
    $values = @{}
    foreach ($item in ($items | ConvertFrom-Json)) {
        $pair = $item -split '=', 2
        if ($pair.Count -eq 2) { $values[$pair[0]] = $pair[1] }
    }
    $env:DB_USERNAME = if ($values['MYSQL_USER']) { $values['MYSQL_USER'] } else { 'root' }
    $env:DB_PASSWORD = if ($values['MYSQL_PASSWORD']) { $values['MYSQL_PASSWORD'] } else { $values['MYSQL_ROOT_PASSWORD'] }
}
if (-not $env:DB_PASSWORD) { throw '未取得 MySQL 凭据，禁止启动伪造数据库降级' }
$database = if ($env:FANDAZI_DB_NAME) { $env:FANDAZI_DB_NAME } else { 'fandazi_groupbuy_dev' }
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/${database}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$agentPort = if ($env:FANDAZI_AGENT_PORT) { $env:FANDAZI_AGENT_PORT } else { '8091' }
$env:AGENT_SERVICE_URL = "http://127.0.0.1:$agentPort"
$env:AGENT_SERVICE_TIMEOUT_MS = '90000'
$env:SPRING_BOOT_BASE_URL = 'http://127.0.0.1:8080'
$env:LANGCHAIN_TRACING_V2 = 'false'
