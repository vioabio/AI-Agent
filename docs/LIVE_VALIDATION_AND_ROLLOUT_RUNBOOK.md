# 真实模型接入、质量评测与灰度操作手册

编写日期：2026-11-06。适用代码基线：`dd2e142` 及之后的兼容版本。

用途：操作者按顺序执行、勾选、填写结果。本文是操作计划，不表示真实调用、标注或生产发布已经完成。本次编写文档不会执行其中的外部请求、建库、迁移或设备操作。

当前已完成工程环境验收：后端 540 项、前端 42 项，独立 MySQL 和新环境 CI 均通过。证据见 [环境验收记录](UPGRADE_ENVIRONMENT_ACCEPTANCE.md)。本手册中的第 3/4/5 阶段是后续工作顺序，不替代 [V2](AI_APPLICATION_UPGRADE_PLAN_V2.md) 原有七阶段编号。

## 0. 先填写执行卡

所有“待填写”都是真正需要确认的值，不是可以直接粘贴的生产配置。密钥、原始简历和数据库密码不填进本文，也不提交 Git。

| 项目 | 填写值 |
| --- | --- |
| 操作者 / 复核者 | 待填写 |
| 执行日期、Git commit | 待填写 |
| 阶段 3 独立库 | `127.0.0.1:<端口>/hr_upgrade_test_<唯一后缀>` |
| 模型服务商 / HTTPS `/v1/` 地址 / 明确模型标识 | 待填写，不能沿用示例供应商假设 |
| 工具能力与核验出处 | supported / unsupported / unknown；附官方文档或实际协议记录 |
| 价格版本、核验日期、币种 | 待填写 |
| 每百万输入 / 输出 token 费率 | 待填写；微单位换算见下文 |
| 首轮批次预算与供应商侧限额 | 待填写，不设虚构价格 |
| 使用的岗位 | `ai_development_engineer_v1` / `fde_ai_deployment_engineer_v1` |
| 样本性质 / 授权 / 标注负责人 | 虚构 / 脱敏授权真实；待填写 |
| 当前阶段 | 3 待执行 / 4 未开始 / 5 未开始 |

执行约定：第 3 阶段先用虚构内容验证真实模型协议；第 4 阶段才引入已授权、脱敏、人工标注的真实简历；第 5 阶段才操作实际采集设备和目标环境。

## 1. 第 3 阶段：真实模型接入验收

### 3.1 建立独立工作目录与配置

- [ ] 另开 PowerShell，在新的目录克隆项目，不复用当前生产 `.env`。

```powershell
git clone https://github.com/vioabio/AI-HR.git D:\AI-HR-validation
cd D:\AI-HR-validation
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m pip check
git rev-parse HEAD
Test-Path .env   # 此独立目录预期为 False
```

前提是已安装 Python 3.12；前端另需 Node.js 22 和 pnpm 10（与当前 CI 一致）。若目录已经存在，使用另一个新目录；不要覆盖旧工作区。后续命令均在这个独立仓库根目录执行。首次运行保持同一个 PowerShell，进程环境变量不会自动传给另开终端。

- [ ] 由数据库管理员在独立 MySQL 实例创建新的空库及专用账号，仅授权这个测试库。不要把 `eval.mysql_queue_acceptance` 指向已初始化库，它要求空库且会运行迁移测试。
- [ ] 填写以下进程环境变量。`Read-Host` 的输入不出现在命令历史中；密钥只输入到隐藏提示。

```powershell
$env:APP_ENV="development"
$env:MYSQL_HOST="127.0.0.1"
$env:MYSQL_PORT=Read-Host "隔离 MySQL 端口"
$env:MYSQL_DATABASE=Read-Host "新空库名称，必须以 hr_upgrade_test_ 开头"
$env:MYSQL_USER=Read-Host "仅有测试库权限的账号"
$env:MYSQL_PASSWORD=[System.Net.NetworkCredential]::new("", (Read-Host "测试库密码" -AsSecureString)).Password
$env:RESUME_ANALYSIS_PROVIDER="openai_compatible"
$env:LLM_BASE_URL=Read-Host "已核验的 HTTPS /v1/ 地址"
$env:LLM_MODEL=Read-Host "明确模型标识"
$env:LLM_API_KEY=[System.Net.NetworkCredential]::new("", (Read-Host "模型 API Key" -AsSecureString)).Password
$env:FEISHU_BOT_WEBHOOK=""
$env:FEISHU_TEST_BOT_WEBHOOK=""
$env:HR_ENABLE_SHADOW_TASKS="false"
$env:HR_ENABLE_QUEUE_PROJECTION="false"
$env:LOG_PII="false"
```

