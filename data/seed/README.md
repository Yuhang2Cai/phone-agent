# 种子数据

用于存放开发和测试所需的可重复导入数据。不得放入生产数据或密钥。

当前目录包含 12 家商家、120 条菜品种子数据。菜品图片使用 redbook 项目同一 OSS Bucket 中的 `fandazi/seed-food/` 前缀；启动时会幂等回填已有种子菜品的图片地址，不会重复插入数据。

`reviews.json` 为每家商家提供 3 条虚构的只读演示评价，共 36 条。评价使用固定的昵称、评分、内容和 ISO-8601 时间；启动时按商家、昵称和创建时间识别已存在的评价，只补齐缺失行。因此已有 `seed_alice` 的数据库也会补齐评价，重复启动不会增加评价数。这些评价仅用于浏览展示，不代表真实用户反馈。

## 元数据 schema

后端通过 Flyway migration `backend/src/main/resources/db/migration/V1__create_seed_metadata.sql` 创建 `seed_dish_metadata` 和 `seed_address_metadata`。该 migration 在 Hibernate 启动前执行；生产配置继续使用 `spring.jpa.hibernate.ddl-auto=validate`，用于校验实体与已迁移 schema 的一致性，而不是由 Hibernate 创建表。

菜品的 `ingredients` 与 `allergens` 在 Java domain、loader、metadata entity 和 `DishResponse` 中均为 `List<String>`；数据库列只承载 converter 的 JSON 存储形式，API 不返回 JSON 字符串。

## Task2 团购数据与开发夹具

`stores/deals/group-orders/vouchers/coupons/group-reviews.json` 的确定性编号仅供种子内部引用。装载器通过 `public_id_aliases` 保存随机第四版 UUID；重复装载沿用映射，公开接口不接受 store-001、deal-001-1 等旧目录别名。V13 保持内部外键和用户归属，并转换历史明确资源字段；旧报价/确认/暂停任务失效，必须重新准备后获得确认。

V12 新金额列是 DECIMAL 元，Java 对外保留整数分，通过显式 JPA 转换器精确换算；订单实付、券分摊和退款申请都有持久化快照。10001 分的三券分摊为 3334/3334/3333，未支付券金额为 null。旧 `*_cents` 列保留历史原值并改为可空，后端不再写这些 legacy 列，新记录不能将其作为权威金额；本任务不清理历史列。

套餐图来自 `deals.json` 的 `imageUrl/imageAlt`；第一份招牌盖饭模板绑定同菜品图片，其替代文本注明套餐主食。第二份川味模板明确缺图，其他未配图模板也保持 null，不复用招牌盖饭图。

`task2-development-fixtures.json` 固定七种隔离场景：无订单、待支付、可退款、已申请、可评价、已评价、无候选券。每种使用新随机账号，数据库只保存密码哈希。仅当 `SPRING_PROFILES_ACTIVE=development`、`TASK2_FIXTURES_ENABLED=true`、`SEED_ENABLED=true` 时装载；包含 prod 或 production 配置时禁止。没有生成状态的生产 HTTP 入口。

启用后运行凭据清单默认位于后端工作目录上一级的 `.runtime/task2-fixtures.json`，可用 `TASK2_FIXTURES_OUTPUT` 指定同样位于 `.runtime` 内的绝对路径。清单包括场景、随机用户名/密码、真实 orderId/voucherIds/requestId/storeId/dealId。不要提交或在日志打印凭据。每次明确开启开发启动生成新七账号并替换清单，旧用户和订单全部保留；关闭开关后再次启动不会再创建场景。

核销事实仅用于评价开发夹具，本期核销界面仍延期；已申请场景通过实际退款确认服务产生 REQUESTED，未假造退款到账。
