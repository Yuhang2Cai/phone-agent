# Task7 首次失败与修复记录

- voucher-network-error首测用Maestro默认launchApp强停进程，原券页面丢失并从首页启动，错误断言未触发；此前仅按Home保留后台但脚本再次强停。参考既有Task6 stopApp:false，改为只恢复后台Activity；原结果保留，重新定位原券后复验。本次没有修改产品或修改设备JWT。

- query-selector-fixed完成全部订单/券/资格断言后最后返回断言失败：测试在券→订单→列表两层之后多执行一次back，退出列表。原失败截图保留；修正YAML只返回两层，query-final完整复验，不修改产品导航。

- Maestro query-first：列表/全部分段断言通过后，详情UUID断言裸匹配失败；实际TextView为“订单号 UUID”，失败截图确认产品已显示正确编号和142.46实付。改为包含匹配，并按XML修正查看券按钮ID为group_order_show_vouchers；未改产品来迎合错误选择器。首测result.json与原始私有日志保留，query-selector-fixed复验中。

- 业务红测：后端页面缺 paidTotalCents，4项中1 failure；backend-red.xml。Agent券查询缺 queryTarget，1 failure/9 deselected；后续10项通过，agent-reads.xml。
- 后端修复首复测：5类10项中1 failure，新测试将两份订单实付误写为单券12800分；真实种子整单25600分/数量2，修正新测试后7类24项通过。backend-attempt2.xml保留错误预期证据。
- Android新验证器首次编译失败：ApiClient构造漏tokenProvider，未运行任何测试，不能算测试失败或PASS。修正后3项中1 failure，确实复现缺canRefund被默认false；产品改为拒绝非法资格后任务适用复测通过。
- API setup首次失败：验证器使用不存在的order_reviews表，实际实体为store_reviews；在任何账号/套餐/业务写入之前停止。原工具失败堆栈及采集脚本版本保留，不使用Task6旧凭据或改变原事实。

后续首次失败追加于本文件；每个复验使用新标签，不覆盖旧失败目录。
