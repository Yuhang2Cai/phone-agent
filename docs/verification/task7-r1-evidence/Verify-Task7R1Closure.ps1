# Task7 R1 元数据修复核对：补B1只读状态资源归属，递归核对实际引用与893项R0冻结源；不改产品、不触发设备或业务测试。
$ErrorActionPreference = 'Stop'
$root = 'C:/fdz-build'
$agent = 'D:/project/-Demo-master'
$r0 = "$root/docs/verification/task7-evidence"
$independent = "$root/docs/verification/task7-independent-evidence"
$out = "$root/docs/verification/task7-r1-evidence"
$startedAt = Get-Date -Format o

# 固定R0审查输入指纹，修复只输出到独立R1目录，禁止覆盖首次失败报告或原清单。
$preservedPaths = @(
 "$r0/source-freeze.json", "$r0/source-start.json", "$r0/source-start-delta.json",
 "$r0/source-closure.json", "$r0/deferred-source-closure.json", "$r0/task7-write-set.txt",
 "$independent/resource-closure-audit.json", "$root/docs/verification/2026-10-02-task7-independent-acceptance.md"
)
$preserved = @($preservedPaths | ForEach-Object { [ordered]@{path=$_;sha256=(Get-FileHash -LiteralPath $_).Hash} })
$freeze = Get-Content "$r0/source-freeze.json" -Raw | ConvertFrom-Json
$start = Get-Content "$r0/source-start.json" -Raw | ConvertFrom-Json
$baselineAudit = Get-Content "$independent/resource-closure-audit.json" -Raw | ConvertFrom-Json
$closure = @(Get-Content "$r0/source-closure.json" -Raw | ConvertFrom-Json)
$delta = @(Get-Content "$r0/source-start-delta.json" -Raw | ConvertFrom-Json)

# 输入校验：B1精确限定三状态布局和十绘制资源，任何清单偏离均中止，不能把售后/QR写功能顺带纳入。
$expected = @(
 'layout/view_orders_empty.xml', 'layout/view_error_retry.xml', 'layout/view_network_error.xml',
 'drawable/after_sale_order_empty.png', 'drawable/after_sale_empty_shadow.png', 'drawable/after_sale_primary.xml',
 'drawable/after_sale_error_shadow.png', 'drawable/after_sale_error_page.xml', 'drawable/after_sale_error_fold.xml',
 'drawable/after_sale_error_eye.png', 'drawable/after_sale_error_badge.png',
 'drawable/after_sale_network_backdrop.png', 'drawable/after_sale_network_off.png'
) | ForEach-Object { "android-app/app/src/main/res/$_" }
if($baselineAudit.missingNecessaryOriginalDirty.Count -ne 13 -or
 @(Compare-Object ($expected | Sort-Object) ($baselineAudit.missingNecessaryOriginalDirty.path | Sort-Object)).Count) {
 throw 'B1输入不是约定的13项资源'
}
$phoneFreeze = @{}; $phoneStart = @{}
$freeze.files | Where-Object repo -eq 'phone' | ForEach-Object { $phoneFreeze[$_.path] = $_ }
$start.files | Where-Object repo -eq 'phone' | ForEach-Object { $phoneStart[$_.path] = $_ }

# 归属规范化：新增必要项的开始/R0冻结/当前字节必须相同；同名售后资源仅复用显示，不授权任何售后写动作。
foreach($path in $expected) {
 $current = (Get-FileHash -LiteralPath (Join-Path $root $path)).Hash
 if(!$phoneStart.ContainsKey($path) -or !$phoneFreeze.ContainsKey($path) -or
 $current -ne $phoneFreeze[$path].sha256 -or $current -ne $phoneStart[$path].sha256) { throw "B1资源字节漂移：$path" }
 $closure += [pscustomobject][ordered]@{
  path=$path;beforeSha256=$phoneStart[$path].sha256;frozenSha256=$phoneFreeze[$path].sha256
  currentSha256=$current;ownDelta=$false;mixed=$false;originalDirtyNecessary=$true
  necessaryUse='Task7本人订单列表空态、错误重试态、断网态的只读显示；不是Task8售后提交或QR核销归属'
 }
}

