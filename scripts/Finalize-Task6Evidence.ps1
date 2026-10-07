# Task6 最终证据冻结：比较本轮开始字节快照，区分本次改动与保留依赖，不 stage/commit。
param()
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$evidence="$root/docs/verification/task6-evidence"
# 源快照覆盖双仓实际产品/测试/运行脚本；配置与构建入口另补只读指纹，排除密钥和运行目录。
& "$root/scripts/Freeze-Task6Sources.ps1" -Phase after
$before=Get-Content "$root/.runtime/task6-source-before/manifest.json" -Raw | ConvertFrom-Json
$after=Get-Content "$root/.runtime/task6-source-after/manifest.json" -Raw | ConvertFrom-Json
$index=@{}
foreach($row in $before){$index[$row.repo+'/'+$row.path]=$row}
$changes=@()
$patches=@()
foreach($row in $after){
    $key=$row.repo+'/'+$row.path
    $prior=$index[$key]
    if($prior -and $prior.sha256 -eq $row.sha256){continue}
    $repo=if($row.repo -eq 'phone'){$root}else{'D:/project/-Demo-master'}
    $old="$root/.runtime/task6-source-before/$key"
    $new="$root/.runtime/task6-source-after/$key"
    # 仅比较记录下来的原字节与当前字节；原 dirty 不被误算成全部由本任务新增。
    $changes += [pscustomobject]@{repo=$row.repo;path=$row.path;before=if($prior){$prior.sha256}else{$null};after=$row.sha256;alreadyPresentBefore=[bool]$prior}
    if($prior -and $row.path -notmatch '\.(png|jpg|jar)$'){
        $patches += "`n### $key"
        $patches += & git -c core.quotepath=false diff --no-index -- $old $new
    }
}
$changes | ConvertTo-Json -Depth 4 | Set-Content "$evidence/task6-delta.json"
$patches | Set-Content "$evidence/task6-baseline-hunks.patch"
Copy-Item "$root/.runtime/task6-source-before/manifest.json" "$evidence/source-before.json"
Copy-Item "$root/.runtime/task6-source-after/manifest.json" "$evidence/source-final.json"
foreach($name in @('phone','agent')){
    # 两仓分别冻结实际源码清单，不能只把未提交 dirty 包装成 HEAD 版本。
    @($after | Where-Object {$_.repo -eq $name}) | ConvertTo-Json -Depth 4 | Set-Content "$evidence/$name-source.json"
}
$inputs=@()
foreach($repo in @(@{name='phone';root=$root;paths=@('backend/pom.xml','android-app/build.gradle.kts','android-app/app/build.gradle.kts','android-app/settings.gradle.kts','android-app/gradle.properties','android-app/gradle/wrapper','contracts','data/seed','agent-service/src')},@{name='agent';root='D:/project/-Demo-master';paths=@('config','requirements.txt','pyproject.toml','node')})){
    foreach($path in $repo.paths){
        $target=Join-Path $repo.root $path
        if(!(Test-Path -LiteralPath $target)){continue}
        foreach($file in Get-ChildItem -LiteralPath $target -File -Recurse){
            if($file.FullName -match '__pycache__|\.pyc$|chroma_db' -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){continue}
            $inputs += [pscustomobject]@{repo=$repo.name;path=[IO.Path]::GetRelativePath($repo.root,$file.FullName);sha256=(Get-FileHash $file.FullName).Hash}
        }
    }
}
# Agent 根目录历史路由/数据库入口也是当前依赖，只计算源码摘要，不读取旧计划/会话或运行密钥。
foreach($file in Get-ChildItem -LiteralPath 'D:/project/-Demo-master' -File -Filter '*.py'){
    $inputs += [pscustomobject]@{repo='agent';path=$file.Name;sha256=(Get-FileHash $file.FullName).Hash}
}
$inputs | ConvertTo-Json | Set-Content "$evidence/compile-inputs.json"
# 写集按本轮相对基线的改动列出；必要原 dirty 只读依赖不等于本轮写入授权。
$lines=@('Task6 写集与冻结版本','状态：开发自测收尾，待新的独立验收；无 stage/commit。',
    '手机 BASE 158c21b385a11406d72ea12bd139092f0b574ff7；Agent BASE f9fcff3e8f2b9f41410762c5b6383f883b9cd742。',
    '本次新改/新增（相对 source-before 字节基线；alreadyPresentBefore=true 表示在原 dirty 上补必要 hunk）：')
