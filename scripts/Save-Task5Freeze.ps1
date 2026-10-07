# Task5 交接工具：以实施前字节副本为边界生成源码归属差异，不暂存、不提交，不修改产品文件。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$before = Join-Path $root '.runtime/task5-source-before'
$evidence = Join-Path $root 'docs/verification/task5-evidence'
$base = 'c614bd57218dd315e4ce03a60537023526cd6d49'
$rows = [Collections.Generic.List[object]]::new()
$patches = [Collections.Generic.List[string]]::new()
Push-Location $root
try {
    # 有实施前副本的共享文件只记录实际变化；原有售后分支保留在前像，不冒称为 Task5 新增。
    foreach ($file in Get-ChildItem -LiteralPath $before -Recurse -File) {
        $relative = [IO.Path]::GetRelativePath($before, $file.FullName).Replace('\','/')
        $current = Join-Path $root $relative
        $oldHash = (Get-FileHash -LiteralPath $file.FullName).Hash
        $newHash = (Get-FileHash -LiteralPath $current).Hash
        if ($oldHash -eq $newHash) { continue }
        & git cat-file -e "${base}:$relative" 2>$null
        $inBase = $LASTEXITCODE -eq 0
        $rows.Add([pscustomobject]@{path=$relative;classification=if($inBase){'Task5 修改已有源码'}else{'Task5 修改原先未跟踪依赖'};before=$oldHash;after=$newHash;inBase=$inBase;preimage=$file.FullName})
        $oldRelative = [IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/')
        $diff = & git -c core.autocrlf=false diff --no-index -- $oldRelative $relative 2>$null
        if($LASTEXITCODE -ne 1){throw "差异生成异常：$relative"}
        # 输出统一到仓库相对路径；补丁必须应用在指定 before 哈希版本上，不能盲套 HEAD。
        $text = ($diff -join "`n").Replace('a/.runtime/task5-source-before/', 'a/')
        $patches.Add($text)
    }
    # 这些是本轮确实新增的产品文件；其余未跟踪文件不能用 Git 的 ?? 判定为本 Agent 创建。
    $created = @(
        'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentPublicCards.kt',
        'android-app/app/src/test/java/com/fandazi/app/feature/agent/Task5ChatStateTest.kt',
        'android-app/app/src/main/res/layout/item_agent_empty.xml',
        'android-app/app/src/main/res/layout/item_agent_context.xml',
        'android-app/app/src/main/res/drawable/agent_send_background.xml',
        'android-app/app/src/main/res/drawable/agent_chat_input_background.xml',
        'android-app/app/src/main/res/drawable/agent_chat_bot_bubble.xml',
        'android-app/app/src/main/res/drawable/agent_chat_user_bubble.xml',
        'android-app/app/src/main/res/drawable/agent_chat_empty_avatar.xml'
    )
    foreach($relative in $created){
        $current=Join-Path $root $relative
        if(Test-Path -LiteralPath (Join-Path $before $relative)){throw "新增归属与副本冲突：$relative"}
        $rows.Add([pscustomobject]@{path=$relative;classification='Task5 新增';before=$null;after=(Get-FileHash -LiteralPath $current).Hash;inBase=$false;preimage=$null})
        $diff=& git -c core.autocrlf=false diff --no-index -- /dev/null $relative 2>$null
        if($LASTEXITCODE -ne 1){throw "新增差异生成异常：$relative"}
        $patches.Add($diff -join "`n")
    }
    $patches -join "`n`n" | Set-Content -LiteralPath (Join-Path $evidence 'task5-own-source.patch') -Encoding utf8
    # 运行器和流程属于本任务；唯一旧 Task5 流程明确注明是原未跟踪文件，不包含 Task8 流程。
    $support = @(Get-ChildItem -LiteralPath (Join-Path $root 'scripts') -File | Where-Object {$_.Name -match 'Task5'}) +
        @(Get-ChildItem -LiteralPath (Join-Path $root 'maestro') -File -Filter 'task5-*.yaml')
    foreach($file in $support){
        $rows.Add([pscustomobject]@{path=[IO.Path]::GetRelativePath($root,$file.FullName).Replace('\','/');classification=if($file.Name -eq 'task5-ai-chat-page.yaml'){'Task5 重写原未跟踪流程，非 Task8'}else{'Task5 新增运行器或流程'};before=$null;after=(Get-FileHash -LiteralPath $file.FullName).Hash;inBase=$false;preimage=$null})
    }
    # 依赖只作精确版本清单，不加入 Task5 源码补丁；共享导航只需现有只读订单入口。
    $dependencies = @('android-app/app/src/main/res/navigation/nav_graph.xml',
        'android-app/app/src/main/java/com/fandazi/app/core/navigation/SessionRouteCoordinator.kt',
        'android-app/app/src/main/java/com/fandazi/app/FandaziApplication.kt',
        'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentEntryPoint.kt',
        'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentSheetFragment.kt',
        'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentFloatingButtonPolicy.kt',
        'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentProgressPresentation.kt',
        'android-app/app/src/main/java/com/fandazi/app/feature/voucher/GroupOrderDetailFragment.kt',
        'android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherRepository.kt',
        'android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherModels.kt',
        'android-app/app/src/main/res/layout/fragment_group_order_detail.xml',
        'android-app/app/src/main/res/layout/item_voucher_row.xml')
    $dependencyRows = @($dependencies | ForEach-Object {
        $path=Join-Path $root $_
        $preimage=Join-Path $before $_
        if(Test-Path -LiteralPath $path){[pscustomobject]@{path=$_;sha256=(Get-FileHash -LiteralPath $path).Hash;before=if(Test-Path -LiteralPath $preimage){(Get-FileHash -LiteralPath $preimage).Hash}else{$null};task5Modified=$false;note='原先工作区依赖，不列为 Task5 交付；最小 hunk 见 scope-boundary.md'}}
    })
    $protected = @(Get-Content -LiteralPath (Join-Path $evidence 'backend-final-regression.json') -Raw | ConvertFrom-Json).protectedFiles
    $guard = @($protected | ForEach-Object {[pscustomobject]@{path=$_.path;before=$_.before;current=(Get-FileHash -LiteralPath $_.path).Hash;equal=($_.before -eq (Get-FileHash -LiteralPath $_.path).Hash)}})
    if(@($guard | Where-Object {!$_.equal}).Count){throw '原真实夹具文件发生变化'}
    $head=(& git rev-parse HEAD).Trim()
    $staged=@(& git diff --cached --name-only)
    [pscustomobject]@{recordedAt=(Get-Date -Format o);phoneHead=$head;expectedBase=$base;staged=$staged;source=$rows;unchangedDependencies=$dependencyRows;protectedFiles=$guard;patchBase='只适用实施前副本 SHA；不将原先 Task8 dirty 当作 Task5 新增'} |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'task5-freeze-manifest.json') -Encoding utf8
    # 写集是实际生成的逐文件清单，依赖版本仅写入 manifest，不因应用能编译就冒称本任务修改了依赖。
    $written=@($rows | ForEach-Object {Join-Path $root $_.path}) +
        @((Join-Path $root 'docs/verification/2026-10-02-task5-development.md'),
          (Join-Path $root '.superpowers/sdd/2026-09-29-group-buying-agent-refactor-implementation/task-5-report.md'),
          'D:/project/-Demo-master/docs/experiments/intent-routing-experiments.md') +
        @(Get-ChildItem -LiteralPath $evidence -Recurse -File | ForEach-Object {$_.FullName})
    $written | ForEach-Object {$_.Replace('\','/')} | Sort-Object -Unique |
        Set-Content -LiteralPath (Join-Path $evidence 'task5-write-set.txt') -Encoding utf8
} finally { Pop-Location }
exit 0
