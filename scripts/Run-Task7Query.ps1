# Task7 Maestro 运行器：账号口令仅使用本任务私有随机账号，资源身份从真实购买证据取得。
param([string]$Flow='group-buying-04-voucher-query.yaml',[string]$Label='query',[ValidateSet('account','outsider')][string]$Account='account',[ValidateSet('orders','order','voucher')][string]$Entry='orders')
$ErrorActionPreference='Stop'
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$root='C:/fdz-build'
$accountData=Get-Content "$root/.runtime/task7/$Account.json" -Raw | ConvertFrom-Json
if(!$accountData.username.StartsWith('task7_')) { throw '拒绝使用非Task7隔离账号' }
$orders=Get-Content "$root/.runtime/task7/orders.json" -Raw | ConvertFrom-Json
$resources=Get-Content "$root/.runtime/task7/resources.json" -Raw | ConvertFrom-Json
$raw="$root/.runtime/task7/maestro-$Label"
$output="$root/docs/verification/task7-evidence/maestro-$Label"
if(Test-Path "$output/result.json") { throw '本次标签已有结果，禁止覆盖首次失败' }
New-Item -ItemType Directory -Force -Path $raw,$output | Out-Null
# 构造断言环境只传入真实公开资源和金额；命令及工具调试树可能包含口令，不复制到公开目录。
$arguments=@('--device','emulator-5554','test','--test-output-dir',$raw,'--debug-output',$raw,
    '-e',"MAESTRO_USERNAME=$($accountData.username)",'-e',"MAESTRO_PASSWORD=$($accountData.password)",
    '-e',"TASK7_ORDER_A=$($orders[0].orderId)",'-e',"TASK7_ORDER_B=$($orders[1].orderId)",'-e',"TASK7_ORDER_PENDING=$($orders[2].orderId)",
    '-e',"TASK7_VOUCHER_A=$($orders[0].vouchers[0].publicId)",'-e',"TASK7_VOUCHER_B=$($orders[0].vouchers[1].publicId)",
    '-e',"TASK7_VOUCHER_C=$($orders[1].vouchers[0].publicId)",'-e',"TASK7_PENDING_VOUCHER=$($orders[2].vouchers[0].publicId)",
    '-e',"TASK6_STORE_ID=$($resources[1].storePublicId)",'-e',"TASK6_DEAL_ID=$($resources[1].public_id)",
    '-e','TASK6_TOTAL=.*96\.47','-e','TASK6_QUANTITY=1','-e','TASK6_VOUCHER_TARGET=com.fandazi.app:id/voucher_detail_status',"$root/maestro/$Flow")
# 登录恢复断言按显式测试入口选择目标，不改变产品导航或业务状态。
$returnId=switch($Entry) {'orders' {'com.fandazi.app:id/orders_segments'} 'order' {'com.fandazi.app:id/group_order_detail_title'} 'voucher' {'com.fandazi.app:id/voucher_detail_info'}}
$arguments=$arguments[0..($arguments.Length-2)]+@('-e',"TASK7_ENTRY=$Entry",'-e',"TASK7_RETURN_ID=$returnId",$arguments[-1])
$started=Get-Date -Format o
& 'C:/Users/caid0/.local/maestro/bin/maestro.bat' @arguments *> "$root/.runtime/task7/maestro-$Label.log"
$code=$LASTEXITCODE
# 仅导出显式截图和摘要；每次APK摘要绑定具体运行结果，不以YAML存在当作通过。
Get-ChildItem -LiteralPath $raw -Recurse -File -Filter '*.png' | Where-Object {$_.FullName -match 'takeScreenshot'} | ForEach-Object {
    $destination=Join-Path $output ([IO.Path]::GetRelativePath($raw,$_.FullName))
    New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $destination
}
@{exitCode=$code;flow=$Flow;accountRole=$Account;startedAt=$started;finishedAt=(Get-Date -Format o);apkSha256=(Get-FileHash "$root/android-app/app/build/outputs/apk/debug/app-debug.apk").Hash;orderIds=@($orders.orderId);voucherIds=@($orders.vouchers.publicId)} | ConvertTo-Json -Depth 5 | Set-Content "$output/result.json" -Encoding utf8
Write-Output "Task7 Maestro $Label exit=$code"
exit $code
