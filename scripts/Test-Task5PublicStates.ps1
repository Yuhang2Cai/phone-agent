# Task5 公开协议场景：真实订单读取、真实只读 DAG、准备确认后取消；绝不点击或发送写入确认。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$account = Get-Content (Join-Path $root '.runtime/task5-account.json') -Raw | ConvertFrom-Json
$headers = @{Authorization="Bearer $($account.token)"}
$fixture = Get-Content (Join-Path $root '.runtime/task5-order-fixture.json') -Raw | ConvertFrom-Json
$rows = [Collections.Generic.List[object]]::new()
function Send-PublicState([string]$Name,[string]$Session,[string]$Text) {
    # 响应已由 Spring 脱敏，证据只保存公开结果，认证头与准备凭证不输出。
    $body=@{text=$Text;mode='CONFIRM'}|ConvertTo-Json
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $result=Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/agent/sessions/$Session/messages" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -NoProxy -TimeoutSec 125
    $watch.Stop()
    $rows.Add([pscustomobject]@{scenario=$Name;sessionId=$Session;elapsedMs=$watch.ElapsedMilliseconds;response=$result})
    [pscustomobject]@{scenario=$Name;status=$result.status;message=$result.message;cards=@($result.cards).Count;events=@($result.events).Count}|ConvertTo-Json -Compress
    return $result
}
$orderSession=[guid]::NewGuid().ToString()
$order=Send-PublicState 'order-query' $orderSession '查询我的团购订单'
if($order.status -eq 'NEEDS_INPUT') { $order=Send-PublicState 'order-query-operation' $orderSession 'query' }
$city=@(Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/stores/cities' -NoProxy)[0].cityCode
$dag=Send-PublicState 'read-only-composite' ([guid]::NewGuid().ToString()) "查询我的团购订单，同时推荐城市代码$city 的团购套餐"
$prepareSession=[guid]::NewGuid().ToString()
$prepare=Send-PublicState 'prepare-only' $prepareSession "购买团购套餐，套餐编号 $($fixture.dealId)，数量1，只准备确认摘要"
if($prepare.status -eq 'NEEDS_INPUT' -and $prepare.message -like '*查询订单*购买套餐*') {
    $prepare=Send-PublicState 'prepare-operation' $prepareSession 'purchase'
}
if($prepare.status -eq 'NEEDS_INPUT' -and $prepare.message -like '*套餐编号*') {
    $prepare=Send-PublicState 'prepare-deal-id' $prepareSession $fixture.dealId
}
if($prepare.status -eq 'AWAITING_CONFIRMATION') { $cancel=Send-PublicState 'cancel-preparation' $prepareSession '取消' }
$rows|ConvertTo-Json -Depth 40|Set-Content (Join-Path $root 'docs/verification/task5-evidence/public-states-api.json') -Encoding utf8
if($order.status -ne 'COMPLETED' -or !@($order.cards).Count){throw '隔离真实订单没有被聊天读取'}
if($prepare.status -ne 'AWAITING_CONFIRMATION' -or $cancel.nextAction -ne 'cancelled'){throw '真实准备/取消未达到预期'}
# 一旦观察到业务写工具立即记录失败；本脚本没有任何确认语句，不能把准备当成已购买。
$writes=@($rows.response.events|Where-Object {$_.toolName -in @('create_group_buy_order','pay_mock_order','request_refund','submit_complaint','redeem_voucher')})
if($writes.Count){throw '只读/准备场景观察到禁止的业务写工具'}
