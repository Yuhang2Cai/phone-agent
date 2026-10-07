<# R1独立复验准备：校验明确的新旧索引，导出新的隔离测试源，保存产品blob相同性及旧失败证据摘要。
不暂存、不提交、不修改产品；原始快照与命令信息只存既有独立验收私有目录的r1子目录。
#>
param([switch]$VerifyExistingExport)
$ErrorActionPreference='Stop'
$private='C:/fdz-build/.runtime/task8-independent-final/r1'
if($VerifyExistingExport){
    if(!(Test-Path -LiteralPath $private)){throw '只读续检要求既有导出存在'}
}else{
    if(Test-Path -LiteralPath $private){throw 'R1目录已存在，禁止覆盖'}
    New-Item -ItemType Directory -Path $private|Out-Null
}
$repos=@(
    @{root='C:/fdz-build';folder='phone';old='1cc8fff9b0077672fd87d26a460a0fda5b7b7996';tree='b948a281232382a07271237b8d32d39b6f94a939';head='f2f08035ed23676c88269d083b3536e200741ac0'},
    @{root='D:/project/-Demo-master';folder='-Demo-master';old='0d29e30b066dd0b33d8cf244fa75d71ee3d5995a';tree='6c8c1992cec8d3d875cf057cd93b25bd5f8f9d12';head='9fdc5e16146423120bab18a722090643fa0d3203'}
)
$records=@()
$freeze=Get-Content 'C:/fdz-build/docs/verification/task8-resumed-evidence/freeze-preacceptance.json' -Raw|ConvertFrom-Json
foreach($repo in $repos){
    if((& git -C $repo.root write-tree).Trim() -ne $repo.tree -or (& git -C $repo.root rev-parse HEAD).Trim() -ne $repo.head){throw 'R1冻结版本不符'}
    $diff=@(& git -C $repo.root diff --name-only $repo.old $repo.tree)
    if(@($diff|Where-Object {$_ -notlike 'docs/*'}).Count){throw '产品路径存在变化，不得复用原构建/UI证据'}
    # 索引blob逐项保持；原冻结工作区中的非文档文件也必须保持，在线服务不能混用被改动的产品。
    $productPaths=@(& git -C $repo.root ls-tree -r --name-only $repo.tree|Where-Object {$_ -notlike 'docs/*'})
    $checked=0
    $frozen=$freeze.repositories|Where-Object repository -eq $repo.root
    foreach($file in $frozen.files|Where-Object {$_.path -notlike 'docs/*'}){
        if((Get-FileHash -LiteralPath (Join-Path $repo.root $file.path) -Algorithm SHA256).Hash -ne $file.workingSha256){throw '在线冻结产品文件变化'}
        $checked++
    }
    if(!$VerifyExistingExport){
        & git -C $repo.root archive --format=zip --output="$private/$($repo.folder).zip" $repo.tree
        if($LASTEXITCODE){throw 'R1归档失败'}
        Expand-Archive -LiteralPath "$private/$($repo.folder).zip" -DestinationPath "$private/$($repo.folder)"
    }
    $records+=@{repository=$repo.root;head=$repo.head;tree=$repo.tree;previousTree=$repo.old;changedPaths=$diff;unchangedNonDocsPaths=$productPaths.Count;unchangedWorkingProductFiles=$checked}
}
# 两测试之间的同目录动态导入必须在索引快照中闭合，同时核验当前冻结工作文件与交付测试内容相同。
$tests=@()
foreach($name in @('test_live_write_boundaries.py','test_boundary_fixture_isolation.py')){
    $relative="docs/verification/task8-service-evidence/$name"
    $exported=(Get-FileHash "$private/phone/$relative" -Algorithm SHA256).Hash
    # Git归档受换行转换影响，CRLF与LF不能直接视为源码变化；同时核验Git清洗后的blob与规范化文本。
    $snapshotText=[IO.File]::ReadAllText("$private/phone/$relative").Replace("`r`n","`n")
    $workingText=[IO.File]::ReadAllText("C:/fdz-build/$relative").Replace("`r`n","`n")
    $blob=(& git -C C:/fdz-build rev-parse "b948a281232382a07271237b8d32d39b6f94a939:$relative").Trim()
    $exportBlob=(& git -C C:/fdz-build hash-object --path=$relative "$private/phone/$relative").Trim()
    $workingBlob=(& git -C C:/fdz-build hash-object --path=$relative "C:/fdz-build/$relative").Trim()
    if($snapshotText -cne $workingText -or $blob -ne $exportBlob -or $blob -ne $workingBlob){throw '冻结测试源与索引不一致'}
    $tests+=@{path=$relative;sha256=$exported;blob=$blob;normalizedTextEqual=$true;workingSha256=(Get-FileHash "C:/fdz-build/$relative" -Algorithm SHA256).Hash}
}
$oldCopy='C:/fdz-build/docs/verification/2026-10-02-task8-independent-acceptance-evidence/test_live_write_boundaries_current.py'
@{createdAt=(Get-Date -Format o);repositories=$records;tests=$tests;historicalFailedCopySha256=(Get-FileHash $oldCopy -Algorithm SHA256).Hash}|ConvertTo-Json -Depth 8|Set-Content "$PSScriptRoot/source-closure.json" -Encoding utf8
Write-Output 'R1索引、产品blob相同及测试导入闭合已核验，独立源导出完成。'