# 颜色投影按token限定，而不是把共享colors.xml整文件归Task7；HEAD已有团购颜色仍复用HEAD。
$colorPath = 'android-app/app/src/main/res/values/colors.xml'
[xml]$colors = Get-Content (Join-Path $root $colorPath) -Raw
[xml]$headColors = (& git -C $root show 'HEAD:android-app/app/src/main/res/values/colors.xml') -join "`n"
$tokens = @('after_sale_ink', 'after_sale_muted')
$tokenClosure = @()
foreach($name in $tokens) {
 $node = $colors.SelectSingleNode("/resources/color[@name='$name']")
 if(!$node -or $headColors.SelectSingleNode("/resources/color[@name='$name']")) { throw "颜色投影归属异常：$name" }
 $tokenClosure += [ordered]@{
  path=$colorPath;token=$name;value=$node.InnerText
  beforeSha256=$phoneStart[$colorPath].sha256;frozenSha256=$phoneFreeze[$colorPath].sha256
  necessaryUse='Task7订单列表及三只读状态页的正文/次级说明，ink另供券页描边；不包含其他Task8颜色'
 }
}
$colorEntry = $closure | Where-Object path -eq $colorPath
$colorEntry.mixed = $true
$colorEntry | Add-Member -NotePropertyName necessaryTokens -NotePropertyValue $tokens
$colorEntry | Add-Member -NotePropertyName necessaryUse -NotePropertyValue '仅两个只读显示token；其他售后颜色及售后功能仍归Task8'
$closure | ConvertTo-Json -Depth 7 | Set-Content "$out/source-closure.json" -Encoding utf8
$tokenClosure | ConvertTo-Json -Depth 5 | Set-Content "$out/color-token-closure.json" -Encoding utf8

# R1完整写集继承原48项开始delta，只更正归属说明并增加13项；原R0写集保留其失败现场。
$writeSet = Get-Content "$r0/task7-write-set.txt" -Raw
$writeSet = $writeSet.Replace('Task7 范围写集；开发冻结，待独立验收，禁止以当前混合工作区冒称干净HEAD完整交付。',
 'Task7 R1 范围写集；B1只读资源归属修复，待独立R1验收。产品相对R0零差异，不stage/commit；不冒称干净BASE投影构建已验证。')
$writeSet = $writeSet.Replace('source-closure.json项目', 'task7-r1-evidence/source-closure.json的25项项目')
$writeSet = $writeSet.Replace('colors.xml只需该单色token，其他Task8颜色不因此归Task7。',
 'colors.xml精确需要after_sale_ink和after_sale_muted两个token；列表和三状态页均引用muted，其他Task8颜色不因此归Task7。')
$writeSet = $writeSet.Replace("freeze=$($phoneFreeze[$colorPath].sha256) ownDelta=False mixed=False", "freeze=$($phoneFreeze[$colorPath].sha256) ownDelta=False mixed=True")
$extra = @(
 'R1 B1必要资源补充：以下13项均为原dirty、开始/R0冻结/当前SHA一致，属于Task7只读状态显示，不是售后提交。',
 'R0延期清单中与下列重名的视觉源同时是Task7必要只读显示依赖；延期的是售后写功能，不是这些资源的显示用途。'
)
foreach($path in $expected) {
 $extra += "NECESSARY $path before=$($phoneStart[$path].sha256) freeze=$($phoneFreeze[$path].sha256) ownDelta=False mixed=False"
}
foreach($token in $tokenClosure) { $extra += "NECESSARY_TOKEN $colorPath/$($token.token) value=$($token.value)；只读状态显示，其他Task8颜色排除。" }
$extra += '原未闭合afterSale接线/退款进度/评价/支持/QR核销继续披露并留Task8；独立R1接续原订单/券自然401，本轮不运行。'
($writeSet.TrimEnd() + "`r`n`r`n" + ($extra -join "`r`n") + "`r`n") | Set-Content "$out/task7-write-set.txt" -Encoding utf8