本阶段只使用下文入队代码及 Worker，不启动手机采集，不使用日报/沟通命令。没有一个通用的“关闭全部消息”环境开关；禁止发送依靠选定入口和明确的 `--collect-only` 参数实现。

### 3.2 初始化测试库，明确迁移边界

- [ ] 确认上一节连接目标是新的测试空库，再执行下面的初始化代码。

```powershell
@'
from sqlalchemy import create_engine, inspect
from sqlalchemy.engine import URL
from alembic import command
from alembic.config import Config
from liepin_rpa.config import Settings
from eval.mysql_queue_acceptance import validate_database_url
from apps.hr_review_api.persistence import tables as hr_tables
from liepin_rpa.db import metadata
from liepin_rpa.analysis_job_schema import collected_inputs, tool_sessions, tool_rounds

s = Settings(_env_file=None)
url = URL.create("mysql+pymysql", username=s.mysql_user, password=s.mysql_password,
                 host=s.mysql_host, port=s.mysql_port, database=s.mysql_database)
validate_database_url(url)  # 只允许 loopback + hr_upgrade_test_*，拒绝其他目标
engine = create_engine(url, hide_parameters=True)
try:
    if inspect(engine).get_table_names():
        raise RuntimeError("只允许初始化全新空测试库")
    excluded = {collected_inputs.name, tool_sessions.name, tool_rounds.name}
    metadata.create_all(engine, tables=[t for t in metadata.sorted_tables if t.name not in excluded])
    config = Config("alembic.ini")
    config.set_main_option("sqlalchemy.url", url.render_as_string(hide_password=False).replace("%", "%%"))
    command.stamp(config, "20261105_07")
    command.upgrade(config, "20261105_08")
    print("独立测试库初始化完成；未连接模型或设备")
finally:
    engine.dispose()
'@ | .\.venv\Scripts\python.exe -
```

这是 **metadata 创建 07 基线，再真实迁移至 08** 的测试库准备方法；不是生产迁移方法，也不是从空库执行全部历史迁移。首个历史迁移依赖已有业务表。目标生产库的升级按第 5 阶段另行确认，禁止在既有库 stamp 07。

**本步记录**：库名 / 初始化日期 / 当前 revision / 操作者：________。

### 3.3 核验协议与计价，设置首轮预算

- [ ] 确认供应商接受当前 Chat Completions 兼容协议、模型标识、JSON 输出和工具帧；模型响应标识需要与冻结模型精确匹配。
- [ ] 记录输入/输出限制、响应 usage、缓存折扣与币种。尚未适配的缓存折扣不能计入预算节省。
- [ ] 工具能力未经确认时保持 unknown，不启动 supported 模式；不要通过无预算的自动探测反复试错。
- [ ] 在供应商控制台设置可接受的消费限额；同时填写程序预算：

```powershell
$env:LIVE_PRICE_VERSION=Read-Host "本轮冻结的价格版本，例如 provider-date-model-v1"
$env:LIVE_CURRENCY=Read-Host "币种，例如 USD 或 CNY"
$env:LIVE_INPUT_MICROS=Read-Host "每百万输入 token 的价格，单位为币种微单位"
$env:LIVE_OUTPUT_MICROS=Read-Host "每百万输出 token 的价格，单位为币种微单位"
$env:LIVE_BATCH_COST_MICROS=Read-Host "本批费用上限，单位为币种微单位"
$env:LIVE_STRATEGY="fulltext"
$env:LIVE_BATCH_ID=Read-Host "本次唯一批次标识，例如 live-ft-日期-01"
$env:LIVE_AS_OF_MONTH=(Get-Date).ToString("yyyy.MM")
```

换算：1 币种单位 = 1,000,000 微单位。假设某价格是每百万 token **P** 个币种单位，参数填 `ceil(P × 1,000,000)`；这是换算公式，不是模型价格推荐。全文和工具比较必须使用同币种、可追溯费率。

首轮：单 Worker、一个样本，全文最多 1 次请求；工具最多 3 次请求。下面入队代码显式设置共享批次预算。仅设置 `--max-dispatches 1` 不等于工具模式只发一次 HTTP；它限制的是任务领取次数。

### 3.4 准备一份输入并冻结入队（不操作手机）

- [ ] 新建私有目录与输入文件：

