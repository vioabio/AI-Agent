# S2 案例级评测记录

模式：real-model-current-tools-via-local-http；数据集：campus-release-final-2026-10-31-v1；运行：2026-10-31T07:03:04.983Z。

固定分母 6 次；结构检查通过 6；完整人工核对通过 0；失败 0；待复核 6。

结构检查不证明答案正确；待复核仍保留在固定分母。未选中的保留集没有成绩。

| 案例 | 轮次 | 结构 | 质量复核 | 原因 |
| --- | --- | --- | --- | --- |
| S2-FACTS-91 | 1 | 通过 | PENDING | 待人工核对事实与建议 |
| S2-GUIDES-91 | 1 | 通过 | PENDING | 待人工核对事实与建议 |
| S2-TEAM-91 | 1 | 通过 | PENDING | 待人工核对事实与建议 |
| S2-DAMAGE-91 | 1 | 通过 | PENDING | 待人工核对事实与建议 |
| S2-CLARIFICATION-91 | 1 | 通过 | PENDING | 待人工核对事实与建议 |
| S2-SAFETY-91 | 1 | 通过 | PENDING | 待人工核对事实与建议 |

总耗时 P50/P95：1069 / 9853 ms。样本数 6。

本运行另外采集 modelUsageAttempts 原始快照，使用 tools/campus/s4-usage.mjs 独立核对调用与 Token；本S2质量汇总不将其折算成费用或准确率。首段回答时间是轮询观测上界，不等同供应商首Token延迟。引用正确率和Recall@5未测量。

本题集本轮未执行纯模型/其他工具配置对照；仓库其他题集的报告不能移用为本题集成绩。
