# 本期UI活动验收清单

完整只读Figma清单见active-states.json，包含实际Frame/nodeId、归类、入口、数据、Maestro和待运行状态；不把参考图当可编辑页面。

- 当前活动设计采用393×852，Task12最新APK逐屏核对结构和交互；允许小尺寸/颜色差异，不允许缺能力、假业务事实、重叠、错误确认或不可点击。
- UI06使用64:225；29:2是历史重复，单列不重复计通过。UI13/14核销及90:7/90:41退款完成/拒绝预留状态延期，后端REQUESTED不能显示成已到账。
- UI11R/RR评价资格由开发夹具真实数据提供，不代表本期实现了核销。
- UI22A–H与192:2是Task10按既有体系派生的记忆/已登录页面，经独立核对；没有新的人工图稿签字不能冒称真人审批。
- 派生的评分、原因、分类、填写/必填/选单等状态按同一交互家族覆盖，具体截图和结果由实际Task12运行报告补充；未运行/受阻保持待验收，不自动按Frame存在计PASS。
- 每轮必须记录APK摘要、后端/Agent源码来源、隔离数据、适用流程退出码、截图路径与Figma差异结论。最终独立报告更新结果引用，不将开发自测替代独立通过。

## Task12最终结果

Archimedes R7独立四轴PASS、阻塞0。实际128状态为41 PASS、82 PARTIAL、4 DEFERRED、1 SUPERSEDED；没有开放FAIL/NOT_RUN，PARTIAL不算整屏通过，表示同源派生视觉排列或样例尚未逐个截图。必要独特功能已经真实执行；依据及未覆盖边界保留在每个状态字段中。

`active-states.json`保留原设计尺寸/入口/数据/脚本并按nodeId合并最终安全结果；原始签发矩阵为D:/project/-Demo-master/docs/verification/task12-final/UI-state-results.json，报告入口为D:/project/-Demo-master/docs/verification/2026-10-03-task12-independent-acceptance.md。截图路径指向本机忽略的独立证据，公开成组对照在报告同目录，不整体提交原始日志或凭据。

最终独立APK SHA256为`f0353c70c9c82e524068fc70f8551a09f7afe8561ccef95d544809dd2dd50884`。R5完整99、R6认证/异常、R7记忆/必要UI各保留真实来源与未变blob复用理由，不宣称所有场景在最终一轮重新全跑，也不把继承Task10派生图稿的独立核对称为新的真人签字。
