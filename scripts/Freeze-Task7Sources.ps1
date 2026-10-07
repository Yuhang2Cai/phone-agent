# Task7 冻结台账：对照原工作区字节，不触碰索引；区分本任务增量、必要原dirty和延期共享引用。
$ErrorActionPreference='Stop'
$root='C:/fdz-build'
$public="$root/docs/verification/task7-evidence"
if(Test-Path "$public/task7-write-set.txt") {throw '冻结写集已存在，禁止覆盖'}
& "$public/Capture-Task7Source.ps1" -Phase freeze
$before=Get-Content "$public/source-start.json" -Raw | ConvertFrom-Json
$after=Get-Content "$public/source-freeze.json" -Raw | ConvertFrom-Json
$start=@{}
foreach($file in $before.files) {$start["$($file.repo):$($file.path)"]=$file}
# 原开始文件消失必须明确停止，不能因为当前枚举缺它就把删除漏出冻结差异包。
$currentPaths=@{}
foreach($file in $after.files) {$currentPaths["$($file.repo):$($file.path)"]=$true}
foreach($key in $start.Keys) {if(!$currentPaths.ContainsKey($key)) {throw "原开始源文件消失：$key"}}
$delta=@()
$patches=@{phone=[Collections.Generic.List[string]]::new();agent=[Collections.Generic.List[string]]::new()}
foreach($file in $after.files) {
 $key="$($file.repo):$($file.path)"
 $previous=$start[$key]
 if(!$previous -or $previous.sha256 -ne $file.sha256) {
    $delta+=@{repo=$file.repo;path=$file.path;beforeSha256=$previous.sha256;afterSha256=$file.sha256;beforeBytes=$previous.bytes;afterBytes=$file.bytes;protected=$file.protected}
    # 保护文档可能由控制端更新，只留摘要，不混入实现者产品patch；新源用NUL表示开始不存在。
    if(!$file.protected) {
       $repoRoot=if($file.repo -eq 'phone') {$root} else {'D:/project/-Demo-master'}
       $old=if($previous) {"$root/.runtime/task7/source-start/$($file.repo)/$($file.path)"} else {'NUL'}
       $current="$repoRoot/$($file.path)"
       $lines=@(& git -c core.quotePath=false diff --no-index --binary --no-ext-diff -- $old $current 2>$null)
       if($LASTEXITCODE -gt 1) {throw '开始字节diff生成失败'}
       $patches[$file.repo].Add(($lines -join "`n"))
    }
 }
}
# 精确原路径patch用于字节审计/重放原工作区，不是直接套到干净BASE的范围提交patch。
foreach($repo in @('phone','agent')) {$patches[$repo] -join "`n" | Set-Content "$public/$repo-source-start.delta.patch" -Encoding utf8}
$delta | ConvertTo-Json -Depth 6 | Set-Content "$public/source-start-delta.json" -Encoding utf8

# Task5 F与Task6成功页原dirty依赖：本任务已经实际验证这些源，不能仅交本轮新改行而丢依赖。
$needed=@(
 'android-app/app/src/main/java/com/fandazi/app/feature/voucher/GroupOrderDetailFragment.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherRepository.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherModels.kt',
 'android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherDetailFragment.kt',
 'android-app/app/src/main/res/layout/fragment_group_order_detail.xml',
 'android-app/app/src/main/res/layout/fragment_voucher_detail.xml',
 'android-app/app/src/main/res/layout/item_voucher_row.xml',
 'android-app/app/src/main/res/layout/item_group_order_card.xml',
 'android-app/app/src/main/res/drawable/after_sale_outline.xml',
 'android-app/app/src/main/res/values/colors.xml',
 'android-app/app/src/main/java/com/fandazi/app/FandaziApplication.kt',
 'android-app/app/src/main/res/navigation/nav_graph.xml')
