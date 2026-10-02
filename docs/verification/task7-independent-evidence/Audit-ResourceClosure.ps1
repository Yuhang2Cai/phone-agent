# 独立资源闭合审查：从Task7实际布局递归追踪资源，区分HEAD已有、新增增量和遗漏原dirty依赖。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Development="$task7Root/docs/verification/task7-evidence";$task7Public="$task7Root/docs/verification/task7-independent-evidence"
$task7Head=@{};git -C $task7Root ls-tree -r --name-only HEAD | ForEach-Object {$task7Head[$_]=$true}
$task7Declared=@{}
Get-Content "$task7Development/source-closure.json" -Raw | ConvertFrom-Json | ForEach-Object {$task7Declared[$_.path]='necessary'}
Get-Content "$task7Development/source-start-delta.json" -Raw | ConvertFrom-Json | Where-Object repo -eq 'phone' | ForEach-Object {$task7Declared[$_.path]='delta'}
$task7Freeze=@{};Get-Content "$task7Development/source-freeze.json" -Raw | ConvertFrom-Json | Select-Object -ExpandProperty files | Where-Object repo -eq 'phone' | ForEach-Object {$task7Freeze[$_.path]=$_.sha256}
$task7Queue=[Collections.Generic.Queue[string]]::new()
@('fragment_orders','fragment_group_order_detail','fragment_voucher_detail','item_group_order_card','item_voucher_row') | ForEach-Object {$task7Queue.Enqueue("android-app/app/src/main/res/layout/$_.xml")}
$task7Seen=@{};$task7Edges=@();$task7Missing=@();$task7Colors=@()
while($task7Queue.Count){
 $task7Path=$task7Queue.Dequeue()
 if($task7Seen.ContainsKey($task7Path)){continue};$task7Seen[$task7Path]=$true
 $task7Absolute=Join-Path $task7Root $task7Path
 if(!(Test-Path -LiteralPath $task7Absolute)){throw "实际资源不存在:$task7Path"}
 # 元素属性中的资源引用由XML解析器读取，避免注释中的样例被当成真实构建依赖。
 [xml]$task7Xml=Get-Content $task7Absolute -Raw
 foreach($task7Attribute in $task7Xml.SelectNodes('//@*')){
  if($task7Attribute.Value -notmatch '^@(layout|drawable|color)/([A-Za-z0-9_]+)$'){continue}
  $task7Type=$Matches[1];$task7Name=$Matches[2]
  if($task7Type -eq 'color'){$task7Colors+=@{from=$task7Path;token=$task7Name};continue}
  $task7Candidates=@(Get-ChildItem "$task7Root/android-app/app/src/main/res/$task7Type" -File | Where-Object BaseName -eq $task7Name)
  if($task7Candidates.Count -ne 1){throw "资源候选异常:$task7Name"}
  $task7Dependency=[IO.Path]::GetRelativePath($task7Root,$task7Candidates[0].FullName).Replace('\','/')
  $task7Edges+=@{from=$task7Path;to=$task7Dependency;inHead=$task7Head.ContainsKey($task7Dependency);declared=$task7Declared[$task7Dependency]}
  if(!$task7Head.ContainsKey($task7Dependency) -and !$task7Declared.ContainsKey($task7Dependency) -and !($task7Missing.path -contains $task7Dependency)){
   $task7Backup="$task7Root/.runtime/task7/source-start/phone/$task7Dependency"
   $task7Before=if(Test-Path -LiteralPath $task7Backup){(Get-FileHash -LiteralPath $task7Backup).Hash}else{$null}
   $task7Missing+=@{path=$task7Dependency;beforeSha256=$task7Before;frozenSha256=$task7Freeze[$task7Dependency];currentSha256=(Get-FileHash -LiteralPath $task7Candidates[0].FullName).Hash;originalDirty=($task7Before -eq $task7Freeze[$task7Dependency])}
  }
  if($task7Candidates[0].Extension -eq '.xml'){$task7Queue.Enqueue($task7Dependency)}
 }
}
@{at=(Get-Date -Format o);checkedResourceFiles=$task7Seen.Count;edges=$task7Edges;missingNecessaryOriginalDirty=$task7Missing;referencedAfterSaleColors=@($task7Colors | Where-Object token -like 'after_sale_*')} | ConvertTo-Json -Depth 6 | Set-Content "$task7Public/resource-closure-audit.json" -Encoding utf8
Write-Output "独立资源闭合: 递归$($task7Seen.Count)个资源，必要原dirty未列$($task7Missing.Count)项"
