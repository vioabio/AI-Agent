# S2 案例级评测记录

模式：real-model-current-tools-via-local-http；数据集：campus-s2-seed-v1；运行：2026-10-31T07:18:34.248Z。

固定分母 14 次；结构检查通过 14；完整人工核对通过 0；失败 0；待复核 14。

结构检查不证明答案正确；待复核仍保留在固定分母。未选中的保留集没有成绩。

| 案例 | 轮次 | 结构 | 质量复核 | 原因 |
| --- | --- | --- | --- | --- |
| S2-FACTS-01 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-FACTS-02 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-FACTS-03 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-GUIDES-01 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-GUIDES-02 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-GUIDES-05 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-GUIDES-06 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-TEAM-01 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-TEAM-02 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-DAMAGE-01 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-DAMAGE-02 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-CLARIFICATION-01 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-CLARIFICATION-02 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |
| S2-SAFETY-01 | 1 | 通过 | ASSISTED_REVIEW_ONLY | 待人工核对事实与建议 |

总耗时 P50/P95：5899 / 9414 ms。样本数 14。

本运行另外采集 modelUsageAttempts 原始快照，使用 tools/campus/s4-usage.mjs 独立核对调用与 Token；本S2质量汇总不将其折算成费用或准确率。首段回答时间是轮询观测上界，不等同供应商首Token延迟。引用正确率和Recall@5未测量。

本题集本轮未执行纯模型/其他工具配置对照；仓库其他题集的报告不能移用为本题集成绩。
