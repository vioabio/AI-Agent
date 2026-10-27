# 当前请求与偏好边界回归

计划分母 12；有限格式/工作流检查通过 11；人工完整任务通过 0；失败 1；待复核 11。

MODEL：12次任务，有限检查通过11次，模型调用26次。
LOCAL：0次任务，有限检查通过0次，模型调用0次。
UNKNOWN：0次任务，有限检查通过0次，模型调用0次。

| 案例 | 轮次 | 生成来源 | 有限检查 | 字符数 | 语义复核 | 原因 |
| --- | ---: | --- | --- | ---: | --- | --- |
| QA-SHORT | 1 | MODEL | 通过 | 55 | PENDING_REVIEW | 人工语义复核单列 |
| QA-SHORT | 2 | MODEL | 通过 | 57 | PENDING_REVIEW | 人工语义复核单列 |
| QA-OVERRIDE | 1 | MODEL | 通过 | 94 | PENDING_REVIEW | 人工语义复核单列 |
| QA-OVERRIDE | 2 | MODEL | 通过 | 116 | PENDING_REVIEW | 人工语义复核单列 |
| QA-BACKGROUND | 1 | MODEL | 通过 | 3 | PENDING_REVIEW | 人工语义复核单列 |
| QA-BACKGROUND | 2 | MODEL | 通过 | 3 | PENDING_REVIEW | 人工语义复核单列 |
| QA-NEGATION | 1 | MODEL | 通过 | 136 | PENDING_REVIEW | 人工语义复核单列 |
| QA-NEGATION | 2 | MODEL | 通过 | 156 | PENDING_REVIEW | 人工语义复核单列 |
| QA-QUOTED | 1 | MODEL | 失败 | 260 | PENDING_REVIEW | INTERNAL_IDENTIFIER_LEAK |
| QA-QUOTED | 2 | MODEL | 通过 | 152 | PENDING_REVIEW | 人工语义复核单列 |
| QA-TYPE | 1 | MODEL | 通过 | 23 | PENDING_REVIEW | 人工语义复核单列 |
| QA-TYPE | 2 | MODEL | 通过 | 23 | PENDING_REVIEW | 人工语义复核单列 |

有限检查不证明事实正确、工具参数一致或所有偏好都遵守。AI辅助复查不能替代人工验收；未使用S2保留集，也没有准确率或统计提升结论。
