# AG07-08 RM-R4 实际结果及失败归因

2026-10-27，同一主Agent按步骤验收。整轮结论：**失败，未完成08**；不能把建单成功改写成整轮通过。使用精确候选Agent `071c2c5e16d0e8126ee5790fa15b7d7f372e5e2d`、Phone `38d0b00bd21be80b4f33cfcdb626c77d99d093cc`。

## 实际动作和业务结果

唯一命令 `python -X utf8 .runtime/acceptance/ag07-operations/ag07-08/run_ui_remaining_r4.py --execute` 退出1。原暂停页与一次恢复控制通过，启用模型前0发送，旧确认禁用且订单未变；fresh文本“改成2份”完成，1模型客户端发送。购买确认只点击1次，工具 `create_group_buy_order` 返回成功；随后Maestro等待助手句子超时，首错停止。退款/限流取消未运行，没有点击模拟支付。

零模型事后读取 `read_rm4_stop_r1.py` 唯一一次退出0（9SELECT/8Docker）：权威账本确认仅新增1条订单，2份/176元/PENDING_PAYMENT、paid_at为空、原执行键唯一，旧订单未变，退款0/投诉0。两旧购买授权撤销，新购买授权消费一次，支付授权未消费。本轮历史模型累计33次客户端发送，不等于账单金额。

## 根因与边界

失败为验收驱动断言错误：`service/engine.py` 的建单分支有支付授权时直接返回订单卡及“确认模拟支付”，不输出脚本等待的两种建单成功句。实际失败截图已查看，展示待支付176元订单、查看订单及支付确认卡。工具回执与数据库状态佐证业务写成功，不能再次购买确认。不是已证实的产品建单失败。

修复仅将 `rv-confirm.yaml`/`operations-smoke.yaml` 的文字等待改为待支付订单卡及查看订单按钮；业务成功仍依赖账本，不凭UI断言替代。原冻结规格、旧失败轮和执行输入保持。新续验不再包含购买/确认，只补剩余两文本及原键只读。

| 步骤/证据 | 状态 | 适用范围与限制 |
|---|---|---|
| RM4-01/02 | 本轮新运行通过 | 同一原暂停task/run恢复，合法版本/控制事件变化，0模型和旧确认失效；不扩展其他任务恢复。 |
| RM4-03 fresh | 本轮新运行通过 | 当前数量纠正和新报价；完整fresh来源索引留存。 |
| RM4-03 confirm | 整段失败，业务建单事实成立 | 实际点击、工具成功与事后账本有效；确认后原生结构/来源索引未完成，不能宣称六来源完整。 |
| RM4-03 refund/saturation | 未运行 | 下一轮限定补验，未用额度不结转。 |
| RM4-04 原键HTTP回执 | 未运行 | 后续须读取原execution key，不发交易。 |
| 资源归还 | 本轮通过 | 双应用正常STOP0、双库stopped、设备退出0，31ADB；事后读取双库再次归还。 |
| 精确源码/既有安全链路 | 限定复用原报告 | 产品未改；新驱动守卫须另行隔离验证，不代替09最终适用回归。 |

## 证据与清理

本地证据根 `.runtime/acceptance/ag07-operations/ag07-08/`：`ui-remaining-r4/result.json`、`private/ui-remaining-r4/{trial-result.json,journey.jsonl,restore-proof.json,resume-proof.json,fresh-source-index.json,maestro-confirm/result.json}`、实际工具交换、原始Maestro日志及截图；事后 `private/rm4-stop-read-r1/{facts.json,result.json}`。私有账号/凭据不进入提交。

清理 `rm4-apk-cleanup.json` 记录1份重复临时APK、238650327字节，C盘可用41834131456→42072784896；保留已验证APK SHA256 `7c72249b4acb6176e44f5887d6723c860a60879410e0b9c55bf0efdc22e52a77`，无本轮残留临时APK。未操作其他任务。

需求部分成立、驱动代码需修复、实际整轮失败、原精确源码构建限定复用、性能债务继续延期。08/09未完成，不允许合并。后续问题选项须给出推荐及理由。
