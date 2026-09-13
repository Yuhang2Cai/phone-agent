# 种子数据

用于存放开发和测试所需的可重复导入数据。不得放入生产数据或密钥。

当前目录包含 12 家商家、120 条菜品种子数据。菜品图片使用 redbook 项目同一 OSS Bucket 中的 `fandazi/seed-food/` 前缀；启动时会幂等回填已有种子菜品的图片地址，不会重复插入数据。

## 元数据 schema

后端通过 Flyway migration `backend/src/main/resources/db/migration/V1__create_seed_metadata.sql` 创建 `seed_dish_metadata` 和 `seed_address_metadata`。该 migration 在 Hibernate 启动前执行；生产配置继续使用 `spring.jpa.hibernate.ddl-auto=validate`，用于校验实体与已迁移 schema 的一致性，而不是由 Hibernate 创建表。

菜品的 `ingredients` 与 `allergens` 在 Java domain、loader、metadata entity 和 `DishResponse` 中均为 `List<String>`；数据库列只承载 converter 的 JSON 存储形式，API 不返回 JSON 字符串。