$closure=@()
foreach($path in $needed) {
 $previous=$start["phone:$path"]
 $current=$after.files | Where-Object {$_.repo -eq 'phone' -and $_.path -eq $path} | Select-Object -First 1
 $closure+=@{path=$path;originalDirtyNecessary=$true;beforeSha256=$previous.sha256;frozenSha256=$current.sha256;ownDelta=($previous.sha256 -ne $current.sha256);mixed=($path -match '(FandaziApplication|nav_graph|VoucherDetailFragment|VoucherRepository|VoucherModels|GroupOrderDetailFragment)')}
}
$closure | ConvertTo-Json -Depth 5 | Set-Content "$public/source-closure.json" -Encoding utf8
# 延期源码只列指纹及归属，不将其计成Task7写闭环通过；供Task8接续闭合当前混合构建引用。
$deferred=@()
foreach($file in $after.files) {
 if($file.repo -eq 'phone' -and $file.path -match 'android-app/app/src/main/(java/.*/feature/(refund/|review/|support/|voucher/(VoucherQrFragment|RedemptionResultFragment))|res/(layout/fragment_(refund|review|help_faq|complaint|voucher_qr|redemption)|drawable/after_sale_|values/after_sale_))') {
    $old=$start["phone:$($file.path)"]
    $deferred+=@{path=$file.path;beforeSha256=$old.sha256;frozenSha256=$file.sha256;unchangedSinceStart=($old.sha256 -eq $file.sha256);scope='Task8或延期QR，只读指纹，不纳入Task7写功能验收'}
 }
}
$deferred | ConvertTo-Json -Depth 5 | Set-Content "$public/deferred-source-closure.json" -Encoding utf8
$text=[Collections.Generic.List[string]]::new()
$text.Add('Task7 范围写集；开发冻结，待独立验收，禁止以当前混合工作区冒称干净HEAD完整交付。')
$text.Add("phone BASE $($before.phoneBase) / 当前HEAD $($after.phoneBase)")
$text.Add("agent BASE $($before.agentBase) / 当前HEAD $($after.agentBase)")
$text.Add('source-start-delta.json及两份*.delta.patch：精确开始字节差异；原工作区备份只在忽略.runtime/task7/source-start，未stage/commit。')
$text.Add('必要原dirty：下列source-closure.json项目由Task7实际API/最新APK/Maestro验证，整文件不能盲目git add；未修改的item_voucher_row仍是必要源。')
foreach($item in $closure) {$text.Add("NECESSARY $($item.path) before=$($item.beforeSha256) freeze=$($item.frozenSha256) ownDelta=$($item.ownDelta) mixed=$($item.mixed)")}
$text.Add('混合接线：FandaziApplication只投影voucher的import/property/init；afterSale的import/property/init原样留Task8。nav_graph只投影订单详情/券详情两个fragment及参数；QR、核销结果、退款/进度/评价/帮助/投诉路由不纳入Task7。')
$text.Add('混合源引用：GroupOrderDetailFragment的原评价/退款目标，VoucherDetailFragment原afterSale进度及无调用requestToken，VoucherRepository/Models原displayToken/redeem能力均留存兼容；Task7只有canRefund资格导航，未执行售后提交或任何QR请求。')
$text.Add('Task8收尾必须继续闭合其AfterSaleRepository、refund/review/support页面、资源与导航；这些原dirty引用仍影响当前工作区构建，不能因本APK成功就声称Task7投影/BASE单独构建已经验证。')
$text.Add('deferred-source-closure.json为Task8/延期QR原源逐项开始与冻结指纹；只证明未改与当前构建依赖，不宣称售后/核销写入亲跑通过。共享outline同时在必要显示依赖中列出，不能据目录名整体纳入或整体丢掉。')
$text.Add('复用HEAD已入库AfterSaleImageLoader/BusinessDisplay/ApiClient/购买与Task5身份合并；未作无关重构或重新实现。')
$text.Add('共享视觉依赖：原dirty after_sale_outline.xml及其after_sale_ink颜色供UI12只读退款导航按钮使用，已实测；colors.xml只需该单色token，其他Task8颜色不因此归Task7。原HEAD的团购背景/卡片和四图标复用。')
$text.Add('增量清单（protected=true为控制端保护文档变化，只审计、不纳入产品）：')
foreach($item in $delta) {$text.Add("DELTA $($item.repo):$($item.path) before=$($item.beforeSha256) freeze=$($item.afterSha256) bytes=$($item.beforeBytes)->$($item.afterBytes) protected=$($item.protected)")}
$text.Add('文档/证据：两份Task7报告、task7-evidence及Agent EXP-018仅文档范围；EXP-014/017等原历史dirty不属于本轮新增实验，不全文件纳入提交。')
$text | Set-Content "$public/task7-write-set.txt" -Encoding utf8
@{frozenAt=(Get-Date -Format o);deltaCount=$delta.Count;sourceCount=$after.files.Count;phoneHead=$after.phoneBase;agentHead=$after.agentBase;sourceManifestSha256=(Get-FileHash "$public/source-freeze.json").Hash;startManifestSha256=(Get-FileHash "$public/source-start.json").Hash;apkSha256=(Get-FileHash "$root/android-app/app/build/outputs/apk/debug/app-debug.apk").Hash} | ConvertTo-Json | Set-Content "$public/freeze-receipt.json" -Encoding utf8
Write-Output "Task7 frozen source=$($after.files.Count) delta=$($delta.Count); no index/commit operations"
