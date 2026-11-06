# AI 招聘自动化与候选人审核平台

面向企业招聘场景，将 **岗位搜索、简历采集、AI 匹配分析、候选人管理和人工审核** 串联起来，帮助招聘人员减少重复检索与信息整理，把精力集中在候选人判断和沟通上。

系统通过安卓手机操作猎聘 App 获取简历，使用大模型按岗位要求输出匹配结论、评分和证据，再由 HR 在 Web 工作台完成筛选、审核与进度跟踪。

> 项目定位是可配置的 AI 招聘平台。当前手机采集任务面向 AI 前沿部署工程师（FDE）与 AI 开发工程师；仓库保留了部分历史岗位配置，Web 工作台与本地演示目前仍沿用历史岗位目录，尚未与这两个 AI 岗位全面统一。

## 核心能力

| 模块 | 实现能力 |
| --- | --- |
| 岗位任务配置 | 通过 YAML 配置岗位要求、关键词目录、提示词及评分规则 |
| 手机端采集 | 页面识别、关键词搜索、结果卡片筛选、详情展开与滚动采集 |
| AI 匹配分析 | 岗位独立提示词、结构化输出、匹配结论、分项评分及简历证据 |
| 候选人去重 | 候选人指纹、简历正文哈希、分析版本缓存和跨批次冷却 |
| 任务恢复 | 关键词级断点、运行锁、批次状态与异常停止 |
| HR 工作台 | 候选人筛选、简历详情、审核反馈、负责人及招聘进度看板 |
| 权限与审计 | 角色和岗位权限、Argon2 密码哈希、Cookie 会话、CSRF 校验、审核事件记录 |
| 审核一致性 | revision 乐观锁与事务提交，保留 AI 分析事实和人工审核历史 |
| 报告生成 | 基于数据库生成候选人 PDF 与日报，支持独立配置飞书投递 |

手机工作流还支持对满足条件的候选人进行沟通、保存正式分享链接或收藏。是否执行取决于岗位规则、去重结果及运行参数；启动 Web 工作台不会启动手机采集或发送日报。

## 业务流程

```mermaid
flowchart LR
    A[岗位配置与关键词] --> B[手机搜索与简历采集]
    B --> C[规则过滤与身份去重]
    C --> D[岗位独立 AI 分析]
    D --> E[MySQL 持久化]
    E --> F[HR 筛选与人工审核]
    E --> G[PDF 与日报]
    D --> H[满足条件后沟通]
    H --> E
```

AI 结果分为 `relevant`（匹配）、`needs_review`（待复核）和 `not_relevant`（不匹配）。模型异常或不确定结果进入待复核流程；自动沟通仅允许明确匹配且通过业务校验的候选人。

## 技术栈与设计

| 技术 | 解决的问题 |
| --- | --- |
| Python、ADB、uiautomator2 | 将重复的手机搜索、页面操作与简历采集转为可恢复的自动化流程 |
| OpenAI 兼容 API、Pydantic | 将简历与岗位要求转换为可校验的结构化分析，减少自由文本输出的不确定性 |
| YAML 岗位配置、独立提示词 | 分离岗位规则与流程代码，便于调整搜索策略和评分标准 |
| FastAPI、SQLAlchemy、MySQL | 提供候选人、权限、审核和统计接口，并持久化业务事实 |
| React、TypeScript、Ant Design | 构建类型明确的候选人审核界面与招聘进度展示 |
| TanStack Query | 管理服务端数据请求、缓存及审核后的界面刷新 |
| Argon2、CSRF、岗位权限 | 保护登录凭据、限制岗位访问并校验审核请求 |
| 乐观锁、事务、审核事件表 | 防止多人审核相互覆盖，保留可追溯的人工处置记录 |
| pytest、Vitest、Playwright | 验证规则解析、后端接口、前端交互和端到端审核流程 |

项目采用规则约束与 AI 分析结合的工作流：确定性条件由代码校验，语义匹配交给模型，人工决定最终招聘处置。

## 快速体验：独立演示环境

演示环境使用本地 SQLite 和虚构简历，不需要安卓手机、MySQL 或模型密钥。它适合体验页面与审核流程，不代替真实 MySQL 环境的兼容性验证。

### 1. 获取代码并安装依赖

推荐环境：Windows PowerShell、Python 3.12、Node.js 22.12+。

```powershell
git clone https://github.com/vioabio/HR-.git
cd HR-

py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"

Push-Location web\hr-review
npx.cmd --yes pnpm@10 install --frozen-lockfile
Pop-Location
```

已有虚拟环境时可直接安装依赖，无需重新创建。

### 2. 启动演示后端

在仓库根目录打开 PowerShell：

```powershell
.\scripts\start_hr_demo.ps1
```

若本机 PowerShell 执行策略阻止脚本，可直接运行：

```powershell
.\.venv\Scripts\python.exe -m scripts.run_hr_demo
```

### 3. 启动演示前端

另开一个 PowerShell，进入仓库根目录：

```powershell
.\scripts\start_hr_demo_web.ps1
```

