# 独立资源闭合审查：从Task7实际布局递归追踪资源，区分HEAD已有、新增增量和遗漏原dirty依赖。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Development="$task7Root/docs/verification/task7-evidence";$task7Public="$task7Root/docs/verification/task7-independent-evidence/r1";$task7R1="$task7Root/docs/verification/task7-r1-evidence"
$task7Head=@{};git -C $task7Root ls-tree -r --name-only HEAD | ForEach-Object {$task7Head[$_]=$true}
$task7Declared=@{}
Get-Content "$task7R1/source-closure.json" -Raw | ConvertFrom-Json | ForEach-Object {$task7Declared[$_.path]='necessary'}
Get-Content "$task7Development/source-start-delta.json" -Raw | ConvertFrom-Json | Where-Object repo -eq 'phone' | ForEach-Object {$task7Declared[$_.path]='delta'}
$task7Freeze=@{};Get-Content "$task7Development/source-freeze.json" -Raw | ConvertFrom-Json | Select-Object -ExpandProperty files | Where-Object repo -eq 'phone' | ForEach-Object {$task7Freeze[$_.path]=$_.sha256}
$task7Queue=[Collections.Generic.Queue[string]]::new()
@('fragment_orders','fragment_group_order_detail','fragment_voucher_detail','item_group_order_card','item_voucher_row') | ForEach-Object {$task7Queue.Enqueue("android-app/app/src/main/res/layout/$_.xml")}
$task7Seen=@{};$task7Edges=@();$task7Missing=@();$task7Colors=@();$task7XmlCount=0
[xml]$task7ColorXml=Get-Content "$task7Root/android-app/app/src/main/res/values/colors.xml" -Raw
[xml]$task7HeadColors=(& git -C $task7Root show HEAD:android-app/app/src/main/res/values/colors.xml) -join "`n"
$task7TokenClosure=Get-Content "$task7R1/color-token-closure.json" -Raw | ConvertFrom-Json
while($task7Queue.Count){
 $task7Path=$task7Queue.Dequeue()
 if($task7Seen.ContainsKey($task7Path)){continue};$task7Seen[$task7Path]=$true
 $task7Absolute=Join-Path $task7Root $task7Path
 if(!(Test-Path -LiteralPath $task7Absolute)){throw "实际资源不存在:$task7Path"}
 if([IO.Path]::GetExtension($task7Path) -ne '.xml'){continue};$task7XmlCount++
 # 元素属性中的资源引用由XML解析器读取，避免注释中的样例被当成真实构建依赖。
 [xml]$task7Xml=Get-Content $task7Absolute -Raw
 foreach($task7Attribute in $task7Xml.SelectNodes('//@*')){
  if($task7Attribute.Value -notmatch '^@(layout|drawable|color)/([A-Za-z0-9_]+)$'){continue}
  $task7Type=$Matches[1];$task7Name=$Matches[2]
  if($task7Type -eq 'color'){
   $task7Definition=$task7ColorXml.SelectSingleNode("/resources/color[@name='$task7Name']")
   $task7InHead=$null -ne $task7HeadColors.SelectSingleNode("/resources/color[@name='$task7Name']")
   $task7DeclaredToken=$task7TokenClosure | Where-Object token -eq $task7Name
   $task7Covered=$task7InHead -or ($null -ne $task7DeclaredToken)
   $task7Colors+=@{from=$task7Path;token=$task7Name;covered=$task7Covered;inHead=$task7InHead}
   if(!$task7Definition -or !$task7Covered){throw "颜色未闭合:$task7Name"}
   if($task7DeclaredToken -and $task7Definition.InnerText -ne $task7DeclaredToken.value){throw "颜色值与归属清单不符:$task7Name"}
   continue
  }
  $task7Candidates=@(Get-ChildItem "$task7Root/android-app/app/src/main/res/$task7Type" -File | Where-Object BaseName -eq $task7Name)
  if($task7Candidates.Count -ne 1){throw "资源候选异常:$task7Name"}
  $task7Dependency=[IO.Path]::GetRelativePath($task7Root,$task7Candidates[0].FullName).Replace('\','/')
  $task7Edges+=@{from=$task7Path;to=$task7Dependency;inHead=$task7Head.ContainsKey($task7Dependency);declared=$task7Declared[$task7Dependency]}
  if(!$task7Head.ContainsKey($task7Dependency) -and !$task7Declared.ContainsKey($task7Dependency) -and !($task7Missing.path -contains $task7Dependency)){
   $task7Backup="$task7Root/.runtime/task7/source-start/phone/$task7Dependency"
   $task7Before=if(Test-Path -LiteralPath $task7Backup){(Get-FileHash -LiteralPath $task7Backup).Hash}else{$null}
   $task7Missing+=@{path=$task7Dependency;beforeSha256=$task7Before;frozenSha256=$task7Freeze[$task7Dependency];currentSha256=(Get-FileHash -LiteralPath $task7Candidates[0].FullName).Hash;originalDirty=($task7Before -eq $task7Freeze[$task7Dependency])}
  }
  $task7Queue.Enqueue($task7Dependency)
 }
}
@{at=(Get-Date -Format o);checkedResourceFiles=$task7Seen.Count;checkedXmlFiles=$task7XmlCount;edges=$task7Edges;missingNecessaryOriginalDirty=$task7Missing;referencedAfterSaleColors=@($task7Colors | Where-Object token -like 'after_sale_*')} | ConvertTo-Json -Depth 6 | Set-Content "$task7Public/resource-closure-audit.json" -Encoding utf8
Write-Output "独立资源闭合: 递归$($task7Seen.Count)个资源，必要原dirty未列$($task7Missing.Count)项"
# 归属不仅看声明数量：逐项核对R0的13个实际遗漏都被必要源和写集收纳，SHA与精确开始备份一致。
$task7B1=Get-Content "$task7Root/docs/verification/task7-independent-evidence/resource-closure-audit.json" -Raw | ConvertFrom-Json
$task7Closure=Get-Content "$task7R1/source-closure.json" -Raw | ConvertFrom-Json
$task7WriteSet=Get-Content "$task7R1/task7-write-set.txt" -Raw
if($task7Closure.Count -ne 25 -or $task7Missing.Count -or $task7Seen.Count -ne 21 -or $task7XmlCount -ne 14){throw '实际递归闭合与限定范围不符'}
$task7ResourceChecks=@()
foreach($task7Item in $task7Closure){
 $task7Hash=(Get-FileHash -LiteralPath (Join-Path $task7Root $task7Item.path)).Hash
 $task7Before=(Get-FileHash -LiteralPath "$task7Root/.runtime/task7/source-start/phone/$($task7Item.path)").Hash
 if($task7Hash -ne $task7Item.frozenSha256 -or $task7Before -ne $task7Item.beforeSha256){throw "必要源字节不符:$($task7Item.path)"}
 $task7ResourceChecks+=@{path=$task7Item.path;currentSha256=$task7Hash;beforeSha256=$task7Before;passed=$true}
}
foreach($task7Item in $task7B1.missingNecessaryOriginalDirty){
 $task7Match=@($task7Closure | Where-Object path -eq $task7Item.path)
 if($task7Match.Count -ne 1 -or $task7Match[0].ownDelta -or !$task7WriteSet.Contains("NECESSARY $($task7Item.path) ")){throw "B1项未真实收纳:$($task7Item.path)"}
 if($task7Match[0].frozenSha256 -ne $task7Item.frozenSha256){throw 'B1项冻结字节被替换'}
}
$task7ColorEntry=$task7Closure | Where-Object path -eq 'android-app/app/src/main/res/values/colors.xml'
if(@(Compare-Object (@('after_sale_ink','after_sale_muted')|Sort-Object) ($task7ColorEntry.necessaryTokens|Sort-Object)).Count -or $task7TokenClosure.Count -ne 2){throw '必要颜色边界不符'}
foreach($task7Token in $task7TokenClosure){if(!$task7WriteSet.Contains("NECESSARY_TOKEN $($task7Token.path)/$($task7Token.token) ")){throw '写集缺精确颜色声明'}}
$task7Receipt=Get-Content "$task7R1/freeze-receipt.json" -Raw | ConvertFrom-Json
$task7MetadataChecks=@()
foreach($task7Item in $task7Receipt.metadata){
 $task7Hash=(Get-FileHash -LiteralPath (Join-Path $task7Root $task7Item.path)).Hash
 if($task7Hash -ne $task7Item.sha256){throw "R1元数据与冻结receipt不符:$($task7Item.path)"}
 $task7MetadataChecks+=@{path=$task7Item.path;sha256=$task7Hash;passed=$true}
}
foreach($task7Item in $task7Receipt.r0Preserved){
 # 独立报告已按用户要求追加R1，报告变化单独审计；其余R0首次失败清单必须原字节保留。
 if($task7Item.path.EndsWith('2026-10-02-task7-independent-acceptance.md')){continue}
 if((Get-FileHash -LiteralPath $task7Item.path).Hash -ne $task7Item.sha256){throw "R0证据被覆盖:$($task7Item.path)"}
}
$task7Manifest=Get-Content "$task7R1/source-freeze.json" -Raw | ConvertFrom-Json
$task7SourceChecks=@()
foreach($task7File in $task7Manifest.files){
 $task7Repo=if($task7File.repo -eq 'phone'){$task7Root}else{'D:/project/-Demo-master'}
 $task7Hash=(Get-FileHash -LiteralPath (Join-Path $task7Repo $task7File.path)).Hash
 if($task7Hash -ne $task7File.sha256){throw "893源字节变化:$($task7File.path)"}
 $task7SourceChecks+=@{repo=$task7File.repo;path=$task7File.path;sha256=$task7Hash;unchanged=$true}
}
$task7R0Hash=(Get-FileHash "$task7Development/source-freeze.json").Hash
$task7R1Hash=(Get-FileHash "$task7R1/source-freeze.json").Hash
if($task7R0Hash -ne $task7R1Hash -or $task7R1Hash -ne $task7Receipt.r1SourceManifestSha256){throw 'R0/R1源清单不是同字节'}
$task7PhoneHead=(& git -C $task7Root rev-parse HEAD).Trim();$task7AgentHead=(& git -C D:/project/-Demo-master rev-parse HEAD).Trim()
if($task7PhoneHead -ne $task7Manifest.phoneBase -or $task7AgentHead -ne $task7Manifest.agentBase -or (& git -C $task7Root diff --cached --name-only) -or (& git -C D:/project/-Demo-master diff --cached --name-only)){throw 'HEAD/索引保护不符'}
@{at=(Get-Date -Format o);result='PASS';addedB1Resources=13;necessaryClosure=$task7Closure.Count;checkedXml=$task7XmlCount;checkedResources=$task7Seen.Count;missingReferences=0;tokens=$task7TokenClosure;resources=$task7ResourceChecks;metadata=$task7MetadataChecks;sourceChecks=$task7SourceChecks;sourceCount=$task7SourceChecks.Count;sourceChanges=0;manifestSha256=$task7R1Hash;phoneHead=$task7PhoneHead;agentHead=$task7AgentHead;indexesEmpty=$true} | ConvertTo-Json -Depth 7 | Set-Content "$task7Public/closure-source-check.json" -Encoding utf8
Write-Output "独立R1 B1 PASS: XML=$task7XmlCount resource=$($task7Seen.Count) missing=0 B1=13 tokens=2 necessary=25 sources=$($task7SourceChecks.Count) delta=0"

