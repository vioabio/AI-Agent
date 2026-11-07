# 上下文预算证据归档

- `verification-summary.json`：工程检查、场景范围、未完成事项及原始文件SHA-256。
- `runtime-identity.json`：458个源码文件摘要、加载class摘要、模型配置和预算策略；配置身份不等于供应商部署版本证明。
- `conversation-scenario.json`：最终实现的三轮真实对话，独立账号/数据库，历史上限临时设为1轮；包含受控测试输入下的模型回答。功能通过不代表回答质量通过。
- `blocked-scenario.json`：总预算512的预检拦截，0次模型边界调用，无助手回答入库。
- `development-context-audit.json`、`source-regression.json`、`usage-verification.json`：三条既有S2开发题的逐次预算、来源/快照与原生usage核对；队伍修改未批准，独立人工评审待办。
- `sdk-failure-history.json`：两版共六条失败与最终三轮结果，以及第三轮意图/简洁性缺口。失败未删除，未挑选最佳轮次。

完整事件、原始报告和日志仍位于`artifacts/campus/s4-context/`与`artifacts/s4-context-*.log`，不覆盖旧结果。归档只包含受控测试结果、数字、摘要与安全配置；不包含API Key、密码、恢复码或Cookie。费用、HTTP请求次数、保留集提升和独立质量验收继续未知/未完成。
