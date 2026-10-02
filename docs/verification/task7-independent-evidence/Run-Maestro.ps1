# 本轮独立 Maestro：只用新账号与订单，固定 APK，原始命令树含口令时仅保留在忽略目录。
param([string]$Flow='group-buying-04-voucher-query.yaml',[string]$Label='query',[ValidateSet('account','outsider','empty')][string]$Account='account',[ValidateSet('orders','order','voucher')][string]$Entry='orders')
$ErrorActionPreference='Stop'
$env:JAVA_HOME='C:/Users/caid0/.jdks/ms-17.0.17'
$env:HTTP_PROXY='';$env:HTTPS_PROXY='';$env:ALL_PROXY='';$env:NO_PROXY='*'
$task7Root='C:/fdz-build';$task7Private="$task7Root/.runtime/task7-independent";$task7Public="$task7Root/docs/verification/task7-independent-evidence"
$task7Account=Get-Content "$task7Private/$Account.json" -Raw | ConvertFrom-Json
if(!$task7Account.username.StartsWith('task7_')){throw '非本轮任务账号'}
$task7Orders=Get-Content "$task7Private/orders.json" -Raw | ConvertFrom-Json
$task7Resources=Get-Content "$task7Private/resources.json" -Raw | ConvertFrom-Json
$task7Raw="$task7Private/maestro-$Label";$task7Output="$task7Public/maestro-$Label"
if(Test-Path "$task7Output/result.json"){throw '不得覆盖首次失败'}
New-Item -ItemType Directory -Force $task7Raw,$task7Output | Out-Null
# 预期身份全部来自本轮真实购买回执；金额保持原冻结流程的两套餐定价。
$task7Args=@('--device','emulator-5554','test','--test-output-dir',$task7Raw,'--debug-output',$task7Raw,
 '-e',"MAESTRO_USERNAME=$($task7Account.username)",'-e',"MAESTRO_PASSWORD=$($task7Account.password)",
 '-e',"TASK7_ORDER_A=$($task7Orders[0].orderId)",'-e',"TASK7_ORDER_B=$($task7Orders[1].orderId)",'-e',"TASK7_ORDER_PENDING=$($task7Orders[2].orderId)",
 '-e',"TASK7_VOUCHER_A=$($task7Orders[0].vouchers[0].publicId)",'-e',"TASK7_VOUCHER_B=$($task7Orders[0].vouchers[1].publicId)",
 '-e',"TASK7_VOUCHER_C=$($task7Orders[1].vouchers[0].publicId)",'-e',"TASK7_PENDING_VOUCHER=$($task7Orders[2].vouchers[0].publicId)",
 '-e',"TASK6_STORE_ID=$($task7Resources[1].storePublicId)",'-e',"TASK6_DEAL_ID=$($task7Resources[1].public_id)",
 '-e','TASK6_TOTAL=.*96\.47','-e','TASK6_QUANTITY=1','-e','TASK6_VOUCHER_TARGET=com.fandazi.app:id/voucher_detail_status')
$task7Return=switch($Entry){'orders'{'com.fandazi.app:id/orders_segments'}'order'{'com.fandazi.app:id/group_order_detail_title'}'voucher'{'com.fandazi.app:id/voucher_detail_info'}}
$task7Args+=@('-e',"TASK7_ENTRY=$Entry",'-e',"TASK7_RETURN_ID=$task7Return","$task7Root/maestro/$Flow")
$task7Started=Get-Date -Format o
& 'C:/Users/caid0/.local/maestro/bin/maestro.bat' @task7Args *> "$task7Private/maestro-$Label.log"
$task7Exit=$LASTEXITCODE
# 公开只复制显式截图；完整命令状态另由脱敏统计器读取，口令不会进入公开树。
Get-ChildItem $task7Raw -Recurse -File -Filter '*.png' | Where-Object {$_.FullName -match 'takeScreenshot'} | ForEach-Object {
 $task7Destination=Join-Path $task7Output ([IO.Path]::GetRelativePath($task7Raw,$_.FullName))
 New-Item -ItemType Directory -Force (Split-Path $task7Destination) | Out-Null
 Copy-Item -LiteralPath $_.FullName -Destination $task7Destination
}
@{exitCode=$task7Exit;flow=$Flow;accountRole=$Account;startedAt=$task7Started;finishedAt=(Get-Date -Format o);apkSha256=(Get-FileHash "$task7Root/android-app/app/build/outputs/apk/debug/app-debug.apk").Hash;orderIds=@($task7Orders.orderId);voucherIds=@($task7Orders.vouchers.publicId)} | ConvertTo-Json -Depth 5 | Set-Content "$task7Output/result.json" -Encoding utf8
Write-Output "独立 Maestro $Label exit=$task7Exit"
exit $task7Exit