```powershell
New-Item -ItemType Directory -Force artifacts/live_validation/private | Out-Null
$env:LIVE_INPUT="artifacts/live_validation/private/input.json"
```

将下面格式保存到 `$env:LIVE_INPUT`。这是虚构协议样本；先用它验证真实调用，不拿它计算真实准确率。使用真实简历时，正文和结构化事实必须来自同一份已授权原文，不能为了通过规则而补造学历或城市。

```json
{
  "sample_id": "fixture-ai-001",
  "candidate_fingerprint": "fixture-person-001",
  "candidate_name": "虚构协议样本",
  "job_profile_id": "ai_development_engineer_v1",
  "resume_record": {
    "experience": "【纯虚构协议样本】\n求职意向：上海。\n教育经历：统招本科。\n项目经历：独立开发 Python 检索问答服务并负责部署上线，完成接口和回归测试。",
    "sections": {"求职意向": ["上海"]},
    "education_experiences": [{"degree": "本科", "admission_type": "统招"}]
  }
}
```

- [ ] 执行一次入队，只写测试库，不向模型发请求：

```powershell
@'
import json, os
from pathlib import Path
import yaml
from sqlalchemy import create_engine
from sqlalchemy.engine import URL
from liepin_rpa.config import Settings
from eval.mysql_queue_acceptance import validate_database_url
from liepin_rpa.analysis_intake import CollectedAnalysisIntake, PROFILE_FILES, ROOT
from liepin_rpa.analysis_jobs import AnalysisJobQueue, QueueLimits, JobBudget
from liepin_rpa.queued_request_plan import QueuedRequestPlan
from liepin_rpa.analysis_versioning import content_hash

s = Settings(_env_file=None)
url = URL.create("mysql+pymysql", username=s.mysql_user, password=s.mysql_password,
                 host=s.mysql_host, port=s.mysql_port, database=s.mysql_database)
validate_database_url(url)
data = json.loads(Path(os.environ["LIVE_INPUT"]).read_text(encoding="utf-8-sig"))
strategy, batch = os.environ["LIVE_STRATEGY"], os.environ["LIVE_BATCH_ID"]
if strategy not in {"fulltext", "tools"}:
    raise ValueError("策略必须为 fulltext 或 tools")
profile = yaml.safe_load((ROOT / "config/job_profiles" / PROFILE_FILES[data["job_profile_id"]]).read_text(encoding="utf-8"))
plan = QueuedRequestPlan.configured(model=s.llm_model,
    system_prompt=(ROOT / profile["analysis_prompt_path"]).read_text(encoding="utf-8"),
    price_version=os.environ["LIVE_PRICE_VERSION"], currency=os.environ["LIVE_CURRENCY"],
    input_micro_per_million=int(os.environ["LIVE_INPUT_MICROS"]),
    output_micro_per_million=int(os.environ["LIVE_OUTPUT_MICROS"]),
    input_reserve=30000, max_output_tokens=1024, timeout_seconds=30)
rounds = 1 if strategy == "fulltext" else 3
budget = JobBudget(**plan.budget_fields(max_attempts=rounds))
cap = int(os.environ["LIVE_BATCH_COST_MICROS"])
if cap < budget.max_cost_micros:
    raise ValueError("批次上限不足以覆盖本样本的保守预留；先复核费率和预算")
engine = create_engine(url, hide_parameters=True)
try:
    queue = AnalysisJobQueue(engine, binding_source="gateway_input",
                            price_version=plan.price_version, currency=plan.currency)
    queue.configure(QueueLimits(max_in_flight=1, max_queued=10, requests_per_minute=3, tokens_per_minute=100000))
    queue.create_batch(batch, max_requests=rounds, max_tokens=budget.max_tokens, max_cost_micros=cap)
    record = {**data["resume_record"], "_analysis_strategy": strategy}
    decision = CollectedAnalysisIntake(queue).submit(batch_id=batch,
        candidate_fingerprint=data["candidate_fingerprint"], candidate_name=data["candidate_name"],
        job_profile_id=data["job_profile_id"], resume_record=record, request_plan=plan,
        pipeline_fingerprint=content_hash({"profile": profile, "plan": plan.fingerprint,
            "endpoint": s.llm_base_url, "strategy": strategy}), budget=budget, deadline_seconds=1800)
    if decision.state != "queued":
        raise ValueError("前置规则未通过：" + decision.reason_code)
    mapping = {"sample_id": data["sample_id"], "job_id": decision.job_id, "batch_id": batch,
               "strategy": strategy, "plan_hash": plan.fingerprint}
    target = Path("artifacts/live_validation/private") / (batch + "-mapping.json")
    target.write_text(json.dumps(mapping, indent=2), encoding="utf-8")
    print(json.dumps(mapping))
finally:
    engine.dispose()
'@ | .\.venv\Scripts\python.exe -
```