同样可以在 `web/hr-review` 中直接启动：

```powershell
$env:HR_API_PROXY_TARGET="http://127.0.0.1:8001"
$env:VITE_HR_DEMO="true"
node.exe node_modules/vite/bin/vite.js --host localhost --port 5174 --strictPort
```

打开 **[演示工作台](http://localhost:5174/login)**：

- 用户名：`demo`
- 密码：`DemoReview2026!`
- 后端端口：`8001`

演示初始化后包含 99 份虚构简历、两个演示岗位与五个批次，覆盖 AI 结论、评分、沟通结果和审核进度。工作台沿用业务筛选规则，因此列表人数可能少于原始简历数。数据保存在 `artifacts/hr_demo/`；重复启动不会重复插入，也不会覆盖已完成的演示审核。

以上账号仅用于本机演示。请使用 `localhost:5174` 访问演示、`127.0.0.1:5173` 访问真实入口，以分开浏览器会话。

## 连接真实招聘数据

真实工作台使用独立配置项 `HR_REVIEW_DATABASE_URL`，不会自动回退到手机采集使用的 `MYSQL_*` 配置。

```powershell
# 仅首次创建；不要覆盖已有配置
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

在 `.env` 中填写自己的 HR 数据库连接：

```dotenv
HR_REVIEW_DATABASE_URL=mysql+pymysql://USER:URL_ENCODED_PASSWORD@HOST:3306/DATABASE?charset=utf8mb4
HR_COOKIE_SECURE=false
HR_ENABLE_OPENAPI=true
HR_ALLOWED_ORIGINS=http://127.0.0.1:5173,http://localhost:5173
```

该连接需要可用的候选人业务表、HR 相关表和登录账号。现有 Alembic 迁移是增量迁移，不能直接用来初始化任意空库；数据库准备、迁移和账号创建请先阅读 [HR 工作台运行手册](docs/HR_REVIEW_PORTAL_RUNBOOK.md)。本地开发和演示应使用隔离数据，连接真实业务库时应配置适当的账号权限。

分别在两个终端的仓库根目录执行：

```powershell
# 终端一：API，端口 8000
.\scripts\start_hr_real.ps1
```

```powershell
# 终端二：Web，端口 5173
.\scripts\start_hr_real_web.ps1
```

打开 **[真实工作台](http://127.0.0.1:5173/login)**，使用自行创建或已有的 HR 账号登录。审核操作会写入当前连接的数据库。开发环境接口文档位于 [API 文档](http://127.0.0.1:8000/docs)。

## 运行手机招聘任务

先准备已登录猎聘的安卓真机，开启 USB 调试，并在 `.env` 中配置设备、采集数据库及模型服务。配置项以 [.env.example](.env.example) 为准。

```powershell
adb devices -l
.\.venv\Scripts\python.exe scripts\device_diagnose.py
```

检查完成后，选择一个岗位运行：

```powershell
# AI 前沿部署工程师（FDE）
.\scripts\run_fde_search.ps1
```

```powershell
# AI 开发工程师
.\scripts\run_ai_development_search.ps1
```

同一手机只能运行一个采集实例。任务支持断点续跑；正常续跑不应清空 checkpoint。遇登录异常、验证码、账号风控或设备离线时应停止并人工处理。正式任务可能调用模型接口、写入数据库并向符合条件的候选人发起沟通。

日报是独立链路。相关运维细节见 [项目交接文档](docs/PROJECT_HANDOFF.md)，不要把历史岗位配置或旧文档中的规则直接套用到新岗位。

## 目录结构

```text
apps/hr_review_api/       FastAPI 接口、权限、查询与审核服务
src/liepin_rpa/           手机采集、AI 分析、去重及报告逻辑
web/hr-review/           React + TypeScript 审核工作台
config/job_profiles/     岗位要求、搜索目录和分析配置
config/prompts/          岗位提示词
scripts/                 启动、采集、诊断、演示及运维入口
migrations/              数据库增量迁移
openapi/                 接口契约
sql/                     SQL 文件
tests/                   单元、接口及集成测试
docs/                    运行手册和设计说明
```

`.env`、虚拟环境、数据库文件、真实简历、运行日志及本机登录信息不随源码发布。演示资料由脚本生成。

## 开发验证

在仓库根目录运行后端测试：

```powershell
.\.venv\Scripts\python.exe -m pytest
```

第 2 阶段已提供回放评测、42 条合成夹具生成、预算化模拟运行、调用事件、
推理版本校验、证据位置与配对统计工具；不调用真实模型、不代表真实质量结果，
见 [评测工具说明](eval/README.md)和[第 2 阶段交付记录](docs/UPGRADE_STAGE_2.md)。

测试默认禁用本地 `.env` 加载，清除继承的业务配置，拦截外部网络和非 SQLite
数据库连接。供应商调用使用替身，不启动手机或发送日报。CI 按同样方式执行后端
测试与前端 typecheck、vitest、build；工作流在 `.github/workflows/ci.yml`。

第 3 阶段已提供分析时正文快照和“查看原文”高亮，旧证据字符串继续兼容，
只为可核验的唯一原文引用显示链接。已有真实库在运行新代码前需显式升级到
`20261105_04`，见 [第 3 阶段实施与迁移说明](docs/UPGRADE_STAGE_3.md)。
本地演示使用 `demo-v4.sqlite3`，保留旧库，不需要真实数据库或模型密钥。

第 4 阶段增加详情页“按岗位查阅原文”：在当前分析快照中手动检索并高亮证据，
不修改评分。已提供全文/词法离线合成对照与混合检索组件；现有模型路径仍使用全文。
实现范围及实跑结果见 [第 4 阶段记录](docs/UPGRADE_STAGE_4.md)。

第 5 阶段新增持久化影子队列，并接通合成采集适配器、独立“影子分析任务”页、
鉴权状态/重试 API 和原文定位。默认关闭；现有手机采集和生产模型流程尚未切换。
核心队列、采集收据和 SDK 请求回执的当前迁移为 `20261105_07`；独立 v5 本地演示与
范围见 [第 5 阶段记录](docs/UPGRADE_STAGE_5.md)。

逐请求 SDK/预算适配可运行 `python -m eval.sdk_queue_demo`，使用真实 SDK 访问临时
本地假服务，验证请求数与持久化回执一致；没有模型推理或真实费用数据。

第 6 阶段提供四个只读工具、必跑配置检查和有限循环。运行
`python -m eval.bounded_tools_demo` 可验证合成控制流、引用及预算边界；当前为脚本
provider，未接入真实 Function Calling 或生产队列。见 [阶段 6 记录](docs/UPGRADE_STAGE_6.md)。

在 `web/hr-review` 目录运行前端检查：

```powershell
npx.cmd --yes pnpm@10 typecheck
npx.cmd --yes pnpm@10 test
npx.cmd --yes pnpm@10 build
```

端到端测试另需配置 Playwright 浏览器及测试服务，具体入口为 `pnpm test:e2e`。

## 文档

- [服务器部署与访问](docs/SERVER_DEPLOYMENT.md)
- [本机双入口启动说明](docs/HR_LOCAL_START.md)
- [HR 工作台运行手册](docs/HR_REVIEW_PORTAL_RUNBOOK.md)
- [项目交接与运行约束](docs/PROJECT_HANDOFF.md)
- [开发计划](DEVELOPMENT_PLAN.md)
- [AI 应用升级方案与开发阶段](docs/AI_APPLICATION_UPGRADE_PLAN_V2.md)
- [第 1 阶段实施、验证与迁移说明](docs/UPGRADE_STAGE_1.md)
- [第 3 阶段快照、原文高亮与迁移说明](docs/UPGRADE_STAGE_3.md)
- [第 4 阶段证据检索与合成对照](docs/UPGRADE_STAGE_4.md)
- [第 5 阶段持久化队列与故障验证](docs/UPGRADE_STAGE_5.md)
- [任务详情中的尝试、请求回执与预算展示](docs/UPGRADE_STAGE_5.md#7-第四轮任务尝试请求回执与预算展示)
- [第 6 阶段只读工具与有限编排](docs/UPGRADE_STAGE_6.md)
- [第 7 阶段统一本地验收与三分钟演示](docs/UPGRADE_STAGE_7.md)
- [AI/FDE 主工作台独立演示启动](docs/HR_LOCAL_START.md#aifde-主工作台演示v6)
- [剩余阶段补齐、队列 Worker、HR 投影与工具恢复](docs/UPGRADE_REMAINING_IMPLEMENTATION.md)
- [新环境、远程 CI 与隔离 MySQL 验收结果](docs/UPGRADE_ENVIRONMENT_ACCEPTANCE.md)
- [真实模型接入、质量评测与灰度逐步操作手册](docs/LIVE_VALIDATION_AND_ROLLOUT_RUNBOOK.md)
- [基于 recruiting-copilot 的猎聘与 BOSS 双平台接入方案](docs/DUAL_PLATFORM_COPILOT_INTEGRATION_PLAN.md)

在已安装依赖的本地环境执行 `.\.venv\Scripts\python.exe -m scripts.run_upgrade_acceptance --include-sdk --verify`，可生成并复核检索、队列、脚本工具、影子 API 和 SDK 本地假服务的独立合成验收包。已有包可用 `python -m scripts.verify_upgrade_acceptance <manifest路径> --require-sdk` 复核，加入 `--check-source` 可对比本机源码。该命令不调用真实模型，不代表生产发布或真实质量验收；CI 配置已接入，本地验证不能替代远程工作流运行记录。

前端采用页面/抽屉按需加载。构建后在 `web/hr-review` 执行 `npm.cmd run check:bundle` 可检查动态入口、单块 500000 字节及登录静态依赖 850000 字节门槛，报告写入 `artifacts/frontend/`；体积数据不代表实际首屏延迟。

部分交接材料记录的是历史运行状态，当前岗位及行为应同时核对配置文件与代码。
