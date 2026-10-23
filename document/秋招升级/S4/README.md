# S4 首批：模型用量与耗时

本批接通每个 Run 的模型调用记录，让下一步上下文预算有实际数据依据。S4整体仍在开发：原有最近20条、12000字符历史窗口保持不变，尚无Token预算、摘要或真实费用计算。

## 接线与口径

```text
RunService创建执行尝试账本
  → AgentRunContext → DefaultRunExecutor → ObservedChatModel
  → 规划call、最终call/stream逐次观察
  → 校验供应商原始usage → 冻结快照
  → usage事件/终态modelUsage → 前端折叠详情
  → 完成回答metadata.modelUsageAttempts → 本地SQLite历史
```

`model-usage-v1`统计的是应用调用`ChatModel.call/stream`的次数，包含内部规划与最终回答。它不是工具调用数、流式分块数，也不证明SDK内部HTTP重试次数；后者明确为`UNKNOWN`。流式订阅才开始计数，单个流中累计usage替换此前值，不对每个分块相加。

当前原始字段适配两条已有链路：OpenAI兼容/DeepSeek的`OpenAiApi.Usage`和DashScope的`TokenUsage`。Spring AI的`DefaultUsage`会把缺失计数补为0，因此只信任native字段里实际存在且非负的输入、输出计数。总数与两项之和冲突、流式计数倒退或字段损坏均保持未知；其他供应商或无native字段的适配器也保持未知。协议参考：[DeepSeek Chat Completion](https://api-docs.deepseek.com/api/create-chat-completion/)，兼容解码由本地HTTP/SSE回归验证，不以mock代替真实供应商测试。

只有全部已开始调用成功返回、全部usage有效且未截断记录，才给出完整任务Token合计。失败或未返回的调用仍在分母中；已知部分单独记入`knownSubtotal`，不冒充整次任务。仅明确无需模型的审批确认保存路径可记录真实0次调用；未接入观察器的旧GameApp回退路径仍为未知。每个执行尝试最多保存64条明细，超限时保留已开始次数并取消完整合计资格。

配置型号与响应metadata返回的型号分别保留。配置型号不能证明实际部署版本；供应商未返回型号时不补写配置值作为证明。账本只保留受限格式的型号、数字和状态，不保存提示词、回答、认证信息、原始native对象或供应商错误正文。异常的可选usage元数据不会使原本有效的回答失败。

## 终态、审批和存储

每次物理执行尝试有独立账本。完成时写`usage`事件；失败、取消、超时在终态附上可取得的快照，仍在途的调用为未知。终态后账本冻结，迟到的响应不能改写数字或再发起模型调用。

等待审批退出时保存该次尝试的`usage`。既有`resumeWhenIdle`保证原worker完成清理后才恢复，新尝试不会覆盖旧尝试；最终历史合并先前事件和当前快照，按attemptId去重。当前任务的`usage`与终态重复携带同一快照时，前端只计算一次。

成功回答的元数据存于已有`.local-data/history.sqlite`（可配置`vio.local-history.path`），按账号、会话隔离；SQLite重新打开后可读。它不改变原有Run事件仓储策略：默认local的失败/取消记录仍受内存事件保留范围限制，不能承诺跨重启恢复全部失败任务用量。没有新增外部服务或数据库迁移。

## 用户界面与耗时

回答下方新增默认折叠的“模型用量与耗时”，支持1440px桌面和390px手机端。当前Run在任务面板中展示一次；发送下一条问题后，上一条消息保留用量；重新加载成功消息时从历史元数据恢复。复制消息只复制原回答。

耗时来自应用单调时钟，表示每次ChatModel调用开始至返回、流结束或冻结时刻的累计值，不能解释成服务端纯推理时间。只有真实SDK流的首个非空文本记录`firstTextDeltaMs`；工具回答的程序分段不算SDK首字。该值也不等同用户第一眼看到回答的延迟。原有Run总耗时、工具耗时和旧监控指标仍各自保留口径，不混合生成新的精确TTFT宣称。

费用固定为未知：本批没有计费价格版本、缓存计费拆分或账单核对，不按网上单价估算。本轮的Token总数不能直接兑换成实际金额。

## 可复现验证

```powershell
.\mvnw.cmd -o '-Dtest=ModelUsageLedgerTest,PokemonAgentRunServiceModelUsageTest,DeepSeekUsageProtocolTest' test
.\mvnw.cmd -o test
node --test tools/campus/*.test.mjs
cd vio-ai-agent-frontend
npm run test:contract
npm run lint
npm run typecheck
npm run build
$env:N5_E2E_BASE_URL='http://127.0.0.1:3186'
$env:N5_E2E_BROWSER_CHANNEL='msedge'
npx playwright test e2e/model-usage.spec.js e2e/pokemon-message.spec.js
```

执行后端Maven检查时不要并发启动另一个Maven编译。真实请求使用独立18134端口及独立accounts/history/teams/guides文件，启动命令与采集命令见[验证记录](验证记录_2026-10-23.md)。每次使用新结果目录，不覆盖失败证据。

## 下一批顺序

1. 修复本次发现的攻略重复检索回退；保持来源校验和副作用审批门禁，补充固定失败回归，不靠重复采样挑选成功结果。
2. 先给系统提示、当前问题、工具定义/结果、历史、队伍状态和输出分配Token预算；明确估算器、误差和保留字段。
3. 按账号/会话实施可验证裁剪，覆盖多轮用户限制与版本信息；摘要不能把模型猜测写成用户事实。
4. 增加任务模型调用/Token预算耗尽的终止与可读提示，并区分观测统计和执行前估算。
5. 加入价格版本、缓存/推理等计费字段，缺失时继续未知；补齐工具排队与执行时间、公共首字延迟的独立口径。
6. 固定版本后重复开发集与保留集评测，继续S2/S3独立人工审核，再进入S5交付材料。
