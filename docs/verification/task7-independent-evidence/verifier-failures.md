# Task7 独立验收首次失败记录

## Agent 临时目录权限

本轮首次命令使用固定 pinnT 执行 5 个适用测试文件，结果 94 passed、1 error。错误位于 pytest 临时目录创建阶段：`PermissionError: [WinError 5]`，目录为系统 Temp 下既有 `pytest-of-caid0`。原报告保留于 agent-tests.xml、python-result.json；原日志在忽略 .runtime/task7-independent/python.log。

复跑仅增加指向本轮忽略目录的 `--basetemp`，不修改任何产品或测试源码/断言。复跑结果为95 passed，0失败/错误/跳过，9.31秒；agent-tests-retry.xml和python-retry-result.json单独留档。

## Figma 工具连接

2026-10-02 本轮首个 use_figma 只读采集当前 UI10–12 完整树调用失败：`Transport closed`。重试整体树输出约20KB被工具截断，JSON不完整；不将它当完整图层。分Frame重取后三个JSON均有效并包含全部子层，当前三张截图重新从Figma获取，未用开发图替代。

## 来源路径验证器

最初按进程命令行直接匹配工作区路径得到Spring sourceMatched=false。实际JVM用Maven临时参数文件传递classpath；展开后Windows转义仍导致一次误判。最终解析参数文件首classpath项并规范化转义，实查C:/fdz-build/backend/target/classes，sourceMatched=true。三份首次诊断和最终spring-source-final.json全部留存。这是验证器路径解析问题，不是旧Spring入口。

## 命令统计脱敏

首次人工查看私有commands.json的metadata时，误将带有本轮新账号登录变量的首条evaluatedCommand带入工具诊断输出。公开文件未包含该对象，但该本轮口令仍需收尾轮换；只操作本轮3个账号，不读取或轮换旧Task账号。后续统计只导出metadata.status、计数、时间和截图指纹，不导出env/inputText/evaluatedCommand。不得在报告或公开证据重复原值。

安全收尾轮换首跑退出1，原因是验证器把错误口令状态误预期401；AuthController:45–47原协议为400，独立诊断实测400，新口令登录实际200。首跑记录cleanup-credentials-result.json和私有日志保留；仅修验收辅助断言为400，不改产品，也不改变JWT页面401断言。安全收尾重试单独记档。

重试退出0，三个本轮新账号旧口令均400、新口令均200，own-credentials-rotated.json留存；公开扫描零秘密/JWT匹配。

## 私有清单字段比较器

首次收尾把开始证据对象整体序列化比较，开始多一个matchesDevelopment采集字段，导致误报“原私有清单变化”。首次protected-release.json保留；逐项path/bytes/sha256复核438项零变化、零缺失，protected-release-retry.json为实际字节判定。没有读取旧JSON内容或修改旧资源。
