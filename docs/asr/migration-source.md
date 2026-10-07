# Task14 redBook 离线 ASR 来源

来源为用户指定的 `D:/project/redBook`；迁移依据为2026-10-03进阶规格第5节。本次不引入redBook的Hilt、上传、下载、系统ASR、实时转写或语音自动提交。

| 来源 | SHA256 |
|---|---|
| AAR 1.12.39 | `D228A731EC3125170C962E2D52AD3FE012F3616E5047A87E2743D851D69C176D` |
| 双层目录 model.int8.onnx | `3EF6C19369B912F7CAF3CEF8E545C5CCD1A33D9D7EC792A46668DC41C4B229EC` |
| 同目录 tokens.txt | `4B2D964E18B9CF139B473003B6698FB2ED9A2A5EC55B93DAA677B28F578897AA` |
| SherpaOnnxSpeechRecognizer.kt | `1561DC910922B500D4D0D52620976DF7D847F2388E0059199FE9A4B4CF7FAAC1` |
| ChatDetailViewModel.kt录音来源 | `0C79A7C5BF7207EB6021A089405332366630C3630540B0F2EEE16762AD295ABD` |

应用minSdk26；AAR Manifest为minSdk21，包含arm64-v8a、armeabi-v7a、x86、x86_64，每个ABI包含ONNX Runtime、Sherpa C/C++接口和JNI共4个库。本轮保留全部ABI。

Sherpa源码许可已有[Apache-2.0依据](https://github.com/k2-fsa/sherpa-onnx/blob/v1.12.39/LICENSE)。该许可不能自动覆盖模型。原模型的[ModelScope元数据](https://www.modelscope.cn/models/crazyant/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8358-onnx/summary)许可字段为空，[Sherpa模型卡](https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09)也未声明直接许可，模型再分发状态仍为未核实。

本机受控迁移可执行 `scripts/Import-RedBookAsr.ps1 -IncludeLocalModel`，逐项校验来源与目标摘要。模型/token在许可闭合前不纳入公开Git提交或发布产物；私有构建可用用户现有资产。该脚本不下载未知替代文件；缺失来源会明确失败。

识别使用原双层路径、CPU两线程、16kHz/80维特征、greedy_search。录音沿用MIC、MPEG4、AAC、44100Hz、96000bps，新增30秒上限和本应用缓存句柄校验。识别只回填草稿，必须由用户编辑确认后手动发送。

本轮按用户要求不运行ASR单测、设备推理、现场录音或性能验收；提供测试入口不代表结果通过。需要人工补齐30条授权录音、离线/权限/取消/旋转/账号场景与arm64真机指标。
