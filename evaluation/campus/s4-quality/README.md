# 当前请求与偏好边界开发回归

固定计划为6条案例、每条重复2次，共12次任务。覆盖一句话、当前格式覆盖旧偏好、条件性偏好不触发任务、否定、引用示例和基础属性工具。它是开发回归，不是S2保留集，也不证明任意问题的语义准确率。

计划在首轮采集前固定；V1出现失败后保留原采集，用同一计划验证V2。能力介绍现在使用注册工具目录在本地生成；问候、属性问题仍走模型。报告按实际用量分别列出LOCAL、MODEL和UNKNOWN，不能把本地固定描述计作模型遵守提升。

```powershell
# 使用已就绪的独立本地实例和新目录，不覆盖旧证据
node tools/campus/s4-quality-scenario.mjs http://127.0.0.1:18143/api artifacts/campus/s4-quality/my-capture
node tools/campus/answer-quality.mjs artifacts/campus/s4-quality/my-capture/capture.json artifacts/campus/s4-quality/my-assessment
```

采集器要求本地账号模式、对应提示版本和加载class摘要一致，创建专用账号并保存每次偏好。密码和Cookie仅保存在内存，事件、成功历史、偏好与用量按摘要绑定。没有审批确认或队伍写入。Maven测试和独立服务启动顺序执行。

有限检查核对完成状态、正文/历史一致性、偏好快照、用量冻结、禁用/必要工具、长度、句数/编号及已登记的内部标识/计划泄露模式。它没有通用语义裁判；已知模式未命中仍可能存在幻觉。

每次评估同时生成review-template.json。复核必须绑定observationSha256、标明human或ai-assisted身份，逐条解释判断；AI辅助复查保持待人工验收。只有完整、有效且通过的人工复核才计作任务PASS。需要检查真实工具输入的条件，缺少输入证据时保留unknown。

V1与V2工程结果、范围及后续工作见[第八批实施记录](../../../document/秋招升级/S4/当前请求边界与输出遵守回归_2026-10-25.md)。
