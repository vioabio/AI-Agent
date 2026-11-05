# 升级第 1 阶段：工程基线与运行边界

日期：2026-11-05。对应 [V2 开发阶段](AI_APPLICATION_UPGRADE_PLAN_V2.md) 的第 1 阶段。

本页保留第 1 阶段历史记录。当前迁移 head 与演示版本已在第 3 阶段更新，启动当前代码请以 [第 3 阶段说明](UPGRADE_STAGE_3.md) 为准。

本阶段完成本地代码改造并进行离线验证；没有连接真实数据库、手机或模型服务，没有发送消息、日报或执行远程部署，没有提交或推送代码。真实数据库迁移与安全处置需要单独记录，不能把本地验证视为生产验收。

## 1. 本轮改造

| 项目 | 实现与边界 |
| --- | --- |
| 80/90 阈值漂移 | 修正过期导出测试，覆盖 89/90 边界；不更改岗位匹配契约中的 80 分阈值 |
| 供应商文案 | CSV 列与动作提示统一为 AI；兼容供应商选择，不误称全部结果来自 Coze |
| 执行状态 | 新增共享 `AnalysisExecutionStatus`，与模型三态独立；适配器返回成功、临时异常、无效输出或请求阻断状态 |
| 缓存持久化 | 保存 `execution_status/error_code/retryable/retry_after`，读取与历史合并保留元数据；新字段使用显式 Alembic 迁移 |
| 报表一致性 | HR 查询、日报 SQL、PDF、飞书、CSV 与沟通资格使用共享状态/兼容判断；明确失败状态不能靠高分或改文案变成可行动结果 |
| 历史兼容 | 历史行的新字段保留 NULL，由集中适配器识别已知失败文案；不批量猜测历史成功状态，显式 `legacy_unknown` 禁止行动 |
| 重试窗口 | 新 `retry_after` 为 UTC 截止时间；有效临时窗口内的重复写入保留原截止时间，过期后的实际重试可开启新窗口；旧行沿用历史时间戳策略 |
| 调用预算 | OpenAI 兼容适配器增加默认 300 秒总时间预算，调用前/返回后检查，单次 SDK 超时取剩余额度与请求超时的较小值；无法在预算内继续退避时停止重试 |
| 认证错误 | 保留当前旋转网关的 401 重试兼容默认值，通过 `LLM_RETRY_AUTH_ERRORS=false` 可关闭；不可重试 HTTP 错误返回 blocked，不记录供应商原始错误正文 |
| Cookie 隔离 | demo 使用 `hr_demo_*`，真实入口默认 `hr_review_*`；登录、鉴权、CSRF 与退出均读取同一配置前缀，前端同步选择相应 CSRF Cookie |
| 演示版本 | 使用新的 `demo-v3.sqlite3`，保留旧 demo-v2 文件与审核结果；测试覆盖生产环境变量存在时仍只用本地 SQLite |
| 测试隔离 | pytest 禁止默认加载本地 `.env`，清除继承配置，拦截外部 DNS/TCP/UDP 和非 SQLite 数据库连接；只允许必要的回环事件循环通信 |
| CI | 新增 GitHub Actions：Windows/Python 后端离线测试与编译、前端 pnpm 锁定安装/typecheck/vitest/build；无需模型或生产数据库密钥 |
| 接口契约 | 动态 Cookie 名称由服务端配置读取；更新 OpenAPI 快照和生成的 TypeScript 类型，移除不再准确的固定 Cookie 参数描述 |

总时间预算用于控制请求准入、退避和拒绝超时后结果。SDK 网络超时不是强制终止线程的硬实时机制；严格 Worker 取消和过期提交保护在第 5 阶段实现。本阶段未修改 Coze SDK 的内部等待机制，不宣称两个 provider 已具备相同的总截止能力。

## 2. 兼容与配置变化

```dotenv
# 真实后端默认前缀；自定义时，真实前端 VITE_HR_COOKIE_PREFIX 与其一致。
HR_COOKIE_PREFIX=hr_review

# OpenAI 兼容调用；单位为秒。
LLM_TIMEOUT_SECONDS=240
LLM_TOTAL_TIMEOUT_SECONDS=300
# 当前网关兼容值；直接接普通上游时建议按实际认证行为设为 false。
LLM_RETRY_AUTH_ERRORS=true
```

demo 后端显式使用 `hr_demo`，前端设置 `VITE_HR_DEMO=true` 后默认读取 `hr_demo_csrf`。不应同时给 demo 前端设置真实 Cookie 前缀。两个服务不共享会话名，但 Cookie 名隔离不替代真实域名和 HTTPS 的安全边界。

