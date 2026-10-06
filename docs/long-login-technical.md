# 长期登录与自动续期技术说明

首次密码登录后，App将完整会话加密保存；杀进程和桌面重新打开只重建进程，不删除会话。短期访问JWT自然到期由刷新凭据静默续期，主动退出或刷新会话失效才重新要求密码。本次不修改登录页面/Figma、不保存密码，也不延长JWT来模拟免登录。

本文对应2026-10-06长期登录交付；根仓本文与手机仓docs/long-login-technical.md为相同内容镜像，验收版本和提交闭合见末节。

## 凭据和期限

| 对象 | 保存位置 | 默认期限 | 用途 |
|---|---|---|---|
| 访问JWT | Android加密会话 | 60分钟 | 普通业务与SSE请求 |
| 刷新秘密 | Android加密会话；服务端仅SHA256摘要 | 30天未续期失效 | 申请新访问JWT与轮换秘密 |
| 会话sid | JWT与auth_sessions | 从密码登录起180天绝对上限 | 身份连续性与在线撤销 |
| 待续期requestId | 与旧刷新秘密共同加密落盘 | 直到本轮成功落盘或退出 | 网络丢响应和进程死亡恢复 |

刷新实际成功会把空闲截止移至当前时间加30天，但不超过初次登录加180天。30天是没有续期的时间，不是每30天强制输入一次密码；经常使用App时会持续续期。超过绝对期限仍需密码，此限制可由部署配置调整。

## 接口

接口相对地址为/api/auth。所有含认证凭据的响应Cache-Control:no-store，使用HTTPS部署；认证秘密只放JSON正文和Authorization头，不进入查询参数、日志或公开缓存。

| 方法和路径 | 请求字段 | 成功 | 明确拒绝 |
|---|---|---|---|
| POST login | username、password | 200完整凭据包 | 保持原密码错误语义 |
| POST register | 原注册字段 | 201完整凭据包 | 保持原重复账号语义 |
| POST refresh | refreshToken、requestId | 200完整凭据包 | 401 SESSION_INVALID |
| POST logout | refreshToken | 204，重复撤销幂等 | 不暴露会话存在性 |

完整凭据包保留原token和user，增加sessionId、refreshToken、accessExpiresAtEpochMs、refreshExpiresAtEpochMs。时间均为UTC Unix毫秒。refreshToken是会话UUID、点号与32字节秘密的Base64URL表示；UUID单独不能认证。requestId由客户端生成UUID并在发包前持久保存。

## 数据与轮换

Flyway V20新增auth_sessions，不修改原账号和业务表。字段包含id、user_id、当前token_hash、previous_token_hash、previous_request_hash、idle_expires_at、absolute_expires_at、revoked_at；用户索引用于定位/清理，账号删除级联删除会话。

服务端在事务中悲观锁定sid。提交当前秘密时，摘要匹配才生成后继、保存旧摘要与请求摘要，并前移空闲到期。后继使用JWT密钥经独立用途派生的HMAC键计算，输入为旧完整秘密和请求标识；因此丢响应后能重新推导同一个后继，不需要保存可还原的完整秘密。签名密钥轮换会影响这项恢复能力，部署必须协调所有实例使用同一密钥。

上一轮相同秘密和相同requestId，只在推导结果仍匹配当前摘要时允许恢复同一后继刷新秘密，访问JWT可以重新签发；不延长空闲期限、不再次轮换；当前凭据又轮换之后，这个恢复机会自然结束。上一轮真实秘密配不同requestId视为重用，撤销会话；拒绝异常不能回滚撤销。更早或随机错误秘密直接拒绝，不仅凭已知sid撤销别人。客户端单次并发续期和请求标识持久化共同约束这一恢复协议。

新的访问JWT带sid。过滤器在签名、到期验证之后还核对sid本人归属、账号存在、撤销、空闲/绝对截止，所以在线退出后的旧JWT即使未自然到期也不能继续调用受保护接口。

## Android调用与竞争

EncryptedSharedPreferences保存完整凭据包，原子commit后才发布身份。刷新互斥锁把同时触发的业务请求合并为一次续期；实际网络等待不占身份修改锁，退出和新登录能够先完成。返回时再次核对原会话版本，迟到旧续期不能覆盖新登录或恢复已退出身份。

普通HTTP和Agent/SSE复用发包前续期拦截器；认证接口使用独立Dispatcher及无自动续期拦截器的客户端，防止刷新递归或业务调度名额占满后的互相等待。剩余不足一分钟主动续期。GET在真实401后允许续期重读一次；POST、PUT、PATCH、DELETE及消息均不自动重放，用户写入失败仍需明确再次操作。取消后的业务调用不会因续期结果重建请求。

续期不改变sid。导航比较稳定会话身份，而不是JWT字节，因此正常换JWT不清聊天、草稿和页面栈；明确重新密码登录的新sid仍清理旧身份任务。旧版不带sid的身份继续按原JWT比较，避免改变历史兼容行为。

## 失败和退出

网络、超时、5xx、非法响应和存储失败均不当作“密码错误”，保留可恢复会话；已知凭据过期时不继续向业务发送写请求。刷新明确401才使身份失效。丢响应后的下一次续期使用已经落盘的同一请求标识；轮换响应未成功落盘也保留旧包和标识，以便恢复。

主动退出按原存储成功条件先清本地身份，再尽力撤销服务端会话。网络可用时旧访问JWT和刷新凭据立即失效；离线时本地退出立即生效，但无法当场撤销远端泄漏副本，仍由在线撤销或服务端期限限制。此版本没有跨设备退出管理和离线撤销队列。