同一事实、计划与策略重复入队复用任务，不会自动重新调用；要进行独立实验，应冻结新的明确版本，不能手改已保存任务。首次配置的队列身份/限额不可覆盖；改费率、币种或全局并发前，规划独立测试库。批次标识只使用字母、数字、短横线或下划线。

这个直接入队路径仅验证协议与分析，不代替真实采集链路的全部年龄、身份、人工抑制和冷却检查；也没有创建 HR 原始采集 locator，所以本阶段 **不加 `--project-hr`**。

### 3.5 执行全文模式，检查实际结果

- [ ] 核对模型、费率与预算，确认愿意产生本次真实模型费用后执行：

```powershell
.\.venv\Scripts\python.exe -m scripts.run_analysis_worker `
  --execute-provider --price-version $env:LIVE_PRICE_VERSION --currency $env:LIVE_CURRENCY `
  --concurrency 1 --max-dispatches 1
```

- [ ] 保存 `dispatched/succeeded/failed_attempts/lease_lost` 输出。程序退出 0 不等于所有业务任务成功；必须检查任务状态与请求回执。
- [ ] 若刚入队就出现 `dispatched=0`，先核对 `available_at`、队列限流和冻结策略。既有 MySQL DATETIME 可能取整到下一秒；等数据库时间到达后重跑领取，不把 0 次派发算成功，也不要手改状态。
- [ ] 数据库客户端只读核对：`analysis_jobs` 的状态/错误码；`analysis_request_receipts` 的请求状态、输入/输出 usage、模型身份、耗时；`analysis_evidence_runs` 的契约与原文绑定。
- [ ] 核验三态、分项上限、总分、唯一原文引用；缺 usage 记 unknown，不写 0。估算费用不是供应商实际账单，另行对账。

本阶段 MySQL 的生产队列不能使用合成影子任务页或 `manage_analysis_queue` 的合成管理入口；保持 `HR_ENABLE_SHADOW_TASKS=false`。检查先通过受控数据库只读查询/导出进行。

输入预留采用 UTF-8 字节估算，不是供应商 tokenizer 的严格上界；已发请求的超额计费无法靠事后记账撤销。单请求 timeout 随计划冻结，工具还受总回合/逻辑截止时间限制，不能用环境里的同步重试参数推算队列行为。

### 3.6 执行工具模式与故障验收

- [ ] 保留全文的 mapping 和报告。将 `LIVE_STRATEGY` 改为 `tools`，使用新的 `LIVE_BATCH_ID`，重新执行 3.4 的入队代码，冻结新的工具任务。
- [ ] 官方资料与实际协议确认支持工具后执行；未经核验不把能力写成 supported：

```powershell
.\.venv\Scripts\python.exe -m scripts.run_analysis_worker `
  --execute-provider --price-version $env:LIVE_PRICE_VERSION --currency $env:LIVE_CURRENCY `
  --concurrency 1 --max-dispatches 1 --tools `
  --tool-capability supported --as-of-month $env:LIVE_AS_OF_MONTH
