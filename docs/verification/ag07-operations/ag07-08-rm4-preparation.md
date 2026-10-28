# AG07 RM-R4离线准备验收报告

2026-10-27；同一主Agent。对应[独立有界方案](../../superpowers/plans/2026-10-27-ag07-ui-paused-continuation-r4.md)。本报告只判定准备阶段，不判定真实UI、业务或AG07完成。

## 实际结论

RM4-00离线准备通过；RM4-01—05真实前置与剩余业务未运行，待新单轮授权。新增模型/数据库/真实采集/资源启动均0。原RM-R3及此前失败保留；原暂停任务、两代撤销授权和数量2只用已有原始事实离线核验，不再次查询或修改数据库。

冻结Agent树`071c2c5e16d0e8126ee5790fa15b7d7f372e5e2d`，隔离目录`C:/ag07-build/08-rm4-agent`；Phone树`38d0b00bd21be80b4f33cfcdb626c77d99d093cc`，隔离目录`C:/ag07-build/08-rm4-phone`。二者是验收候选树，非已提交HEAD或master。开发HEAD仍61d04b2/896a966，master仍8eed532/e6877e2；本轮未提交、未合并。

## 改动与证据选择

| 项目 | 新运行 / 复用 | 版本、范围及实际结果 |
|---|---|---|
| 暂停身份与恢复守卫 | 新离线运行 | 新ui_paused模块；暂停时核原始全字段事实，恢复只允许同task/run控制状态变化，数量、订单、旧授权保持；页面核资源ID、中文和旧确认禁用。 |
| 费用及阶段 | 新离线运行 | 19配置进入真实共享SendGuard，第20次网络前拒绝；paused_remaining仅fresh/confirm/refund/saturation，禁止重复prepare/correct。19配置同样接入真实工具传输采集。 |
| 控制上下文 | 新离线运行 | 源码发现resume会追加一条pending并推进上下文版本；原过严比较的2项指定失败保留，修复后只接受同源同run同commandId事件，实体/限制/纠正等其余事实不变。不是扩大业务允许范围。 |
| 组合测试 | 新运行 | 开发70通过；隔离候选同70通过。首次隔离临时父目录缺失导致19通过/51环境错误，不算预期红测；保存paused-isolated.xml，改用既有私有父目录后paused-isolated-r2.xml通过，无源码变化。 |
| 精确源码构建 | 新运行+限定复用 | 候选85份相关Python源码编译退出0；测试/驱动改动不影响产品输入。Phone backend/android/contracts与bf48cc6e无差异，569产物文件逐字节核一致后复制复用，APK仍7c72249b…e52a77，不重建、不清除原会话。 |
| UI流程 | 新语法检查，真实待验 | rm-paused-restore.yaml、rm-resume-only.yaml的Maestro check-syntax均退出0/OK；真实界面未执行，不计UI通过。rv-correct旧修复保留但不纳入本轮执行。 |
| 中文采集 | 既有严格解码验证复用+本轮组合 | 598a33ea引入的严格UTF-8与U+FFFD拒绝继续有效；新helper显式JVM stdout/stderr编码。标准流原始字节、原生JSON、派生XML均保存。实际业务页修复仍须RM4-01验证，旧乱码不改判。 |
| 授权/输入 | 新入口负例 | 新父入口以pending输入运行，退出1且指定new_round_not_authorized；运行目录未创建、0资源。4173文件SHA核对一致、12原冻结材料一致。授权字段只有新明确许可后才能修改；不从旧轮结转。 |

## 可复现命令与安全证据

证据根：Agent工作树`.runtime/acceptance/ag07-operations/ag07-08/`。除报告和代码外，原始运行数据保持私有，不进入Git。

- 测试：`C:/Users/caid0/anaconda3/python.exe -X utf8 -m pytest tests/test_ag07_ui_paused.py tests/test_ag07_ui_remaining.py tests/test_ag07_ui_revalidation.py tests/test_ag07_ui_hierarchy.py tests/test_ag07_ui_runtime.py tests/test_ag07_ui_send_guard.py -q`；均显式唯一私有basetemp与JUnit。开发退出0/70、隔离退出0/70。
- 首次13指定红测`paused-red.xml`；上下文合法控制转换2指定红测`paused-context-red.xml`；开发`paused-final.xml`；隔离首次环境错误`paused-isolated.xml`与有效复验`paused-isolated-r2.xml`。
- `run_ui_remaining_r4.py --execute`只触发未授权守卫，退出1；`rm4-pending-guard.json/.stdout/.stderr`记录，不是一次真实旅程。
- `isolated-candidate-rm4.json`、`ui-remaining-input-r4.json`固定来源和预算；`rm4-offline-result.json`汇总实际编译、测试及0调用。父/子脚本均静态编译成功。

## 下一步与边界

批准新一次3文本/1确认/19发送后，先核真实原暂停页面，再进行仅控制恢复，核同scope/task/run、旧确认撤销、原订单不变、0模型；全部通过才开启剩余文本。首错停止，不重复入口、不拼接旧失败为通过。

08仍受阻、09尚未执行。最终需求、质量、运行、源码及性能结论仍按主报告分别给出；本轮没有在线Agent质量结论。性能债务继续延期、原门槛和未运行范围不变；双master合并仍须用户当次同意。
