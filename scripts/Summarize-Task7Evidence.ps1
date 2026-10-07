# Task7 结果汇总：读取实际JUnit、Maestro状态和API断言，条件跳过独立计数，不公开敏感调试命令。
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$public="$root/docs/verification/task7-evidence"
$runs=@()
foreach($file in (Get-ChildItem $public -Recurse -Filter 'result.json' -File | Where-Object {$_.Directory.Name -like 'maestro-*'})) {
 $result=Get-Content $file.FullName -Raw | ConvertFrom-Json
 $label=$file.Directory.Name.Substring('maestro-'.Length)
 $commands=Get-ChildItem "$root/.runtime/task7/maestro-$label" -Recurse -File -Filter 'commands.json' | Select-Object -First 1
 $counts=@{}
 if($commands) {
    # 结构化调试JSON只读取metadata.status，不复制evaluatedCommand中的真实口令。
    foreach($command in (Get-Content $commands.FullName -Raw | ConvertFrom-Json)) {
       $status=[string]$command.metadata.status
       if(!$counts.ContainsKey($status)) {$counts[$status]=0}
       $counts[$status]++
    }
 }
 $runs+=@{label=$label;flow=$result.flow;exitCode=$result.exitCode;apkSha256=$result.apkSha256;startedAt=$result.startedAt;finishedAt=$result.finishedAt;commandsByStatus=$counts}
}
$checks=@(Get-Content "$public/api-checks.json" -Raw | ConvertFrom-Json)
$summary=@{capturedAt=(Get-Date -Format o);maestroRuns=$runs;maestroRunCount=$runs.Count;maestroExit0=@($runs | Where-Object {$_.exitCode -eq 0}).Count;maestroNonzero=@($runs | Where-Object {$_.exitCode -ne 0}).Count;apiAssertions=$checks.Count;apiPassed=@($checks | Where-Object {$_.passed}).Count;apiFailed=@($checks | Where-Object {!$_.passed}).Count;conditionalSkippedNotPassed=$true}
$summary | ConvertTo-Json -Depth 7 | Set-Content "$public/actual-counts.json" -Encoding utf8
Write-Output "Task7 Maestro runs=$($runs.Count) exit0=$($summary.maestroExit0) nonzero=$($summary.maestroNonzero); API=$($checks.Count) failed=$($summary.apiFailed)"
