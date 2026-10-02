# R2只读接口交付闭合断言：比对券仓库真实请求与拟提交后端实际路由，不借工作区Task8实现掩盖遗漏。
$ErrorActionPreference='Stop'
$task7Root='C:/fdz-build';$task7Snapshot="$task7Root/.runtime/task7-delivery-r2/phone"
$task7Public="$task7Root/docs/verification/task7-independent-evidence/r2"
$task7Controller='backend/src/main/java/com/fandazi/feature/refund/RefundController.java'
$task7Service='backend/src/main/java/com/fandazi/feature/refund/RefundService.java'
$task7Repository='backend/src/main/java/com/fandazi/feature/refund/RefundRequestRepository.java'
$task7Client=Get-Content "$task7Snapshot/android-app/app/src/main/java/com/fandazi/app/feature/voucher/VoucherRepository.kt" -Raw
$task7Paths=Get-Content "$task7Root/docs/verification/task7-r2-evidence/phone-proposed-paths.txt"
$task7Evidence=@()
foreach($task7Item in @(@{path=$task7Controller;required='@GetMapping("/by-voucher/{voucherId}")';line=42},@{path=$task7Service;required='detailByVoucher';line=73},@{path=$task7Repository;required='findByUserIdAndVoucherPublicId';line=14})){
 $task7Current=Get-Content (Join-Path $task7Root $task7Item.path) -Raw
 $task7Projected=Get-Content (Join-Path $task7Snapshot $task7Item.path) -Raw
 $task7Evidence+=@{path=$task7Item.path;workspaceLine=$task7Item.line;symbol=$task7Item.required;workspacePresent=$task7Current.Contains($task7Item.required);snapshotPresent=$task7Projected.Contains($task7Item.required);inProposedDelta=($task7Paths -contains $task7Item.path);workspaceSha256=(Get-FileHash (Join-Path $task7Root $task7Item.path)).Hash;snapshotSha256=(Get-FileHash (Join-Path $task7Snapshot $task7Item.path)).Hash}
}
$task7RouteHits=@(Get-ChildItem "$task7Snapshot/backend/src/main/java" -Recurse -File -Filter '*.java' | Select-String '/by-voucher/')
$task7Expected=$task7Client.Contains('api.get("refunds/by-voucher/${voucherId.urlEncoded()}")')
$task7Passed=$task7Expected -and $task7RouteHits.Count -gt 0 -and !(@($task7Evidence | Where-Object {!$_.snapshotPresent}).Count)
@{at=(Get-Date -Format o);result=if($task7Passed){'PASS'}else{'FAIL'};blockingFinding='B2';requestMethod='GET';requestPath='/api/refunds/by-voucher/{voucherId}';clientRequiresRoute=$task7Expected;clientLine=46;consumer='VoucherDetailFragment.kt:80/102/113';snapshotBackendRouteMatches=$task7RouteHits.Count;dependencies=$task7Evidence;liveHttpAttempted=$false;businessWrites=0;inference='拟提交后端不存在该路由；不能把工作区Task8新增GET的200/业务404当作拟提交源支持已有申请恢复'} | ConvertTo-Json -Depth 5 | Set-Content "$task7Public/read-dependency-blocker.json" -Encoding utf8
Write-Output "独立R2接口闭合断言：需要GET=$task7Expected，拟提交路由=$($task7RouteHits.Count)，结果=$task7Passed"
if(!$task7Passed){exit 1}