```

- [ ] 只读核对 `analysis_tool_sessions/analysis_tool_rounds`：回合数不超过 3，调用关联正确，每个新请求都先有共享预留；保存已收到响应和未知结果的区别。
- [ ] 先在本地夹具验证失败分支，不向真实供应商制造限流风暴：

```powershell
.\.venv\Scripts\python.exe -m eval.sdk_queue_demo
.\.venv\Scripts\python.exe -m eval.bounded_tools_demo
.\.venv\Scripts\python.exe -m pytest tests/unit/test_remaining_pipeline.py
```

- [ ] 在隔离库补做真实进程退出、租约过期与补偿检查。已开始但无响应的工具回合不得自动重发；已保存响应回放不应新增模型请求。当前无通用真实队列重试/回放 CLI，需受控调用现有队列/持久化工具接口或先补管理脚本，不能用直接 UPDATE 状态代替验收。
- [ ] 显式 unsupported 的回退在同一预算内检查；只有模型错误/400 不足以证明“不支持工具”。若真实服务不支持工具，记录“全文可用，工具暂缓”，不得写成工具路径已验收。

**第 3 阶段放行条件**：

| 检查 | 通过 / 失败 / 暂缓 | 证据路径、日期、复核人 |
| --- | --- | --- |
| 全文真实请求、冻结契约、原文证据 | 待填写 | 待填写 |
| 模型标识、usage、费率/币种与费用对账 | 待填写 | 待填写 |
| 工具能力、关联、逐回合预算、回放/不确定结果处理 | 待填写 | 待填写 |
| 超时、429、非法输出、退出恢复和投影补偿测试 | 待填写 | 区分夹具测试与真实环境测试 |
| 默认启用路径 / 暂缓能力及理由 | 待填写 | 待填写 |

产物：`artifacts/live_validation/private/<批次>-mapping.json`、私有请求/结果导出、脱敏适配报告。未通过的路径保持关闭。不要因为本阶段协议通过而填写准确率提升。

## 2. 第 4 阶段：真实质量评测

### 4.1 冻结标注规则与样本用途

- [ ] 先写三态判定标准、缺失证据如何判复核、每个评分维度需要的原文支持。不能把“没写出来”自动标为“不匹配”。
- [ ] 确定标注与复核负责人；边界样本双人复核，有分歧先裁决，不由模型生成黄金标签。
- [ ] 先完成 30–50 份开发样本；另留独立冻结测试样本。目标 150+ 是后续扩大规模的计划，不是当前完成数。
- [ ] 由同一个 `group_id` 绑定同一候选人的所有版本；同人、同原文、近重复不能泄漏到 dev/test 两侧。覆盖 AI/FDE、长简历、明确匹配/不匹配、证据不足和异常情况。

**标注规则版本 / 开发样本数 / 测试样本数 / 冻结日期**：________。

### 4.2 按现有工具格式准备数据集

私有数据保存到 `artifacts/eval/private/dataset.jsonl`，每行一个 JSON 对象。下面只是格式示例，替换全部正文/标签并由人工确认后才能使用：

```json
{"sample_id":"real-ai-001","group_id":"person-001","split":"dev","source_type":"authorized_real","annotator_id":"annotator-01","job_profile_id":"ai_development_engineer_v1","resume_text":"待替换的授权脱敏原文","gold":"needs_review"}
```

- [ ] `gold` 只能取 `relevant/needs_review/not_relevant`；可选人工确认的 `score_interval` 和 `gold_evidence`。
- [ ] 证据范围使用原文 Unicode 码点半开区间 `[start,end)`，不能使用 JS UTF-16 下标；换行/脱敏替换完成后再标注位置。
- [ ] 开发与测试放在同一完整数据清单中，通过 `split` 选择，工具才能检查跨集合泄漏。不要分别裁剪后绕过检查。
- [ ] 保存数据摘要、标注协议、排除规则和授权记录；正文与标注人身份不上传 Git。

### 4.3 生成真实预测，补齐导出适配

- [ ] 用第 3 阶段验证过的入口运行已标注样本，逐样本保存 `sample_id → job_id → analysis_run_id` 映射。当前示例入队只配置一个样本的额度，不能直接循环放入 50 份而不调整批次预算。
- [ ] 先补一个受控的导出适配脚本：从映射读取具体 job/run，导出当前实验的保存结果与实际调用事件，不靠候选人“最新结果”反查历史实验。
- [ ] 导出 `predictions-fulltext.jsonl`：每行包含 `sample_id`、真实 `execution_status` 及六字段 `payload`（或保存的 `raw_response`）。若从 `analysis_evidence_runs.result_json` 构造，去掉非 payload 字段，并按三态补齐派生的 `beauty_relevant`，再次校验契约。
- [ ] 每个失败/超时样本也保留一行及真实状态。缺失或非法输出进入复核并留在分母，不能只导出成功任务。
- [ ] 分开导出回执的实际 latency/usage、工具回合、重试、保守占用、估算费用和价格版本。缺失 usage 保持 unknown；不能把回放耗时当成在线延迟。

**当前能力边界**：`eval.run_eval` 是保存响应的离线评估，不负责请求真实模型，也没有现成的生产队列到真实评测文件的一键导出命令。上述适配是进入批量真实评测前的明确待开发项；不要把 `eval.simulate` 的合成预测当成真实模型结果。

### 4.4 跑全文基线和逐项对照

准备真实导出后执行：

```powershell
.\.venv\Scripts\python.exe -m eval.run_eval `
  --dataset artifacts/eval/private/dataset.jsonl `
  --predictions artifacts/eval/private/predictions-fulltext.jsonl --split dev