foreach($row in $changes){$lines += "$($row.repo)/$($row.path) | before=$($row.before) | after=$($row.after) | 原基线存在=$($row.alreadyPresentBefore)"}
$lines += @('本轮工具补充：Freeze-Task6Sources.ps1 在基线采集前新建，因此未出现在上述差异；属于本轮工具，不是历史依赖。',
    '必要原 dirty 依赖（保持原字节、不改其行为）：ConfirmationGrantService/Repository、PurchaseQuote/Repository、GroupBuyOrderEntity/Repository、VoucherEntity/Repository、AfterSaleImageLoader、BusinessDisplay、GroupOrderDetailFragment、VoucherDetailFragment、SessionRouteCoordinator、Auth/ApiClient、旧 agent-service 客户端导入仅供隔离 H2 合同测试。其实际 SHA 见 source-before/source-final；未将 Task7 详情收尾归于 Task6。',
    '必要原 dirty 上本轮 hunk：FandaziApplication仅购买wiring注释；MainActivity仅保护目标Bundle/401返回；DealDetailFragment仅真实套餐购买/登录resumePurchase；nav_graph仅checkout dialog/真实store参数。逐行相对基线见task6-baseline-hunks.patch。',
    'Agent仅service/engine.py购买凭证期限hunk与tests/test_purchase_workflow.py；dag_runtime和Task8其他源原样保留。Task5历史按钮replyToCard守卫未修改。',
    '开发报告与SDD报告是本轮新文档；最终报告指纹见freeze-receipt.json，主plan/spec/UI确认记录未修改。')
$lines | Set-Content "$evidence/task6-write-set.txt"
# 公开证据逐文件冻结，涵盖命令计数/XML/截图/服务事实；排除清单自身与最终receipt避免循环摘要。
$evidenceRows=@(Get-ChildItem -LiteralPath $evidence -Recurse -File | Where-Object {$_.Name -notin @('freeze-receipt.json','evidence-manifest.json')} | ForEach-Object {
    [pscustomobject]@{path=[IO.Path]::GetRelativePath($evidence,$_.FullName);sha256=(Get-FileHash $_.FullName).Hash}
})
$evidenceRows | ConvertTo-Json | Set-Content "$evidence/evidence-manifest.json"
# 摘要覆盖实际最终报告及源清单；生成自身摘要会循环，因此 receipt 自身不纳入摘要。
$receipt=[ordered]@{frozenAt=(Get-Date -Format o);status='待独立验收';phoneHead=(& git -C $root rev-parse HEAD);agentHead=(& git -C 'D:/project/-Demo-master' rev-parse HEAD);sourceFinalFiles=$after.Count;sourceFinalSha256=(Get-FileHash "$evidence/source-final.json").Hash;compileInputsSha256=(Get-FileHash "$evidence/compile-inputs.json").Hash;deltaSha256=(Get-FileHash "$evidence/task6-delta.json").Hash;hunksSha256=(Get-FileHash "$evidence/task6-baseline-hunks.patch").Hash;writeSetSha256=(Get-FileHash "$evidence/task6-write-set.txt").Hash;developmentReportSha256=(Get-FileHash "$root/docs/verification/2026-10-02-task6-development.md").Hash;sddReportSha256=(Get-FileHash "$root/.superpowers/sdd/2026-09-29-group-buying-agent-refactor-implementation/task-6-report.md").Hash;apkSha256=(Get-FileHash "$root/android-app/app/build/outputs/apk/debug/app-debug.apk").Hash;figma=@{file='FxsWKGs10z7BL1MhFAOqFN';ui07='29:41';ui08='29:59';ui09='29:82'}}
$receipt['phoneSourceSha256']=(Get-FileHash "$evidence/phone-source.json").Hash
$receipt['agentSourceSha256']=(Get-FileHash "$evidence/agent-source.json").Hash
$receipt['evidenceManifestSha256']=(Get-FileHash "$evidence/evidence-manifest.json").Hash
$receipt | ConvertTo-Json -Depth 5 | Set-Content "$evidence/freeze-receipt.json"
Write-Output "frozen: sourceFiles=$($after.Count), changedFiles=$($changes.Count)"
