# Task4 真实 Maestro 运行器：仅从游客目录选取资源并传入流程；不创建种子、账号、交易或消费夹具。
param([string]$EvidenceSuffix = 'development', [ValidateSet('browse', 'keyword')][string]$Flow = 'browse')
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:/Users/caid0/.jdks/ms-17.0.17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$maestro = 'C:/Users/caid0/.local/maestro/bin/maestro.bat'
$api = 'http://127.0.0.1:8080/api'

# 输入来自运行中的 Spring：同一目录同时用于 API 比对与本轮页面资源身份，不读取 Task8 清单。
$evidence = Get-Content (Join-Path $workspace 'docs/verification/task4-evidence/browse-api.json') -Raw | ConvertFrom-Json
$store = Invoke-RestMethod -NoProxy "$api/stores/$($evidence.selectedStore.publicId)"
# Invoke-RestMethod 返回 JSON 数组本身，不能在命令外套 @() 造成数组被视为单个管道对象。
$deals = Invoke-RestMethod -NoProxy "$api/stores/$($store.publicId)/deals"
$drinks = Invoke-RestMethod -NoProxy "$api/stores?category=$([uri]::EscapeDataString('奶茶咖啡'))"
$hotpots = Invoke-RestMethod -NoProxy "$api/stores?category=$([uri]::EscapeDataString('火锅'))"
if ($deals.Count -lt 2 -or $drinks.Count -eq 0 -or $hotpots.Count -eq 0) { throw '真实目录不足以运行 Task4 浏览流程' }
$destination = Join-Path $workspace "docs/verification/task4-evidence/maestro-$EvidenceSuffix"
New-Item -ItemType Directory -Path $destination -Force | Out-Null

# 使用参数而不是把业务样例写进 YAML；名称允许动态变化，关键断言使用完整 UUID 和整数分。
$arguments = @('test', '--device', 'emulator-5554', '--debug-output', $destination,
    '--test-output-dir', $destination, '-e', "CITY_CODE=$($store.cityCode)",
    '-e', "STORE_ID=$($store.publicId)", '-e', "STORE_NAME=$($store.name)",
    '-e', "DEAL_A=$($deals[0].publicId)", '-e', "DEAL_B=$($deals[1].publicId)",
    '-e', "PRICE_A=$($deals[0].priceCents)", '-e', "PRICE_B=$($deals[1].priceCents)",
    '-e', "DRINK_ID=$($drinks[0].publicId)", '-e', "HOTPOT_ID=$($hotpots[0].publicId)",
    (Join-Path $workspace $(if ($Flow -eq 'browse') { 'maestro/group-buying-01-browse.yaml' } else { 'maestro/task4-search-keyword.yaml' })))
& $maestro @arguments
exit $LASTEXITCODE