```

- [ ] 保存命令输出中的真实报告路径，记录模型、prompt、岗位、契约、数据集、源码和价格版本。
- [ ] 同集比较 `全文 → BM25 → 混合检索 → 工具模式`，每次主要变动一个因素，固定同一批样本和评分契约。
- [ ] BM25 目前有只读检索/离线规划，不等于已经把检索上下文接到真实评分请求；混合检索还需要实际 embedding、索引版本与失效适配。先补真实请求上下文接线及导出，再跑这两项对照；可以明确暂缓，不填收益数字。
- [ ] 比较两个实际报告，替换以下路径为刚产生的文件，不照抄示意文件名：

```powershell
$fullReport=Read-Host "全文报告 JSON 的实际路径"
$variantReport=Read-Host "对照报告 JSON 的实际路径"
.\.venv\Scripts\python.exe -m eval.compare_runs `
  $fullReport $variantReport `
  --output artifacts/eval/private/comparison.json --iterations 500 --seed 2026
```

报告应包含：三态混淆矩阵、匹配精确率/召回率、合格误拒、复核比例、执行失败、按岗位分层、证据位置有效率，以及另行汇总的总费用和 p50/p95 延迟。语义证据支持需要人工核验；样本数不足的区间不作为可靠收益证明。

### 4.5 锁定门槛，运行冻结测试集

先在下表填写门槛并签字，再看冻结测试结果。数字由业务风险、人工容量和开发集结果决定，不预填虚构达标值。

| 指标 | 预先约定门槛 | 实测分子/分母或数值 | 结果 |
| --- | --- | --- | --- |
| 匹配精确率、合格误拒率 | 待填写 | 待填写 | 待填写 |
| 复核比例与人工处理能力 | 待填写 | 待填写 | 待填写 |
| 执行失败率、证据位置/语义支持 | 待填写 | 待填写 | 待填写 |
| 每样本及整批费用、p95 延迟 | 待填写 | 待填写 | 待填写 |
| 相对全文基线的收益与不确定性 | 待填写 | 待填写 | 待填写 |

- [ ] 冻结所选配置，使用对应预测文件运行 `eval.run_eval ... --split test`。
- [ ] 测试未达标时保留失败记录；若回到开发集修改，下一轮应使用新的独立测试集，不反复用旧测试集调参。
- [ ] 决定默认路径。没有可测收益的检索/工具保持可选；全文也可以是最终默认方案。

工具报告的 `quality_claim_allowed=false` 不自动改成 true。工具会标记未认证的真实记录来源；真实发布结论需要人工核验映射、授权、独立集合和实际结果，不能靠手动改标志获得。

**第 4 阶段放行记录**：数据/配置/报告路径：________；默认策略与理由：________；复核者/日期：________。

## 3. 第 5 阶段：小范围灰度与运维

### 5.1 准备目标环境和迁移

- [ ] 明确本次灰度环境、数据范围、负责人、值守时段和停用条件；首轮建议只选一个岗位和少量候选人，批量由人工可处理能力决定。
- [ ] 轮换已有敏感凭据，分开管理采集/Worker、HR、迁移、备份账号；生产账号只授予必要权限。
- [ ] 核验 PDF HTTPS/受控访问、会话 HTTPS、岗位授权与脱敏日志；演示账号不用于生产。
- [ ] 备份并在独立环境恢复，记录备份标识、恢复耗时与可接受的恢复点。
- [ ] 检查目标库实际 schema 和 Alembic revision。只有 revision 与物理表一致才执行所需升级；已有表但没有迁移记录时先制定接管方案，不能盲目 stamp 或重复 create_all。
- [ ] 在预发布验证迁移后，再停消费者、备份目标库、执行已复核的迁移计划。不要将第 3 阶段空测试库初始化代码用于此处。

迁移常用入口（在确认 `HR_REVIEW_DATABASE_URL` 指向目标环境的独立终端中执行）：

```powershell
$env:HR_REVIEW_DATABASE_URL=[System.Net.NetworkCredential]::new("", (Read-Host "目标环境 HR/迁移专用连接 URL" -AsSecureString)).Password
.\.venv\Scripts\python.exe -m alembic current
.\.venv\Scripts\python.exe -m alembic history
# 仅在迁移计划确认之后执行目标 revision，不自行 stamp：
.\.venv\Scripts\python.exe -m alembic upgrade 20261105_08
```

### 5.2 小批量真实采集入队，保留人工审核

- [ ] 配置并确认设备、当前账号、岗位 YAML、独立 checkpoint 与批次身份。当前岗位文件：`config/job_profiles/ai_development_engineer.yaml` 或 `fde_ai_deployment_engineer_draft.yaml`。
- [ ] 设 `WORKFLOW_SELECTION_MODE=jd_smart`、`WORKFLOW_JOB_PROFILE_PATH` 为对应文件，初始 `WORKFLOW_MAX_PROFILES_PER_KEYWORD` 使用小值，记录采集上限。

```powershell
$env:WORKFLOW_SELECTION_MODE="jd_smart"
$env:WORKFLOW_JOB_PROFILE_PATH="config/job_profiles/ai_development_engineer.yaml"
$env:WORKFLOW_MAX_PROFILES_PER_KEYWORD="1"
```

- [ ] **先处理预算接入缺口，否则停在本步**：采集 dispatcher 使用默认不可变队列限额和默认批次预算，不读取第 3 阶段的 `LIVE_BATCH_COST_MICROS`；生产采集没有通用批次费用覆盖参数。必须先补齐或审查专用队列/批次预置机制，在预发布证明费率/额度一致；不能把第 3 阶段的低限额队列原地交给生产采集，限额不匹配会被拒绝。
- [ ] 下列命令会实际操作手机并写库，只有进入本阶段后执行：

```powershell
.\.venv\Scripts\python.exe -m scripts.run_search_workflow `
  --standalone-job --collect-only --queue-analysis --limit 1 `
  --checkpoint-file artifacts/live_validation/gray-checkpoint.json `
  --queue-price-version $env:LIVE_PRICE_VERSION --queue-currency $env:LIVE_CURRENCY `
  --queue-input-price-micros $env:LIVE_INPUT_MICROS `
  --queue-output-price-micros $env:LIVE_OUTPUT_MICROS `
  --queue-input-reserve 30000 --queue-output-limit 1024 `
  --queue-strategy fulltext --queue-concurrency-limit 1
```

