# UI Diff 验收工具

`ui_diff.py` 用于把设计稿 PNG 与 Android 模拟器截图归一化到同一个内容帧，生成区域评分、动态区域 mask、控件树缺失项和浏览器可打开的 `report.html`。

## 运行

使用本地 Conda 环境：

```powershell
conda run -n detect python tools/ui_diff.py `
  --reference docs/ui-design-references/home-b.png `
  --actual <simulator-screenshot.png> `
  --config tools/ui-diff-home.json `
  --hierarchy <maestro-hierarchy.xml> `
  --out .scratch/ui-diff/home-412
```

报告文件：

- `report.html`：参考图、实际图、热力图、区域评分和控件树结果。
- `reference-aligned.png`、`actual-aligned.png`：按内容帧对齐后的图。
- `overlay.png`：半透明叠加结果。
- `diff-heatmap.png`：差异热力图。
- `result.json`：供 CI 或后续 Agent 判断的机器可读结果。

## 对齐与判定

- `reference_frame` 与 `actual_frame` 是内容帧像素坐标，不把原始 PNG 尺寸当作设计尺寸。
- `canonical_size` 使用设计基准 412 × 915 dp。
- `masks` 默认屏蔽状态栏和导航栏；业务图片不能默认 mask。
- `regions` 可分别设置 SSIM 下限和差异面积上限。
- 任何区域失败或必需控件缺失时退出码为 `1`；全部通过时为 `0`。

当前首页配置是首个样例。正式验收前，应根据每次模拟器截图和设计稿的真实内容帧校准 frame/regions，并为搜索、商家、结算、订单和 AI 页面增加独立 JSON 配置。
