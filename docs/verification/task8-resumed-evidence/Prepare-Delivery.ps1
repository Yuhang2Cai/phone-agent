<#
Task8 精确源码交付：仅暂存已核对的售后与必要共享依赖，并从索引导出隔离快照。
原工作区保持不变；导航只投影售后节点，不纳入延期核销页面。已有未知暂存项会阻止执行。
#>
param([string]$Label='r0')
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$agent='D:/project/-Demo-master'
$out="$root/.runtime/task8-delivery-$Label"
if(Test-Path -LiteralPath $out){throw '不覆盖既有交付快照'}

# 下列每项均服务于已实现业务；不根据整个 dirty 列表自动认领旧外卖和未来账号设置功能。
$phone=@(
 'android-app/app/src/main/java/com/fandazi/app/FandaziApplication.kt',
 'android-app/app/src/main/java/com/fandazi/app/core/image/AfterSaleImageLoader.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentCardRenderer.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentChatFragment.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentModels.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentPublicCards.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentRepository.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/agent/AgentViewModel.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/refund/RefundRequestFragment.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/refund/RefundProgressFragment.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/review/ReviewFragment.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/support/AfterSaleRepository.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/support/HelpFaqFragment.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/support/ComplaintFragment.kt',
 'android-app/app/src/main/res/values/colors.xml',
 'android-app/app/src/test/java/com/fandazi/app/core/image/AfterSaleImageLoaderTest.kt',
 'android-app/app/src/test/java/com/fandazi/app/feature/agent/AgentAfterSaleCardTest.kt',
 'android-app/app/src/test/java/com/fandazi/app/feature/agent/Task8AfterSaleActionTest.kt',
 'android-app/app/src/test/java/com/fandazi/app/feature/agent/HttpAgentRepositoryTest.kt',
 'backend/src/main/java/com/fandazi/development/DevelopmentOrderFailure.java',
 'backend/src/main/java/com/fandazi/feature/agent/AgentV1ToolBridge.java',
 'backend/src/main/java/com/fandazi/feature/coupon/CouponService.java',
 'backend/src/main/java/com/fandazi/feature/support/ComplaintController.java',
 'backend/src/main/java/com/fandazi/feature/support/ComplaintService.java',
 'backend/src/main/java/com/fandazi/feature/support/ComplaintTicketEntity.java',
 'backend/src/main/java/com/fandazi/feature/support/FaqController.java',
 'backend/src/main/java/com/fandazi/feature/support/FaqService.java',
 'backend/src/main/resources/application-development.yml',
 'backend/src/test/java/com/fandazi/development/DevelopmentOrderFailureTest.java',
 'backend/src/test/java/com/fandazi/feature/coupon/CouponAvailabilityTest.java',
 'backend/src/test/java/com/fandazi/feature/support/FaqServiceTest.java'
)
$service=@('service/engine.py','service/dag_runtime.py','tests/test_agent_service_reads.py',
 'tests/test_after_sale_workflow.py','tests/test_task8_support_live.py')

# 从上述页面的实际 R 引用递归解析布局/绘制/颜色资源；只认这些入口可达的文件，不整目录暂存。
$resources="$root/android-app/app/src/main/res"
$queue=[Collections.Generic.Queue[string]]::new()
$phone | Where-Object {$_ -like '*src/main/java*'} | ForEach-Object {
 foreach($m in [regex]::Matches([IO.File]::ReadAllText("$root/$_"),'R\.(layout|drawable|color)\.([A-Za-z0-9_]+)')){
  $queue.Enqueue("$($m.Groups[1].Value)/$($m.Groups[2].Value)")
 }
}
$seen=@{}
$edges=@()
while($queue.Count){
 $ref=$queue.Dequeue()
 if($seen.ContainsKey($ref)){continue}
 $seen[$ref]=$true
 $parts=$ref.Split('/')
 $files=@(Get-ChildItem "$resources/$($parts[0])" -File -ErrorAction SilentlyContinue | Where-Object BaseName -eq $parts[1])
 foreach($file in $files){
  $path="android-app/app/src/main/res/$($parts[0])/$($file.Name)"
  $edges+=@{reference=$ref;path=$path}
  # HEAD 已有且当前未改的依赖自然留在快照；未跟踪依赖必须显式闭合。
  $tracked=& git -C $root ls-tree --name-only HEAD -- $path
  if(!$tracked){$phone+=$path}
  if($file.Extension -eq '.xml'){
   [xml]$xml=[IO.File]::ReadAllText($file.FullName)
   foreach($attr in $xml.SelectNodes('//@*')){
    if($attr.Value -match '^@(layout|drawable|color)/([A-Za-z0-9_]+)$'){$queue.Enqueue("$($Matches[1])/$($Matches[2])")}
   }
  }
 }
}
$nav='android-app/app/src/main/res/navigation/nav_graph.xml'
$profile='android-app/app/src/main/java/com/fandazi/app/feature/profile/ProfileFragment.kt'
$profileLayout='android-app/app/src/main/res/layout/fragment_profile.xml'
$projected=@($nav,$profile,$profileLayout)
$phone=@($phone|Sort-Object -Unique)
foreach($repo in @(@{root=$root;files=@($phone)+$projected},@{root=$agent;files=$service})){
 $unknown=@(& git -C $repo.root diff --cached --name-only | Where-Object {$_ -notin $repo.files})
 if($unknown.Count){throw "存在其他任务暂存项，停止：$($unknown -join ',')"}
 & git -C $repo.root add -- @($repo.files|Where-Object {$_ -notin $projected})
 if($LASTEXITCODE){throw '精确暂存失败'}
}

