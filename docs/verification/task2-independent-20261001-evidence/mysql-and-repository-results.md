# MySQL 与仓库独立证据

运行时间：2026-10-01 深夜至 2026-10-02 00:04 +08:00。文件日期沿用本次 Task2 验收编号。

## 真实 MySQL 只读结果

```powershell
. C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1
Get-Content -LiteralPath 'C:/fdz-build/docs/verification/task2-independent-20261001-evidence/mysql-readonly.sql' -Raw | docker exec -i -e "MYSQL_PWD=$env:DB_PASSWORD" mysql-server mysql -u $env:DB_USERNAME --batch --raw --skip-column-names fandazi_groupbuy_dev
```

实测 exit 0；不输出密码、备份正文或任何具体用户与资源 ID。

| 指标 | 实际结果 |
|---|---:|
| MySQL | 8.0.44 |
| 主库 / 隔离副本 Flyway | 13 / 13 |
| 主库历史订单 / 分合计 / 元合计 | 25 / 248200 / 2482.00 |
| 副本订单 / 分合计 / 元合计 | 25 / 248200 / 2482.00 |
| 副本券 / 退款 / 门店 / 套餐 | 29 / 6 / 30 / 120 |
| 副本 quote / grant | 21 / 45 |
| 副本稳定旧别名映射 | 215 |
| 副本未消费旧 grant / ACTIVE 旧 quote | 0 / 0 |
| 主库历史未消费旧 grant / ACTIVE 旧 quote（按副本内部 ID 联查） | 0 / 0 |
| 主库 / 副本历史订单金额转换差异 | 0 / 0 |
| 主/副本订单内部归属、店/套餐引用、金额、数量、状态、创建时间差异 | 0 |
| 主/副本券内部归属、订单/套餐引用、状态、分摊金额差异 | 0 |
| 主/副本退款归属、券引用、金额、状态差异 | 0 |
| 实付与券分摊合计差异 | 0 |
| 待支付订单或券金额错误非空 | 0 |
| 最后检查新订单 DECIMAL 已写且 legacy total_cents 为空 | 20 |
| 最后检查新 quote DECIMAL 已写且 legacy total_cents 为空 | 8 |
| 历史券 / 退款按内部 ID 保留 | 29 / 6 |
| 券外键缺失 | 0 |
| 同券多退款分组 | 0 |
| 公开资源 UUID 非完整随机 v4 | 0 |
| 别名映射 UUID 非完整随机 v4 | 0 |
| 已检查别名引用缺实体（店/套餐/订单/券/退款/报价/优惠券/用户优惠券） | 0 |

新订单/quote 数量包含父侧既有在线自测、本次随机独立账号；计数随后续验证增长，不用它们替代历史基线数。

实际权威金额类型均为 `DECIMAL(12,2)`：stores.average_spend_amount；deal_packages.price_amount/original_price_amount；purchase_quotes.subtotal_amount/discount_amount/total_amount；group_buy_orders.total_amount/paid_total_amount；vouchers.allocated_amount；refund_requests.amount；coupons.threshold_amount/discount_amount；confirmation_grants.amount。

已检查保留旧列 `price_cents/total_cents/amount_cents` 仍为 int 且可空；订单、券、退款 created_at 及 paid_at/redeemed_at 为 timestamp(6)。真实唯一索引 `uk_refund_requests_voucher` 和 `uk_store_reviews_order` 的 non_unique 均为 0。

MySQL CLI session 为 SYSTEM、system 为 UTC。历史领域本地时间按项目已确认上海语义解释；独立 HTTP 探针对同一行原始 created_at 赋予 +08:00 后转 UTC，并对照首返微秒值，订单/券/退款均相等。另以随机新订单的创建前后实际 UTC 时钟界定 createdAt，排除只给上海时间直接补 Z 的错误。

本 Agent 没有重新读取/恢复敏感 dump、升级或重启真实库；亲自验证的是当前真实 V13 主库/副本保留结果，独立 V11→V13 执行及重跑由 GroupBuyingMigrationTest 的真实 Flyway/JDBC（H2）完成。父侧 MySQL 恢复/升级报告仅作环境定位，不替代本表实测。

## 仓库原始字节

手机 HEAD `f4902b8707cd128a7c726768fd02faf4f1931d58`；D 仓 HEAD `fad28f80c47698018bde0f7fffe9f5b1eb210f93`。

- `backend/src/main/resources/db/migration` 11 个 V1–V11 文件逐个 `git hash-object` 对 `git rev-parse HEAD:<path>`：CHANGED=0；`git diff --name-only HEAD` 也为空。
- `AgentCardEnrichmentService.java` 和 `AgentCardEnrichmentTest.java` 同样逐字节 EXACT_HEAD=True；文案失败调用链只经过未改的 required/enrich 校验，与本次新金额/时间/UUID 路径无关。
- 双仓权威与受控副本 Get-FileHash SHA256 相等：协议 `838542d06b2ef05bf3e2ba125d3cab9ba28533359883334ecf002683544d7019`；工具 `56d52e44c6e89998eb26c2e43d43e41fa9fce82c2434347d6392b5b01253dcd4`。对应 D manifest.yaml 与 source.sha256 均一致；352 项离线包含跨仓严格同步回归。
- `git check-ignore` 确认 `.runtime/task2-fixtures.json`、`.runtime/task2-test-fixtures.json`、`.runtime/backups/task2-before-20261001.sql` 全部忽略；对这三路径 `git ls-files` 为空。未读取或打印备份正文，凭据清单只在独立脚本内存使用。

## 服务收尾

未重启或终止服务。最后 `GET http://127.0.0.1:8080/api/stores` HTTP 200，公开列表 29 个可展示门店；数据库 30 个总门店包含非公开可展示门店，不混淆两类计数。
`GET http://127.0.0.1:8091/internal/v1/health` HTTP 200，status=ok、schemaVersion=1.1、sourceRoot=`D:/project/-Demo-master`、entryPoint=`service.app:create_app`。
一次误用 `/api/health` 收到需鉴权响应，该路径未用于通过判定；后续使用实际公共目录与新 Agent 健康入口。