## 配置和兼容

| 环境变量 | 默认 | 说明 |
|---|---|---|
| JWT_EXPIRATION_MINUTES | 60 | 访问JWT寿命 |
| AUTH_SESSION_IDLE_DAYS | 30 | 刷新空闲期限 |
| AUTH_SESSION_ABSOLUTE_DAYS | 180 | 同一次密码登录的绝对期限，必须不小于空闲期限 |
| JWT_SECRET | 必填 | 沿用安全注入，不写源码和文档 |

先升级后端执行V20，再升级Android。旧客户端仍可读取token/user；旧JWT不带sid，在原到期前兼容，但不能凭旧JWT自动制造刷新资格。升级前已经登录的Android身份在旧JWT第一次过期时需一次密码登录，以取得新凭据包；之后按新机制续期。数据库迁移保留，不为了应用回滚删除会话表或业务数据。

本方案采用[RFC 9700](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14)的轮换和重用检测思路，自有requestId幂等恢复为本项目协议扩展；不宣称完整OAuth服务。刷新秘密仍具有持有者权限，Android加密存储、TLS、服务端摘要和撤销不能替代设备本身的安全性。

## 构建与验收入口

后端在backend目录执行`mvn -o package`；Android在android-app目录执行`gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --offline --max-workers=1 --no-daemon -Pkotlin.compiler.execution.strategy=in-process -PdebugApiBaseUrl=http://10.0.2.2:18080/api/ -I ../scripts/Exclude-AdvancedAsrTests.init.gradle`。本机使用JDK17，最终Gradle JVM上限为Xmx768m、MaxMetaspaceSize512m；此参数只用于受控构建，不修改App运行配置。ASR仅编译依赖，不运行录音/推理/相关测试。

实际Maestro入口为`maestro --device emulator-5560 test -e LOGIN_PHASE=login maestro/long-login.yaml`；账号密码只通过进程环境变量MAESTRO_USERNAME/MAESTRO_PASSWORD注入，禁止写入命令参数、YAML或公开日志。恢复场景由桌面真实点击后再运行cold、ordinary、expired、offline、restored阶段；logout阶段主动退出。不能以launchApp或重复密码登录代替恢复验证。设备控制器检查旧PID确实消失，普通死亡使用am kill；不满足回收条件时用同UID SIGKILL，不设置force-stop标志。

## 实际验收

2026-10-06，单主Agent按步骤验收。冻结源码树`4b3a80f2316c106a9550b28a390a649c8c4ac420`；同一隔离快照实际运行Android **371/371**、Spring **200/200**，无失败/错误/跳过。完整APK与测试APK构建退出0，lint 0错误、709警告；警告未宣称已全部治理。

| 场景 | 实际结果 |
|---|---|
| 第一次密码登录 | 加密保存完整包，后续恢复段不再次输入密码 |
| 桌面强停后点击图标 | 旧进程消失、新PID建立，仍是原账户 |
| 普通进程死亡后点击图标 | am kill或同UID SIGKILL，仍是原会话 |
| JWT自然到期 | 测试寿命120秒，稳定无轮换等待130.35秒；重新打开后摘要轮换、sid不变 |
| 离线与恢复联网 | 实际无默认网络时保留身份；联网后无密码恢复 |
| 主动退出与桌面重开 | MySQL会话已撤销，重新打开仍要求密码 |
| 真实MySQL/HTTP | V20成功；轮换、同请求恢复、重放撤销、并发锁和退出通过 |
| 写请求重放 | MockWebServer验证POST401只发一次；GET允许一次续期重读 |
| 默认配置恢复 | 本机集成服务恢复60分钟，实测新包59.998分钟；设备真实HTTP仪器1项通过 |

八个正式设备画面均亲阅；退出后的两张登录表单与当前已确认Figma34:43比对。我的页沿用既有资源，只验功能/布局连续性，不宣称待确认UI-16的新设计已获确认。最终补正源码注释后重新构建，APK字节摘要与八段已运行产物完全相同，因此其设备证据有效；最终jar各解压条目也与认证组合测试运行版本逐字相同，打包时间不同不作为行为变化。

APK SHA256：`d856c44b87214c09a16cbf56e798d34290769a2298537cf641cc3e90e969a27b`。测试APK SHA256：`c340483b2f63cc24f5e07911fe3179ab8e7cab9a7cbcd2dd39ef1efd2aa28ad7`。最终运行jar SHA256：`31b7b3b2e5bed6f4973debb2d9c326003dfa59f7ef2c2efe6df726939e70bcfd`。

消息/报价/订单/券没有新增；原合成账号计数仍为1226/8/3/3，测试账户四类均0。原账号已恢复为新长期会话，网络与代理恢复；加密会话XML实查未发现密码、用户名或刷新包字段明文。源码原28项改动及既有暂存单独保护，不进入本次提交。

手机源码提交：`2ad3ee87e58bc574a93dbe8a1c213c989c2997b1`；手机文档元数据与根仓文档提交登记在详细验收报告。项目根仓详细报告：docs/verification/2026-10-06-long-login-step-acceptance.md。

整机重启、30/180天真实等待和正式arm64性能未实测；期限边界由独立测试会话截止验证，只有短JWT到期使用真实等待。机制上整机重启并解锁后仍读取加密存储；清数据/卸载重装则需要重新登录。升级前旧会话需首次密码登录一次换取刷新资格。跨设备退出和离线撤销队列未实现。Task18原ASR、模型许可与正式性能遗留状态不因本任务而改变。
