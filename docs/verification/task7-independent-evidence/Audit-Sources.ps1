# Task7 独立源台账核对：真实读取精确开始备份与当前冻结源，不修改源或Git索引。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Development="$task7Root/docs/verification/task7-evidence";$task7Public="$task7Root/docs/verification/task7-independent-evidence"
$task7Start=Get-Content "$task7Development/source-start.json" -Raw | ConvertFrom-Json
$task7Freeze=Get-Content "$task7Development/source-freeze.json" -Raw | ConvertFrom-Json
$task7Delta=Get-Content "$task7Development/source-start-delta.json" -Raw | ConvertFrom-Json
$task7ByKey=@{};$task7Errors=@();$task7Changes=@()
foreach($task7File in $task7Start.files){
 $task7Key="$($task7File.repo):$($task7File.path)";$task7ByKey[$task7Key]=$task7File
 $task7Backup="$task7Root/.runtime/task7/source-start/$($task7File.repo)/$($task7File.path)"
 if(!(Test-Path -LiteralPath $task7Backup) -or (Get-FileHash -LiteralPath $task7Backup).Hash -ne $task7File.sha256){$task7Errors+="开始字节:$task7Key"}
}
foreach($task7File in $task7Freeze.files){
 $task7Key="$($task7File.repo):$($task7File.path)";$task7Before=$task7ByKey[$task7Key]
 $task7Repo=if($task7File.repo -eq 'phone'){$task7Root}else{'D:/project/-Demo-master'}
 if((Get-FileHash -LiteralPath (Join-Path $task7Repo $task7File.path)).Hash -ne $task7File.sha256){$task7Errors+="冻结字节:$task7Key"}
 if(!$task7Before -or $task7Before.sha256 -ne $task7File.sha256){$task7Changes+=$task7Key}
}
# 台账必须完整列出所有变化，既不遗漏新增源，也不能把原dirty归属冒作新实现。
$task7Declared=@($task7Delta | ForEach-Object {"$($_.repo):$($_.path)"})
$task7DeltaMismatch=@(Compare-Object ($task7Changes|Sort-Object) ($task7Declared|Sort-Object))
if($task7DeltaMismatch.Count){$task7Errors+='增量路径不一致'}
$task7Closure=Get-Content "$task7Development/source-closure.json" -Raw | ConvertFrom-Json
$task7Deferred=Get-Content "$task7Development/deferred-source-closure.json" -Raw | ConvertFrom-Json
foreach($task7Item in @($task7Closure)+@($task7Deferred)){
 if((Get-FileHash -LiteralPath (Join-Path $task7Root $task7Item.path)).Hash -ne $task7Item.frozenSha256){$task7Errors+="闭合源:$($task7Item.path)"}
 if($task7Item.PSObject.Properties.Name -contains 'unchangedSinceStart' -and $task7Item.beforeSha256 -ne $task7Item.frozenSha256){$task7Errors+="延期源被修改:$($task7Item.path)"}
}
@{at=(Get-Date -Format o);exactStartSources=$task7Start.files.Count;frozenSources=$task7Freeze.files.Count;actualDelta=$task7Changes.Count;declaredDelta=$task7Declared.Count;necessaryClosure=$task7Closure.Count;deferredClosure=$task7Deferred.Count;errors=$task7Errors;patches=@(Get-FileHash "$task7Development/phone-source-start.delta.patch","$task7Development/agent-source-start.delta.patch"|Select-Object Path,Hash)} | ConvertTo-Json -Depth 5 | Tee-Object -FilePath "$task7Public/source-audit.json"
if($task7Errors.Count){throw '源台账核对失败'}
