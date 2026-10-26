# HR 候选人审核工作台运行手册

更新日期：2026-08-18

本手册只覆盖 HR 审核 API、Web 工作台、增量数据库迁移和离线验证。它不授权启动手机采集、调用 Coze、发送飞书日报，或连接、复制、修改生产数据。

## 1. 安全边界

开始前确认当前目录是专用开发工作树，并核对基线：

```powershell
git status --short
git log -1 --oneline
Test-Path src/liepin_rpa/candidate_decision_rules.py
Test-Path src/liepin_rpa/reporting_rules.py
```

本功能的开发基线应为：

```text
08bbd1d Finalize recruitment rules and HR review portal PRD
```

本地演示和测试必须遵守以下限制：

- 只使用隔离的开发/测试 MySQL；禁止把生产库 URL 写入 `HR_REVIEW_DATABASE_URL`。
- 必须显式设置 `HR_REVIEW_DATABASE_URL`。留空时应用会拒绝启动，不会回退到主项目的 `MYSQL_*` 配置。
- 不运行 `scripts/run_search_workflow.py`、`scripts/run_serial_job_queue.ps1` 或任何日报发送脚本。
- 不使用 `--no-resume`、`--reset-checkpoint`，不修改 checkpoint、运行锁或 run_state。
- 不配置或使用真实 `COZE_API_TOKEN`、`FEISHU_BOT_WEBHOOK`、`FEISHU_TEST_BOT_WEBHOOK`。
- API 启动不会自动迁移、建表、启动手机任务或发送日报；数据库迁移必须由明确的 Alembic 命令单独执行。

## 2. 本地依赖

在仓库根目录执行：

```powershell
.\scripts\rebuild_venv.ps1
.\.venv\Scripts\python.exe -m pip install -e .[dev]
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

如果 `.env` 已存在，保留并人工核对，不要用示例文件覆盖其中的本地配置。

前端使用 `pnpm`。首次准备依赖可能访问公共包仓库；严格离线环境应使用已经准备好的 `node_modules` 或内部镜像，不能因此改为调用任何业务外部服务。

```powershell
Push-Location web\hr-review
pnpm install --frozen-lockfile
Pop-Location
```

## 3. 准备隔离数据库

迁移 `20260818_01` 是增量迁移，不是空库初始化器。目标库必须已经包含当前项目的候选人主表及相关命中、分析和定位表，尤其是：

- `candidate_resume_experience_records`
- `candidate_resume_search_hits`
- `candidate_resume_analysis_cache`
- `candidate_resume_source_locators`

使用经过脱敏的开发数据或专用测试 fixture。不要从生产库临时导出候选人、简历、手机号或正式链接来做演示。

建议由 DBA 为同一个隔离 schema 准备两个账号：

1. 迁移账号：仅在迁移窗口拥有该开发 schema 的 DDL 权限；
2. Web 运行账号：只拥有候选人事实表的必要读取权限、候选人主表人工字段的更新权限，以及 HR 用户、会话、审核事件和敏感访问审计表的必要读写权限。

不要让 Web 运行账号拥有修改 AI 三态、评分、证据、分析版本或手机采集状态的权限。

在 `.env` 中显式配置本地 URL。密码中的特殊字符需要按 URL 规则编码：

```dotenv
APP_ENV=development
HR_REVIEW_DATABASE_URL=mysql+pymysql://hr_review_local:encoded-password@127.0.0.1:3306/hr_review_dev?charset=utf8mb4
HR_COOKIE_SECURE=false
HR_ENABLE_OPENAPI=true
HR_ALLOWED_ORIGINS=http://127.0.0.1:5173,http://localhost:5173
HR_BATCH_STATE_PATH=artifacts/search_workflow_batches.json
```

`HR_BATCH_STATE_PATH` 是只读的已完成批次状态文件。文件缺失时 `/api/v1/batches` 返回空列表，候选人、指标和日报查询会明确返回“没有可用批次”，不会退化为全历史查询；演示前应确认该路径指向正确的只读开发快照，并且不修改源文件。

## 4. 迁移与回滚验证

先用临时 SQLite 文件执行自动化的 upgrade/downgrade 测试；它不会连接 MySQL：

```powershell
.\.venv\Scripts\python.exe -m pytest tests\integration\test_hr_review_migration.py
```

随后只对隔离且可丢弃的开发 MySQL schema 执行真实方言验证：

```powershell
.\.venv\Scripts\python.exe -m alembic upgrade head
.\.venv\Scripts\python.exe -m alembic current
```

如果需要验证回滚，必须在创建本地账号和产生审核事件之前进行；`downgrade base` 会删除 HR 用户、会话和审计表：

```powershell
.\.venv\Scripts\python.exe -m alembic downgrade base
.\.venv\Scripts\python.exe -m alembic upgrade head
```

完成迁移后，把 `HR_REVIEW_DATABASE_URL` 切换为 Web 最小权限账号，再启动应用。生产库和包含真实业务数据的共享库绝不能用于回滚演练。

## 5. 创建本地账号

账号脚本交互读取密码，不把明文密码写入参数、文件或日志；密码至少 12 位。

```powershell
.\.venv\Scripts\python.exe scripts\create_hr_user.py reviewer "本地审核员" `
  --role hr `
  --job daren_live_bd_v1 `
  --job finance_shared_management_v1
```