CSV 表头从 `Coze状态/Coze结果/Coze分数/Coze原因` 改为 `AI状态/AI结果/AI分数/AI原因`，增加 `AI执行状态`。依赖旧中文列名的外部消费者需要同步映射；仓库内导出统计已同步，不回写旧 CSV 文件。

## 3. 真实数据库迁移顺序

新代码会读取新增字段。**已有真实数据库应先完成迁移，再启动新版本应用/采集/报表**。本次没有执行以下真实库命令。

1. 在目标环境确认 HR 专用连接指向预期数据库，备份数据库并停止相关写入任务；不要把生产连接复制到演示或测试环境。
2. 在该环境运行 `python -m alembic current`，核对当前版本、已有基础表与本地迁移历史。旧采集基础表早于 Alembic；如果此前未纳入迁移，先核对既有 HR 表与历史 revision，不能盲目 `stamp` 或直接重建。
3. 审核增量迁移 `migrations/versions/20261105_03_analysis_execution.py`。它在存在分析缓存表时增加四个可空字段，不批量覆盖历史行；HR-only 库不存在此表时跳过，由后续建表 metadata 提供新字段。
4. 历史版本确认无误后，在目标环境运行 `python -m alembic upgrade head`，检查版本到 `20261105_03`，并核对四个新增字段。
5. 用隔离样本确认成功、异常、高分失败与历史 NULL 行的查询表现，再启动新应用；继续保持原有外部行动限制。

SQLAlchemy metadata 创建全新演示数据库时已包含新字段。旧采集库在缺少字段时会提示显式迁移，不为这四个新字段自动 `ALTER TABLE`。

回退优先切回旧代码并保留新增列。若确需删除列，应先停止新写入、备份执行状态数据，再评审 `alembic downgrade 20260818_02`；该操作会丢弃四个新增字段的数据，不应作为常规应用回退的第一步。

本地已用临时 SQLite 验证迁移升级、历史内容保留和降级，并离线渲染 MySQL 增量 DDL。**未在隔离 MySQL 实例或真实库执行迁移，也未验证 MySQL 运行时 upsert 与并发行为。**

## 4. 验证方式

```powershell
.\.venv\Scripts\python.exe -m pytest
.\.venv\Scripts\python.exe -m compileall -q src apps scripts tests
git diff --check

# web/hr-review 内
npm.cmd run typecheck
npm.cmd test
npm.cmd run build
```

本机既有 node_modules 使用 npm 调用脚本；CI 与新安装使用仓库的 `pnpm-lock.yaml` 和冻结安装，未引入 package-lock.json。

前端测试曾在前后端同时运行、默认多 Worker 时出现 5 秒超时；限制 Vitest 为 2 个 Worker 后全量通过，没有放宽业务断言或测试超时。前端构建曾因系统临时目录中 esbuild 文件删除权限失败，改用工作区内独立临时目录后成功；没有修改系统执行策略或删除系统临时文件。产物单 chunk 约 1.28 MB 的警告仍存在，代码分割留待展示性能优化。

最终测试计数与运行结果统一记录在 V2 文档第 12.5 节，避免把中间轮次当成最终结果。远程 CI 工作流尚未推送执行；全新机器依赖安装尚未实测，现有虚拟环境与临时演示隔离测试不能代替干净 clone 验收。

## 5. 工作区与剩余事项

开始时已有 10 个跟踪文件修改及多项演示/部署未跟踪文件。本轮保留这些成果，增量修改其中的 README、API 配置/runtime、pyproject、演示脚本和本地运行说明；没有删除 `tmp/hr-publish`、批量提交或覆盖旧项目副本。新代码、迁移和测试仍在本地工作区，提交时应按功能审阅。

本次安全检查仅输出配置键是否有值与 URL 是否为 HTTP，未输出密钥或完整连接地址。确认 `.env` 被忽略、当前不受 Git 跟踪，当前本地 Git 历史查询也未发现 `.env` 的提交记录；这不能证明凭据从未通过其他渠道泄露。

确认配置中仍有数据库、模型、机器人、PDF 上传相关敏感值，且 PDF 公网地址是明文 HTTP。**尚未轮换凭据或把既有 PDF 服务改成 HTTPS/受控链接**，也没有验证账号权限和服务端访问控制。它们仍是生产验收遗留项，不阻碍离线评测开发，但不能标记为安全问题已关闭。

第 2 阶段已开始，保存响应的回放运行器与同集描述性比较已实跑；说明见 [eval/README.md](../eval/README.md)。仍需人工开发集、固定测试划分、真实调用事件及推理版本绑定。现有虚构 demo 数据不自动充当人工标注黄金集；未经人工核验和固定划分，不给出准确率提升结论。
