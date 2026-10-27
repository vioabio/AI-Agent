# 伤害实际条件与结果回归

计划8次；输入、实际条件、同引擎结果及记录核对通过4次，失败4次；人工语义验收未进行。

| 案例 | 轮次 | 结果 | 原因 |
| --- | ---: | --- | --- |
| DR-EXPLICIT | 1 | 通过 | 有限检查通过 |
| DR-EXPLICIT | 2 | 通过 | 有限检查通过 |
| DR-DEFAULTS | 1 | 失败 | EXPECTED_INPUT_NOT_VERIFIED, RESOLVED_RESULT_NOT_VERIFIED |
| DR-DEFAULTS | 2 | 失败 | EXPECTED_INPUT_NOT_VERIFIED, RESOLVED_RESULT_NOT_VERIFIED |
| DR-WEATHER | 1 | 通过 | 有限检查通过 |
| DR-WEATHER | 2 | 通过 | 有限检查通过 |
| DR-ALIASES | 1 | 失败 | EXPECTED_INPUT_NOT_VERIFIED, RESOLVED_RESULT_NOT_VERIFIED |
| DR-ALIASES | 2 | 失败 | EXPECTED_INPUT_NOT_VERIFIED |

HTTP控制和Agent调用使用同一引擎；不能将相等当作独立算法正确性证明。缺失、显式null和默认特性分别统计，固定分母不筛除失败。
