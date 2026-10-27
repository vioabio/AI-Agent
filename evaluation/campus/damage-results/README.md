# 伤害实际条件与结果回归

固定4条案例、各2次，共8次模型任务。覆盖完整配置、null配置与默认值、不同等级及天气/地形、物种编号和字段别名规范化。它是独立开发回归，不修改S2保留集或上一批参数计划。

```powershell
# 对应版本的独立本地实例就绪后；两个结果目录须尚不存在
node tools/campus/s2-damage-result-scenario.mjs http://127.0.0.1:18145/api artifacts/campus/damage-results/my-capture
node tools/campus/damage-results.mjs artifacts/campus/damage-results/my-capture/capture.json artifacts/campus/damage-results/my-analysis
```

采集器使用专用本地账号及隔离SQLite/公开资料缓存，密码与Cookie只在内存。开始前逐案例执行HTTP计算控制请求并预热公开缓存，随后运行固定8次Agent任务；不确认审批或修改队伍。控制请求与Agent调用同一引擎，只用于比较实际条件和结果，不是独立公式正确性证明，也不作为冷启动性能证据。

计划要求原样传入JSON，同时验证原始参数摘要和逐字段证据。结果检查关联同次调用的outputEvidence、工具版本、参数摘要、Java DTO摘要、已校验结果投影摘要及原始输出摘要；比较实际等级/EV/IV/特性/道具/性格/招式/场地和伤害范围。名称只以摘要比较，原始输出摘要不是加密或防伪签名。

分别记录叶字段未提供、显式null和引擎默认特性。父对象为null时，其叶字段属于未提供；父对象的显式null仍保留在输入证据中。数组中的配置招式并非本引擎的全部使用条件；当前计算使用单独传入的move，不宣称模拟所有配招、强化或状态。

失败、缺失、篡改和旧版没有结果证据的记录保持失败，不从固定分母中剔除。人工回答语义验收保持NOT_PERFORMED；策略建议、赛制合法性和任意计算公式正确性不由8次条件核对推出。

报告的pass是含原样输入要求的严格检查；resolvedPass是实际条件/结果与记录一致性的检查。实际调用的输入/输出DTO摘要必须相等，控制请求的等价别名则通过规范化条件比较。缺失指标或旧记录缺少调用DTO绑定时保持未验证，不追溯修改既有报告。

运行代码、已知失败及结果见[伤害条件与结果绑定](../../../document/秋招升级/S2/伤害条件与结果绑定_2026-10-26.md)。