`--limit 1` 是一个关键词任务，不是一个候选人；候选人上限由采集配置控制。异步入队使用 `--collect-only`；不要叠加 `--no-send-message/--dry-run`，后两者不写库且与 collect-only 互斥。不要添加 `--send-report`。

### 5.3 启动 Worker 与 HR 当前结果投影

- [ ] 采集/Worker 与 HR 指向同一物理 schema，但使用各自独立账号。原采集 locator 和批次状态必须存在；不能靠补造 locator 把手工协议样本冒充采集结果。
- [ ] 执行已通过第 3/4 阶段的策略。以下全文命令会实际调用模型、写当前结果并投影到 HR：

```powershell
.\.venv\Scripts\python.exe -m scripts.run_analysis_worker `
  --execute-provider --price-version $env:LIVE_PRICE_VERSION --currency $env:LIVE_CURRENCY `
  --concurrency 1 --max-dispatches 1 --project-hr
```

- [ ] HR 显式设置专用 `HR_REVIEW_DATABASE_URL`、`HR_ENABLE_QUEUE_PROJECTION=true`、实际批次状态文件 `HR_BATCH_STATE_PATH`；MySQL 上仍设 `HR_ENABLE_SHADOW_TASKS=false`。需要真实队列 Web 运维页时，先开发支持 gateway_input/MySQL 的鉴权入口，不能开启合成页替代。
- [ ] 用 `scripts.create_hr_user --help` 查看创建账号参数；先分配岗位权限，再启动 HR API 和前端：

```powershell
$env:HR_ENABLE_QUEUE_PROJECTION="true"
$env:HR_ENABLE_SHADOW_TASKS="false"
$env:HR_COOKIE_PREFIX="hr_review"
$env:HR_BATCH_STATE_PATH=Read-Host "本轮真实采集批次状态 JSON 文件路径"
.\.venv\Scripts\python.exe -m scripts.create_hr_user --help
.\.venv\Scripts\python.exe -m uvicorn apps.hr_review_api.main:create_app --factory --host 127.0.0.1 --port 8000
```

本机/预发布页面核对时，API 显式设置 `HR_COOKIE_PREFIX=hr_review`；另开已安装前端依赖的终端：

```powershell
cd D:\AI-HR-validation\web\hr-review
$env:HR_API_PROXY_TARGET="http://127.0.0.1:8000"
$env:VITE_HR_DEMO="false"
$env:VITE_HR_COOKIE_PREFIX="hr_review"
node.exe node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort
```

