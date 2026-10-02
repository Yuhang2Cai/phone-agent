# Task6 Maestro 运行器：使用隔离真实账号与实时目录，凭据只进入忽略日志，不消费历史任务清单。
param([int]$DealIndex=0,[int]$Quantity=2,[string]$Flow='group-buying-03-purchase.yaml',[string]$Label='purchase')
$ErrorActionPreference='Stop'
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$root='C:/fdz-build'
$account=Get-Content "$root/.runtime/task6-account.json" -Raw | ConvertFrom-Json
if(!$account.username.StartsWith('task6_')){throw '不是本任务隔离账号'}
# 资源来自当前真实公开接口；金额只用于对照页面，不写回产品源码或修改后端报价。
$stores=Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/stores' -NoProxy
$deals=Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/stores/$($stores[0].publicId)/deals" -NoProxy
$deal=$deals[$DealIndex]
if(!$deal.active -or $deal.inventory -lt $Quantity -or $deal.purchaseLimit -lt $Quantity){throw '当前套餐不可按指定数量购买'}
$total='¥'+([decimal]($deal.priceCents * $Quantity)/100).ToString('0.##',[Globalization.CultureInfo]::InvariantCulture)
# Windows 批处理可能损坏命令行货币符号，断言使用数字正则；公开记录仍保留准确人民币金额。
$amountPattern='.*'+([decimal]($deal.priceCents * $Quantity)/100).ToString('0.##',[Globalization.CultureInfo]::InvariantCulture).Replace('.','\.')
$output="$root/docs/verification/task6-evidence/maestro-$Label"
New-Item -ItemType Directory -Force -Path $output | Out-Null
# 原始命令含登录口令，只保存到忽略目录；公开证据仅复制明确的页面截图。
$raw="$root/.runtime/task6-maestro-raw-$Label"
New-Item -ItemType Directory -Force -Path $raw | Out-Null
$target=if($Quantity -eq 1){'com.fandazi.app:id/voucher_detail_status'}else{'com.fandazi.app:id/group_order_detail_title'}
$started=Get-Date -Format o
& 'C:/Users/caid0/.local/maestro/bin/maestro.bat' --device emulator-5554 test --test-output-dir $raw --debug-output $raw -e "MAESTRO_USERNAME=$($account.username)" -e "MAESTRO_PASSWORD=$($account.password)" -e "TASK6_STORE_ID=$($stores[0].publicId)" -e "TASK6_DEAL_ID=$($deal.publicId)" -e "TASK6_TOTAL=$amountPattern" -e "TASK6_QUANTITY=$Quantity" -e "TASK6_VOUCHER_TARGET=$target" "$root/maestro/$Flow" *> "$root/.runtime/task6-maestro-$Label.log"
$code=$LASTEXITCODE
# 截图内容为本任务购买页面，保留相对位置便于对照；不公开登录命令及层次树。
Get-ChildItem -LiteralPath $raw -Recurse -File -Filter '*.png' | Where-Object { $_.FullName -match 'takeScreenshot' } | ForEach-Object {
    $relative=[IO.Path]::GetRelativePath($raw,$_.FullName)
    $destination=Join-Path $output $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $destination
}
# 公开只保留实际资源、金额、APK摘要和命令结果，不复制含账号口令的完整 Maestro 运行日志。
[pscustomobject]@{exitCode=$code;flow=$Flow;dealId=$deal.publicId;quantity=$Quantity;expectedTotal=$total;startedAt=$started;finishedAt=(Get-Date -Format o);apkSha256=(Get-FileHash "$root/android-app/app/build/outputs/apk/debug/app-debug.apk").Hash} | ConvertTo-Json | Set-Content "$output/result.json"
Write-Output "maestro-$Label exit=$code"
Get-Content "$root/.runtime/task6-maestro-$Label.log" -Tail 12
exit $code
