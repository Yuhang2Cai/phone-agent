# Task5 真实只读自测：随机账号经 Spring 注册，所有聊天只走 8080，凭据仅写忽略目录。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$runtimeFile = Join-Path $root '.runtime/task5-account.json'
$evidence = Join-Path $root 'docs/verification/task5-evidence'
if (Test-Path -LiteralPath $runtimeFile) {
    $account = Get-Content -LiteralPath $runtimeFile -Raw | ConvertFrom-Json
} else {
    # 仅注册本任务随机隔离用户；不借用 Task8 真实用户、券或订单，密码不进入源码或证据。
    $account = [pscustomobject]@{username=('task5_' + [guid]::NewGuid().ToString('N').Substring(0,12));password=([guid]::NewGuid().ToString('N'));token=''}
    $body = @{username=$account.username;password=$account.password} | ConvertTo-Json
    $auth = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/auth/register' -ContentType 'application/json' -Body $body -NoProxy
    $account.token = $auth.token
    $account | ConvertTo-Json | Set-Content -LiteralPath $runtimeFile -Encoding utf8
}
$headers = @{Authorization="Bearer $($account.token)"}
$cities = @(Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/stores/cities' -NoProxy)
if (!$cities.Count) { throw '真实目录没有城市，不能制造推荐兜底' }
$city = $cities[0].cityCode
$session = [guid]::NewGuid().ToString()
$results = [System.Collections.Generic.List[object]]::new()
function Send-ReadOnlyQuestion([string]$Name, [string]$Text) {
    # 保存公开回复与事件用于源数据对照；不记录请求头、认证令牌或用户密码。
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $body = @{text=$Text;mode='CONFIRM'} | ConvertTo-Json
    $result = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/agent/sessions/$session/messages" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -NoProxy -TimeoutSec 125
    $watch.Stop()
    $results.Add([pscustomobject]@{scenario=$Name;elapsedMs=$watch.ElapsedMilliseconds;response=$result})
    [pscustomobject]@{scenario=$Name;status=$result.status;message=$result.message;cardCount=@($result.cards).Count;eventCount=@($result.events).Count} | ConvertTo-Json -Compress
}
Send-ReadOnlyQuestion 'greeting' '你好'
Send-ReadOnlyQuestion 'missing-city' '推荐附近的团购套餐'
Send-ReadOnlyQuestion 'resume-city' $city
Send-ReadOnlyQuestion 'multi-turn' '谢谢'
Send-ReadOnlyQuestion 'store-recommendation' '推荐附近的餐厅'
Send-ReadOnlyQuestion 'resume-store-city' $city
$results | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath (Join-Path $evidence 'readonly-chat-api.json') -Encoding utf8
# SSE 回放只读取本人会话，id 为数据库游标；原身份保留于 payload，不能混用 sequence。
# curl 的总时限覆盖已连接但永不结束的 SSE；退出 28 表示主动截取窗口结束，不当成业务失败。
& curl.exe --silent --noproxy '*' --max-time 5 -H "Authorization: Bearer $($account.token)" -H 'Last-Event-ID: 0' "http://127.0.0.1:8080/api/agent/sessions/$session/events" -o (Join-Path $evidence 'readonly-chat-sse.txt')
# 六个只读场景必须达到真实预期状态；SSE 截取超时不能掩盖任何请求失败。
$expected = @('COMPLETED','NEEDS_INPUT','COMPLETED','COMPLETED','NEEDS_INPUT','COMPLETED')
for ($index = 0; $index -lt $results.Count; $index++) {
    if ($results[$index].response.status -ne $expected[$index]) { throw "只读场景状态不符：$($results[$index].scenario)" }
}
if (!@($results[2].response.cards).Count -or !@($results[5].response.cards).Count) { throw '实际恢复没有返回任何真实卡片' }
exit 0