# 递归规则判断：XML解析器读取真实属性，布局/drawable继续向下遍历；位图记录为叶节点，不把注释样例当引用。
$head = @{}; & git -C $root ls-tree -r --name-only HEAD | ForEach-Object { $head[$_]=$true }
$declared = @{}; $closure | ForEach-Object { $declared[$_.path]='necessary' }
$delta | Where-Object repo -eq 'phone' | ForEach-Object { $declared[$_.path]='delta' }
$queue = [Collections.Generic.Queue[string]]::new()
@('fragment_orders', 'fragment_group_order_detail', 'fragment_voucher_detail', 'item_group_order_card', 'item_voucher_row') |
 ForEach-Object { $queue.Enqueue("android-app/app/src/main/res/layout/$_.xml") }
$seen = @{}; $edges = @(); $colorEdges = @(); $missing = @(); $xmlCount = 0
while($queue.Count) {
 $path = $queue.Dequeue()
 if($seen.ContainsKey($path)) { continue }
 $absolute = Join-Path $root $path
 if(!(Test-Path -LiteralPath $absolute)) { throw "引用源缺失：$path" }
 $seen[$path] = $true
 if([IO.Path]::GetExtension($path) -ne '.xml') { continue }
 $xmlCount++
 [xml]$xml = Get-Content -LiteralPath $absolute -Raw
 foreach($attribute in $xml.SelectNodes('//@*')) {
  if($attribute.Value -notmatch '^@(layout|drawable|color)/([A-Za-z0-9_]+)$') { continue }
  $type=$Matches[1]; $name=$Matches[2]
  if($type -eq 'color') {
   $definition = $colors.SelectSingleNode("/resources/color[@name='$name']")
   $inHead = $null -ne $headColors.SelectSingleNode("/resources/color[@name='$name']")
   $covered = $inHead -or ($tokens -contains $name)
   $colorEdges += [ordered]@{from=$path;token=$name;inHead=$inHead;necessaryToken=($tokens -contains $name);covered=$covered}
   if(!$definition -or !$covered) { $missing += "color/$name" }
   continue
  }
  $candidates = @(Get-ChildItem "$root/android-app/app/src/main/res/$type" -File | Where-Object BaseName -eq $name)
  if($candidates.Count -ne 1) { throw "资源候选异常：$type/$name" }
  $dependency = "android-app/app/src/main/res/$type/$($candidates[0].Name)"
  $covered = $head.ContainsKey($dependency) -or $declared.ContainsKey($dependency)
  $edges += [ordered]@{from=$path;to=$dependency;inHead=$head.ContainsKey($dependency);declared=$declared[$dependency];covered=$covered}
  if(!$covered) { $missing += $dependency }
  $queue.Enqueue($dependency)
 }
}
if(@($expected | Where-Object { !$seen.ContainsKey($_) }).Count) { throw '补充清单存在无真实引用项' }
if($missing.Count) { throw "递归引用仍未闭合：$($missing -join ',')" }
[ordered]@{
 at=(Get-Date -Format o);result='PASS';checkedXmlResourceFiles=$xmlCount;checkedResourceFiles=$seen.Count
 edges=$edges;colorEdges=$colorEdges;necessaryAddedResources=13;necessaryOriginalDirtyTotal=$closure.Count
 missingNecessaryOriginalDirty=@();missingColors=@();necessaryTokens=$tokens
 scope='仅Task7只读显示资源闭合，不代表售后/QR闭合或独立验收通过'
} | ConvertTo-Json -Depth 7 | Set-Content "$out/resource-closure-audit.json" -Encoding utf8

# 冻结复核：逐个核对全部893源字节与两HEAD/索引，另存R1快照，不改R0manifest；任何产品变化都是阻塞。
$sourceChecks = @()
foreach($file in $freeze.files) {
 $repo = if($file.repo -eq 'phone') { $root } else { $agent }
 $absolute = Join-Path $repo $file.path
 $hash = if(Test-Path -LiteralPath $absolute) { (Get-FileHash -LiteralPath $absolute).Hash } else { $null }
 $sourceChecks += [ordered]@{repo=$file.repo;path=$file.path;r0Sha256=$file.sha256;r1Sha256=$hash;unchanged=($hash -eq $file.sha256)}
}
$sourceDifferences = @($sourceChecks | Where-Object { !$_.unchanged })
if($sourceDifferences.Count) { throw "R0源发生变化：$($sourceDifferences.Count)项" }
$phoneHead = (& git -C $root rev-parse HEAD).Trim(); $agentHead = (& git -C $agent rev-parse HEAD).Trim()
if($phoneHead -ne $freeze.phoneBase -or $agentHead -ne $freeze.agentBase) { throw 'HEAD与R0不一致' }
if((& git -C $root diff --cached --name-only) -or (& git -C $agent diff --cached --name-only)) { throw '索引非空，请核对控制端外部暂存' }
Copy-Item -LiteralPath "$r0/source-freeze.json" -Destination "$out/source-freeze.json"
$sourceChecks | ConvertTo-Json -Depth 5 | Set-Content "$out/source-r0-r1-comparison.json" -Encoding utf8
foreach($item in $preserved) { if((Get-FileHash -LiteralPath $item.path).Hash -ne $item.sha256) { throw "R0证据被覆盖：$($item.path)" } }

