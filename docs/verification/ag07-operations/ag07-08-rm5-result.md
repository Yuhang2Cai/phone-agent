# AG07-08 RM-R5 实际前置失败及修复

2026-10-27。整轮失败、08仍未完成。唯一 `run_ui_remaining_r5.py --execute` 观察退出1；冻结Agent16b9095a/Phone371a6c70，最多2文本/0确认/18发送。实际**0文本、0确认、0模型发送**，未激活模型，退款/限流取消未运行。

## 现场与根因

原账号/指针/epoch核对通过，原会话Maestro恢复退出0；截图已亲看：待支付订单176元、查看订单、历史确认仅查看及禁用旧确认，未点击任何购买或支付。原execution key的HTTP回执已取得，state=committed，orderId与唯一订单一致。

随后`verify_post_order`整行比较失败。离线逐字段差异仅为7个`tools[*].result_json`：先前独立SQL读取保留JSON字符串，正式驱动读取解析为dict。解析后订单、授权、tasks、runs、tools五组完整等值；不是新业务副作用。之前的合成守卫测试没有覆盖两个真实读取器不同的JSON表示，这是验收驱动测试覆盖缺口。原失败保留，不反向改判。

修复仅在已建单守卫规范化明确的工具结果JSON列；保留原始输入，不忽略字段、状态、执行键或授权。非法JSON仍拒绝，解析后任一字段变化仍失败。新增3案例：原样等价、内层数量改变、损坏JSON，首次1指定失败/2通过；修复后17开发通过，精确Agent `e3c5103be81b9f9dacf81e876f6daf13d3c922f9` 的87组合隔离通过。直接用本轮原始before-facts、旧事后facts和真实原键回执离线重放守卫通过（7条工具结果），不预改记录、不联网，不把离线通过当真实复验。

## 资源与证据

12条SELECT/10Docker/30ADB；Agent40196和Spring8136均正常退出0、未强杀；设备40312退出0、反向转发删除，双源库回到stopped。持久化1/1/ON保持。无新订单、未支付，原订单及两旧撤销/一消费购买授权/一未消费支付授权完整不变，退款投诉0。

证据根 `.runtime/acceptance/ag07-operations/ag07-08/`：`ui-remaining-r5/result.json`、`private/ui-remaining-r5/{trial-result.json,before-facts.json,preflight-original-receipt.json,failure.txt,maestro-restore/result.json}`及实际截图、`rm6-json-red.xml`、`rm6-green.xml`、`rm6-isolated.xml`、`rm6-recorded-preflight.json`。本轮前置失败发生在原生hierarchy采集之前，此项未运行、不能因截图存在称完整结构验证通过。

`rm5-apk-cleanup.json`记录删除本轮重复APK1份238650327字节，C盘41314844672→41553498112；最新验证包SHA `7c72249b4acb6176e44f5887d6723c860a60879410e0b9c55bf0efdc22e52a77`保留，自有轮目录APK剩余0。无他人资源改动。

需求动态前置部分通过、驱动缺陷已离线修复、真实整轮失败、精确源码定向隔离通过/未改产品构建限定复用、性能债务延期。08/09未完成，双master未合。下一轮仍需新授权；不将未用18次自动结转。


## RM-R6准备验收与复用表

RM6-00离线通过；RM6-01—03未运行，独立新轮待批准。`rm6-offline-result.json`：31Python语法编译0、87隔离测试通过，4187文件冻结、12原冻结全文不变。新父入口approval=pending实际退出1/`new_round_not_authorized`，没有运行目录或资源启动；端口复查无本任务监听。全部测试只离线，不增加费用。

| 范围 | 选择 | 版本/证据及理由 |
|---|---|---|
| 工具JSON表示比较及共享续验守卫 | 新运行 | Agent e3c5103b，17开发/87隔离、指定红1失败，`rm6-isolated.xml`与实际落盘`rm6-recorded-preflight.json`通过；不反向修改RM5失败。 |
| Phone/Android/Spring、3个Maestro语法 | 限定复用 | Phone371a6c70与RM5完整相同，3流程及569产物此前已逐文件核验；本次仅2个Agent测试文件变化。无产品代码/布局/协议变更，不重建APK。 |
| 原页面恢复/唯一订单与原键回执 | RM5事实限定复用 | 当前已观察页面/回执有效；下一真实轮动态前置仍重新核，不保证未来状态，不补未运行的hierarchy。 |
| 新两文本/工具/上下文/业务结果/取消 | 待新运行 | 0文本已消费；原成功建单不能重复，仍需新轮实际前后组合。 |
| 08质量及09最终适用回归 | 未完成 | 任何证据不足或未测单列；不以当前87测试宣称AG07完成。 |

修复后的最小续验方案：[RM-R6](../../superpowers/plans/2026-10-27-ag07-ui-post-order-continuation-r6.md)。最多2文本/0确认/18发送，不自动结转，合master另问。