打开 `http://127.0.0.1:5173/login`，使用刚创建并授权的 HR 账号。前端未安装依赖时先 `pnpm install --frozen-lockfile`。公网使用构建后的前端和 HTTPS 反向代理，不直接暴露 Vite 开发服务；实际 Cookie 前缀、Secure 与允许来源需按部署环境一致配置。

迁移完成后 HR API 使用自己的低权限连接，不继续使用拥有 DDL 权限的迁移账号。连接 URL 中密码的特殊字符需要正确 URL 编码；不要打印完整 URL 验证配置。

- [ ] HR 人工逐条核对身份、岗位、原文、结论和证据；验证人工反馈、revision、负责人、抑制标志及审计事件。
- [ ] 新任务替代旧结果时，未完成期间旧队列结论可能暂时不展示/详情 404；核对这是预期的当前结果过滤，不把旧结论重新发布。

### 5.4 观测、扩容与恢复演练

- [ ] 每批记录采集完成与分析完成，不能把“采集结束”当“全部分析成功”。观察积压数/最老等待时长、失败分类、请求/工具回合数、usage 未知数、保守占用、费用估算、排队与模型延迟、投影失败及人工复核负担。
- [ ] 告警阈值和通知接收人由操作者填写。当前没有已完成的生产监控告警平台，需先接入监控或建立明确的人工巡检机制，不把数据库字段本身当作已上线告警。
- [ ] 逐批增加样本，确认稳定后才考虑 2/4 并发。全局队列限额不能靠 Worker 的 `--concurrency` 自动变更，需独立、已验收的限额变更方案。
- [ ] 在预发布演练 Worker 退出/租约恢复、重复提交、旧 token 拒绝、投影失败与补偿；生产仅安排可控的恢复演练。
- [ ] 仅补偿投影时使用以下命令，不创建模型客户端；当前命令会遍历成功任务，应预先确认范围并检查输出：

```powershell
.\.venv\Scripts\python.exe -m scripts.run_analysis_worker `
  --projection-only --price-version $env:LIVE_PRICE_VERSION --currency $env:LIVE_CURRENCY
```

### 5.5 停用与放行

出现预先定义的费用、错误、身份或权限异常时：停止新采集和新派发，记录在途请求；等待受控超时/恢复或人工处置，不能假设进程退出就撤销供应商请求。必要时关闭队列投影视图并按已验证版本回退应用，保留所有账本、快照和人工审计。

不得删除数据库来回退，也不得直接降级 08 丢弃工具/输入账本。停异步后不要自动切回可能发消息的旧同步采集路径；重新启用任何路径前都确认 `--collect-only` 和预算。

| 灰度检查 | 预先门槛 | 实测与证据 | 放行人/日期 |
| --- | --- | --- | --- |
| 身份/权限、人工字段和审计 | 待填写 | 待填写 | 待填写 |
| 队列恢复、重复提交、旧版本保护 | 待填写 | 待填写 | 待填写 |
| 费用、延迟、积压与失败 | 待填写 | 待填写 | 待填写 |
| 备份恢复、投影补偿与停用 | 待填写 | 待填写 | 待填写 |
| 默认策略与下一批规模 | 待填写 | 待填写 | 待填写 |

首轮异步链路只产出 HR 待办。自动沟通仍是另一个需要设备串行调度、候选人重新定位、身份和人工抑制复核的独立阶段，不随灰度自动开启。

## 4. 操作日志与最终交付

每做完一步复制一行填写，失败也保留，不覆盖之前结果。仓库中的模板只记录脱敏摘要；私有完整记录留在 `artifacts/live_validation/private/` 或受控外部目录。

| 日期 | 步骤编号 | commit / 批次 / 配置版本 | 执行结果 | 脱敏证据路径 | 下一步 / 负责人 |
| --- | --- | --- | --- | --- | --- |
| 待填写 | 3.1 | 待填写 | 未执行 | 待填写 | 待填写 |

阶段交付清单：

- [ ] 阶段 3：供应商协议/能力记录、调用与回合报告、实际 usage/费用对账、异常与恢复结果。
- [ ] 阶段 4：授权和标注协议、开发/冻结集合、真实预测映射、混淆矩阵/对照/错误案例、默认策略。
- [ ] 阶段 5：部署与迁移记录、预算/监控方案、灰度运行报告、备份恢复/停用手册、放行签字。

当前明确需要补齐的工程入口：真实队列到评测预测/事件导出、真实检索上下文接线、MySQL/gateway 队列管理与受控重试、生产批次预算/限额变更、生产监控告警。它们不影响已完成的合成与环境验收，但必须按上述步骤处理后，才能宣称真实质量或生产可运维。
