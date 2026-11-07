# Run 累计模型额度证据

- `verification-summary.json`：992项后端测试、121项前端契约、47项工具测试和5项浏览器检查，六条计划请求范围、初始测试断言失败、未完成事项及原始文件SHA-256。
- `runtime-identity.json`：461个源码文件摘要、31个加载class摘要、模型配置与默认预算；配置身份不证明供应商部署版本。
- `success-scenario.json`：独立账号/数据库下的正常SDK流与历史/累计额度核对。
- `calls-scenario.json`、`tokens-scenario.json`：分别限制1次调用、1 Token额度，得到累计1次/0次调用及明确失败，均无完成助手消息入库，跨账号读取404。
- `budget-audit.json`、`source-regression.json`、`usage-verification.json`：三条既有开发题各一轮，队伍预览未经批准，快照未改；预算、来源及usage检查各3/3，不替代独立人工质量验收。

恢复连续性、缺失/旧快照、并发、迟到响应与原生超额由固定仓储/SDK测试验证；真实PostgreSQL、多实例及完整账单未验收。原始事件、全部模型回答和日志保留于`artifacts/campus/s4-task/`、`artifacts/s4-task-*.log`。归档不包含API Key、密码、恢复码或Cookie。额度占用不等于供应商用量或计费金额。
