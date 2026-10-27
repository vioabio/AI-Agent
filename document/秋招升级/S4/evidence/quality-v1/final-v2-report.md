# 当前请求与偏好边界回归

计划分母 12；有限格式/工作流检查通过 12；人工完整任务通过 0；失败 0；待复核 12。

MODEL：4次任务，有限检查通过4次，模型调用10次。
LOCAL：8次任务，有限检查通过8次，模型调用0次。
UNKNOWN：0次任务，有限检查通过0次，模型调用0次。

| 案例 | 轮次 | 生成来源 | 有限检查 | 字符数 | 语义复核 | 原因 |
| --- | ---: | --- | --- | ---: | --- | --- |
| QA-SHORT | 1 | LOCAL | 通过 | 54 | PENDING_REVIEW | 人工语义复核单列 |
| QA-SHORT | 2 | LOCAL | 通过 | 54 | PENDING_REVIEW | 人工语义复核单列 |
| QA-OVERRIDE | 1 | LOCAL | 通过 | 54 | PENDING_REVIEW | 人工语义复核单列 |
| QA-OVERRIDE | 2 | LOCAL | 通过 | 54 | PENDING_REVIEW | 人工语义复核单列 |
| QA-BACKGROUND | 1 | MODEL | 通过 | 3 | PENDING_REVIEW | 人工语义复核单列 |
| QA-BACKGROUND | 2 | MODEL | 通过 | 3 | PENDING_REVIEW | 人工语义复核单列 |
| QA-NEGATION | 1 | LOCAL | 通过 | 117 | PENDING_REVIEW | 人工语义复核单列 |
| QA-NEGATION | 2 | LOCAL | 通过 | 117 | PENDING_REVIEW | 人工语义复核单列 |
| QA-QUOTED | 1 | LOCAL | 通过 | 117 | PENDING_REVIEW | 人工语义复核单列 |
| QA-QUOTED | 2 | LOCAL | 通过 | 117 | PENDING_REVIEW | 人工语义复核单列 |
| QA-TYPE | 1 | MODEL | 通过 | 23 | PENDING_REVIEW | 人工语义复核单列 |
| QA-TYPE | 2 | MODEL | 通过 | 23 | PENDING_REVIEW | 人工语义复核单列 |

有限检查不证明事实正确、工具参数一致或所有偏好都遵守。AI辅助复查不能替代人工验收；未使用S2保留集，也没有准确率或统计提升结论。
