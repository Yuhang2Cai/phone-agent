# 后端

Spring Boot 是饭搭子 AI 的业务权威层：认证、菜单、地址、购物车、订单、模拟支付、配送时间线、Agent Gateway、SSE 和内部工具授权都在这里裁决。

## 运行

需要 JDK 17、Maven 和 MySQL。生产配置使用 Flyway migration + Hibernate validate。

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/fandazi_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:DB_USERNAME = "fandazi"
$env:DB_PASSWORD = "<local-db-password>"
$env:JWT_SECRET = "<64+ chars local secret>"
$env:AGENT_INTERNAL_TOKEN = "<shared internal token>"
$env:AGENT_SERVICE_URL = "http://127.0.0.1:8090"
mvn -f backend/pom.xml spring-boot:run
```

## 测试

```powershell
mvn -f backend/pom.xml test
```

Task 12 新增 `CrossLayerOrderingFlowTest`，用 H2 测试配置覆盖注册、菜单、地址、购物车、订单、支付、时间线、资源归属和幂等。不要把真实 JWT secret、内部 token、数据库密码或日志提交到仓库。
