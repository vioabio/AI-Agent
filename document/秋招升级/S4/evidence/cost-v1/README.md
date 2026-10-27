# 价格版本与参考费用证据

- `verification-summary.json`：1004项后端测试、127项前端契约、49项工具测试、7项浏览器检查；初始SSE协议失败、最终验证范围、未完成事项及文件摘要。
- `runtime-identity.json`：467个源码、39个加载class摘要与当前模型/预算/价格策略；配置身份不证明供应商部署版本。
- `source-regression.json`、`usage-verification.json`、`budget-audit.json`、`cost-audit.json`：三条既有开发题各一轮。真实DashScope/qwen-plus未启用价格目录，费用明确未知；来源、usage、预算和费用状态核对各3/3。队伍预览未批准，快照未改。
- `fixture-calculation.json`：测试价格的USD 0.00094计算口径，推理计数不重复相加；HTTP/SSE、Run/SQLite和浏览器均为受控fixture，不能当作真实供应商费用。
- `pricing-references.json`：2026-10-24核对的官方字段/价格链接与本地示例有效窗口；没有自动按当前配置选择地区/模式或宣称账单核对通过。

原始模型输出、全部事件和日志保留于`artifacts/campus/s4-cost/`及`artifacts/s4-cost-*.log`，原SSE失败未覆盖。独立人工评审、保留集比较、实际账单、工具费用和SDK内部HTTP重试仍未验收/未知。归档不包含模型密钥、密码、恢复码或Cookie。
