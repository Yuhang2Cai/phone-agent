# Task5 Maestro 运行器：只注入本任务随机账号与真实目录城市，秘密日志留忽略目录。
param([string]$Flow = 'task5-ai-chat-page.yaml', [string]$Label = 'chat', [string]$Query = 'hello', [string]$Expected = '你好，我是饭搭子助手。')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$account = Get-Content -LiteralPath (Join-Path $root '.runtime/task5-account.json') -Raw | ConvertFrom-Json
$city = @(Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/stores/cities' -NoProxy)[0].cityCode
# 套餐 ID 只从本任务真实 Spring 展示夹具读取，XML、流程文件和产品代码均不写死业务 ID。
$fixture = Get-Content -LiteralPath (Join-Path $root '.runtime/task5-order-fixture.json') -Raw | ConvertFrom-Json
$env:JAVA_HOME = 'C:/Users/caid0/.jdks/ms-17.0.17'
$env:ANDROID_HOME = 'C:/Users/caid0/AppData/Local/Android/Sdk'
$env:PATH = "$env:ANDROID_HOME/platform-tools;$env:JAVA_HOME/bin;$env:PATH"
$output = Join-Path $root ".runtime/task5-maestro-$Label"
# Maestro 调试文件可能保存输入密码，整个原始输出只能在忽略目录，公开报告只复制聊天截图。
& 'C:/Users/caid0/.local/maestro/bin/maestro.bat' --device emulator-5554 test --test-output-dir $output --debug-output $output -e "TASK5_USERNAME=$($account.username)" -e "TASK5_PASSWORD=$($account.password)" -e "TASK5_CITY=$city" -e "TASK5_DEAL_ID=$($fixture.dealId)" -e "TASK5_QUERY=$Query" -e "TASK5_EXPECTED=$Expected" (Join-Path $root "maestro/$Flow") *> (Join-Path $root ".runtime/task5-maestro-$Label.log")
$code = $LASTEXITCODE
$pictures = Join-Path $root "docs/verification/task5-evidence/maestro-$Label"
New-Item -ItemType Directory -Force -Path $pictures | Out-Null
Get-ChildItem -LiteralPath $output -Recurse -File -Filter '*.png' | Where-Object { $_.Name -like '*task5-*' } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $pictures $_.Name)
}
[pscustomobject]@{flow=$Flow;label=$Label;exitCode=$code;finishedAt=(Get-Date -Format o);rawOutput='忽略目录 .runtime';screenshots=$pictures} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $pictures 'result.json') -Encoding utf8
[pscustomobject]@{flow=$Flow;exitCode=$code;screenshots=$pictures} | ConvertTo-Json
exit $code