# XML 解析识别延期节点，保留空白格式；仅生成索引投影，不覆盖原导航文件。
[IO.Directory]::CreateDirectory($out)|Out-Null
$xml=[xml]::new()
$xml.PreserveWhitespace=$true
$xml.Load("$root/$nav")
foreach($name in @('voucherQrFragment','redemptionResultFragment')){
 $node=@($xml.navigation.fragment)|Where-Object {$_.GetAttribute('id','http://schemas.android.com/apk/res/android') -eq "@+id/$name"}
 if($node){
  # 同时移除被排除节点之前的纯空白，避免索引留下仅有缩进的空行。
  $previous=$node.PreviousSibling
  if($previous -is [Xml.XmlWhitespace]){$node.ParentNode.RemoveChild($previous)|Out-Null}
  $node.ParentNode.RemoveChild($node)|Out-Null
 }
}
$projection="$out/nav_graph.xml"
[IO.File]::WriteAllText($projection,$xml.OuterXml,[Text.UTF8Encoding]::new($false))
$blob=(& git -C $root hash-object -w --path=$nav $projection).Trim()
& git -C $root update-index --cacheinfo "100644,$blob,$nav"
if($LASTEXITCODE){throw '导航索引投影失败'}

# 帮助入口是本任务页面的必要可达路径；账号展示/登录设置属于后续任务，只投影入口绑定与单个视图。
$profileHead=(& git -C $root show "HEAD:$profile") -join "`n"
$anchor='        view.findViewById<View>(R.id.profile_logout).setOnClickListener {'
if(!$profileHead.Contains($anchor)){throw '个人页入口投影锚点变化，须重新审核'}
$addition=@'
        // Task8 只接通公开帮助页；不提前修改账号设置或启用延期业务。
        view.findViewById<View>(R.id.profile_help_entry).setOnClickListener {
            it.findNavController().navigate(R.id.helpFaqFragment)
        }
'@
$profileProjection="$out/ProfileFragment.kt"
[IO.File]::WriteAllText($profileProjection,$profileHead.Replace($anchor,$addition+"`n"+$anchor)+"`n",[Text.UTF8Encoding]::new($false))
$profileBlob=(& git -C $root hash-object -w --path=$profile $profileProjection).Trim()
& git -C $root update-index --cacheinfo "100644,$profileBlob,$profile"
$headLayout=[xml]::new()
$headLayout.PreserveWhitespace=$true
$headLayout.LoadXml((& git -C $root show "HEAD:$profileLayout") -join "`n")
[xml]$currentLayout=[IO.File]::ReadAllText("$root/$profileLayout")
$helpNode=$currentLayout.SelectNodes('//TextView')|Where-Object {$_.GetAttribute('id','http://schemas.android.com/apk/res/android') -eq '@+id/profile_help_entry'}
if(@($helpNode).Count -ne 1){throw '帮助入口视图不唯一'}
$container=$headLayout.SelectSingleNode('/ScrollView/LinearLayout')
$container.AppendChild($headLayout.ImportNode($helpNode,$true))|Out-Null
$layoutProjection="$out/fragment_profile.xml"
# Android 数据绑定解析器不接受该路径中的 UTF-8 BOM，统一写无 BOM 文本，与仓库原资源一致。
[IO.File]::WriteAllText($layoutProjection,$headLayout.OuterXml,[Text.UTF8Encoding]::new($false))
$layoutBlob=(& git -C $root hash-object -w --path=$profileLayout $layoutProjection).Trim()
& git -C $root update-index --cacheinfo "100644,$layoutBlob,$profileLayout"
if($LASTEXITCODE){throw '帮助入口索引投影失败'}

# 索引快照不包含工作区其他文件、凭据或构建缓存；两套日志/数据库也不会随复制混入。
$receipt=@()
foreach($repo in @(@{name='phone';root=$root;folder='phone'},@{name='agent';root=$agent;folder='-Demo-master'})){
 $tree=(& git -C $repo.root write-tree).Trim()
 $folder="$out/$($repo.folder)"
 [IO.Directory]::CreateDirectory($folder)|Out-Null
 # Windows tar 对历史中文文件名解码失败，改用 Git UTF-8 ZIP 与 .NET 解包，不跳过任何源码。
 & git -C $repo.root archive --format=zip --output="$out/$($repo.name).zip" $tree
 if($LASTEXITCODE){throw '索引快照归档失败'}
 Expand-Archive -LiteralPath "$out/$($repo.name).zip" -DestinationPath $folder
 $paths=@(& git -C $repo.root diff --cached --name-only)
 $receipt+=@{repo=$repo.name;head=(& git -C $repo.root rev-parse HEAD).Trim();tree=$tree;paths=$paths;snapshot=$folder}
}
$receipt|ConvertTo-Json -Depth 7|Set-Content "$root/docs/verification/task8-resumed-evidence/delivery-$Label.json" -Encoding utf8
$edges|ConvertTo-Json -Depth 4|Set-Content "$root/docs/verification/task8-resumed-evidence/resource-edges-$Label.json" -Encoding utf8
Write-Output "精确索引快照已生成：$out；待实际构建与独立验收，尚未提交。"