# 环境仅核对既有端口拥有者，继承独立验收释放凭证；本轮未接管设备、改网络/JWT或重启服务，不伪称新设备实测。
$releasePath = "$independent/environment-release.json"
$release = Get-Content $releasePath -Raw | ConvertFrom-Json
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 8080,8091 | Select-Object LocalPort,OwningProcess)
foreach($owner in $release.listeners) {
 if(!($listeners | Where-Object { $_.LocalPort -eq $owner.port -and $_.OwningProcess -eq $owner.pid })) { throw "既有服务端口归属变化：$($owner.port)" }
}
[ordered]@{
 at=(Get-Date -Format o);inheritedRelease=$releasePath;inheritedReleaseSha256=(Get-FileHash $releasePath).Hash
 releaseRecordedAt=$release.releasedAt;listeners=$listeners;ordinaryJwtSeconds=$release.ordinaryJwtSeconds
 networkFromIndependentRelease=@{wifi=$release.wifi;data=$release.data;airplane=$release.airplane;activeDefaultNetwork=$release.activeDefaultNetwork}
 appForceStoppedFromIndependentRelease=$release.appForceStopped;deviceOrNetworkOperations=0;serviceRestarts=0
 ownRunningTestSessions=0;scope='仅元数据递归核对命令，无Gradle/Maestro/服务执行会话遗留，原网络恢复凭证继承而非重新设备测量'
} | ConvertTo-Json -Depth 6 | Set-Content "$out/environment-release.json" -Encoding utf8

# 结果构造：R1 receipt固定元数据SHA、源差异0和R0保留指纹；失败R0及未验收401仍由独立R1接续。
$metadata = @('source-closure.json','color-token-closure.json','task7-write-set.txt','resource-closure-audit.json',
 'source-freeze.json','source-r0-r1-comparison.json','environment-release.json','Verify-Task7R1Closure.ps1') |
 ForEach-Object { [ordered]@{path="docs/verification/task7-r1-evidence/$_";sha256=(Get-FileHash "$out/$_").Hash} }
[ordered]@{
 startedAt=$startedAt;frozenAt=(Get-Date -Format o);stage='DONE_WITH_CONCERNS，B1开发元数据修复，待独立R1'
 phoneHead=$phoneHead;agentHead=$agentHead;sourceFiles=$freeze.files.Count;sourceDifferenceFromR0=0
 r0SourceManifestSha256=(Get-FileHash "$r0/source-freeze.json").Hash;r1SourceManifestSha256=(Get-FileHash "$out/source-freeze.json").Hash
 addedNecessaryResources=13;necessaryOriginalDirtyTotal=$closure.Count;necessaryColorTokens=$tokens
 recursiveXmlFiles=$xmlCount;recursiveResourceFiles=$seen.Count;uncoveredReferences=0;productEdits=0
 stageOrCommit=$false;task8Executed=$false;independentR0='FAIL保留';independentR1='待控制端派发，仅续验B1及剩余原订单/券401'
 r0Preserved=$preserved;metadata=$metadata
} | ConvertTo-Json -Depth 7 | Set-Content "$out/freeze-receipt.json" -Encoding utf8
Write-Output "Task7 R1 B1 PASS xml=$xmlCount resources=$($seen.Count) added=13 necessary=$($closure.Count) tokens=2 missing=0 sources=$($freeze.files.Count) sourceDelta=0; independent R1 pending"