只有确需查看正式猎聘链接的受控测试账号才增加：

```text
--can-view-sensitive
```

角色含义：

- `hr`：只能访问显式分配的岗位，可提交审核；
- `read_only`：只能访问显式分配的岗位，不能提交审核；
- `recruiting_lead`、`admin`：当前版本可访问两个已知岗位，并可把负责人指定为其他有效用户；
- `can_view_sensitive`：独立控制正式简历链接是否返回，不由角色自动获得。

岗位权限应按最小范围授予。姓名不是候选人主键；所有审核写入均通过 `candidate_fingerprint` 和 `revision` 完成。

## 6. 启动 API 与前端

终端一，在仓库根目录启动 API：

```powershell
.\.venv\Scripts\python.exe -m uvicorn apps.hr_review_api.main:create_app `
  --factory `
  --host 127.0.0.1 `
  --port 8000
```

开发环境可访问 `http://127.0.0.1:8000/docs`。生产环境必须关闭 OpenAPI，不能照搬开发配置。

终端二启动前端：

```powershell
Push-Location web\hr-review
pnpm dev -- --host 127.0.0.1
```

浏览器访问 `http://127.0.0.1:5173`。Vite 将 `/api` 代理到 `http://127.0.0.1:8000`。日报预览在抽屉打开时每 20 秒读取一次最新数据库状态；这是只读预览，不会发送日报，也不会修改已经发送的飞书卡片。

## 7. API 与安全行为

第一版接口统一使用 `/api/v1`：

- `POST /auth/login`、`POST /auth/logout`、`GET /me`
- `GET /batches`
- `GET /dashboard/summary`、`GET /reports/live-preview`
- `GET /review-queue`
- `GET /candidates/{candidate_fingerprint}`
- `PATCH /candidates/{candidate_fingerprint}/review`
- `GET /candidates/{candidate_fingerprint}/review-history`

会话 token 仅存放于 `HttpOnly` Cookie；前端可读的第二个 Cookie 只保存 CSRF 随机值，写请求必须同时携带 `X-CSRF-Token`。会话在服务端保存并过期，登录失败按用户名和客户端 IP 限制。正式链接只向具有独立敏感权限的用户返回，候选人详情访问和人工修改会写审计记录。

人工审核只修改人工状态、反馈、负责人、revision 和自动化抑制字段，不覆盖 AI 三态、评分、证据或分析版本。每次提交在同一事务中更新快照并追加历史事件；revision 不一致返回 HTTP 409。任何正式人工处置默认抑制后续自动沟通和可行动日报重复出现。

## 8. 离线验证命令

以下命令不需要手机，不调用 Coze，不连接飞书。API 测试和迁移测试使用测试内创建的临时 SQLite 数据库；Playwright 核心流程拦截并模拟所有 `/api/v1` 请求。

```powershell
# HR API 与迁移
.\.venv\Scripts\python.exe -m pytest tests\api tests\integration

# 主项目回归
.\.venv\Scripts\python.exe -m pytest

# Python 语法、格式和类型
.\.venv\Scripts\python.exe -m compileall -q src apps scripts tests
.\.venv\Scripts\python.exe -m ruff check .
.\.venv\Scripts\python.exe -m mypy apps\hr_review_api

# 唯一 OpenAPI 契约及前端类型
.\.venv\Scripts\python.exe scripts\export_hr_openapi.py
Push-Location web\hr-review
pnpm generate:api
pnpm typecheck
pnpm lint
pnpm test
pnpm build
pnpm test:e2e
Pop-Location

# Git 完整性
git diff --check
git status --short
```

`pnpm test:e2e` 使用本机 Chrome channel；如果机器没有可用 Chrome，应先在受控开发环境准备浏览器，而不是跳过核心流程。验证失败必须如实保留，不得删除、跳过或弱化测试。

## 9. 生产部署检查

本地演示通过不等于允许部署。生产上线至少还需要：

- `APP_ENV=production`、`HR_COOKIE_SECURE=true`、`HR_ENABLE_OPENAPI=false`；
- 由 Nginx/Caddy 提供 HTTPS，Uvicorn 只监听内网或回环地址；
- `HR_ALLOWED_ORIGINS` 只列实际 HTTPS 站点，`HR_COOKIE_DOMAIN` 只设必要域；
- 独立最小权限数据库账号，DDL 账号与 Web 账号分离；
- `.env`、数据库凭据和 Cookie 不进入版本控制或普通日志；
- 日志不记录完整简历、手机号、正式链接、反馈原文或密钥；
- 批次状态以只读方式提供，Web 服务账号无权启动手机采集或日报发送；
- 在授权的非生产环境完成真实 MySQL 迁移、权限隔离、并发冲突、审计表和备份恢复验收。

未经单独上线授权，本工作树只用于本地开发、离线测试和演示，不推送、不发布，也不连接任何生产或业务外部服务。
