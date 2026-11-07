# 项目体检与「AI 应用开发工程师」升级路线

> 体检时间：本机 `D:\jianlizhuanqu`，分支 `master`，最后提交 `4bb71d2`。
> 本文所有结论均来自实际运行，不是推测；实测命令与输出见第 2、3 节。

---

## 1. 这个项目是什么

一套**面向招聘场景的 AI 自动化平台**，把「手机端简历采集 → 大模型结构化匹配 → MySQL 落库 → HR 网页审核」串成闭环。

规模事实（实测）：

| 维度 | 数值 |
| --- | --- |
| Python 业务代码 | 约 1.8 万行（`src/` + `apps/`） |
| 最大单文件 | `src/liepin_rpa/search_execution/result_filter.py` 2904 行 |
| 后端测试 | 235 个用例（229 个 `def test_`，含参数化） |
| 前端 | React 18 + TS + antd，17 个组件测试，1 个 Playwright E2E |
| Git 跟踪文件 | 249 个 |
| 数据库表 | 候选人、搜索命中、来源定位、分析缓存、报告历史、HR 用户/审核事件/会话/限流 |

架构是清晰的三层：

- **采集层** `src/liepin_rpa/`：ADB + uiautomator2 驱动安卓真机操作猎聘 App，页面识别 → 关键词搜索 → 卡片筛选 → 详情采集 → AI 分析 → 去重落库 →（可选）自动沟通。
- **分析层** `src/liepin_rpa/analysis_*.py` + `openai_compatible_analysis.py` + `coze_workflow.py`：供应商无关的分析契约，OpenAI 兼容接口与 Coze 工作流两个适配器。
- **应用层** `apps/hr_review_api/` + `web/hr-review/`：FastAPI + SQLAlchemy + React，做筛选、详情、审核、看板、权限与审计。

---

## 2. 怎么跑（三种路径，均已实测）

### 2.1 演示环境（推荐先跑这个，不碰真库、不碰手机）

演示环境用本地 SQLite + 虚构简历，不需要安卓手机、MySQL 或模型密钥。

```powershell
# 终端一：后端，端口 8001
.\.venv\Scripts\python.exe -m scripts.run_hr_demo

# 终端二：前端，端口 5174
cd web\hr-review
$env:HR_API_PROXY_TARGET = "http://127.0.0.1:8001"
$env:VITE_HR_DEMO = "true"
node.exe node_modules/vite/bin/vite.js --host localhost --port 5174 --strictPort
```

打开 <http://localhost:5174/login>，账号 `demo`，密码 `DemoReview2026!`（数据在 `artifacts/hr_demo/`，演示数据表 `demo-v2.sqlite3`）。

> **踩坑 1**：README 里写的 `.\scripts\start_hr_demo.ps1` 在本机会被 PowerShell 执行策略拦掉
> （`is not digitally signed`）。要么先 `Set-ExecutionPolicy -Scope Process Bypass`，
> 要么像上面这样直接调 `python -m scripts.run_hr_demo`。
>
> **踩坑 2**：`demo-v2.sqlite3` 已存在时，重复启动不会重复插数据，但也**不会**把旧的 3 条精简演示数据补齐成完整评分拆解——想看完整「评分拆解 / 关键证据」，要点后面那些 `演示周先生005` 之类的新增候选人。

已验证：登录 → `/api/v1/me` → `/api/v1/review-queue?scope=all` 返回 `total=48` → 看板渲染 7 个指标卡。截图见第 6 节。

### 2.2 真实数据库工作台

需要 `.env` 里配好 `HR_REVIEW_DATABASE_URL`（当前已配，指向真实 RDS），然后：

```powershell
.\.venv\Scripts\python.exe -m uvicorn apps.hr_review_api.main:create_app --factory --host 127.0.0.1 --port 8000
cd web\hr-review; node.exe node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort
```

<http://127.0.0.1:5173/login>，接口文档 <http://127.0.0.1:8000/docs>。

> 注意：真实入口和演示入口要用 `127.0.0.1:5173` 与 `localhost:5174` 两个不同域名，否则浏览器会话 Cookie 会互相覆盖。这是设计上的一个隐式约束，建议后面改成不同 cookie name 或子路径。

### 2.3 手机采集链路

```powershell
adb devices -l
.\.venv\Scripts\python.exe scripts\device_diagnose.py
.\scripts\run_fde_search.ps1          # 或 run_ai_development_search.ps1
```

同一台手机只能跑一个实例（`artifacts/search_workflow.lock`）。**不要**在搜索命令里夹带 `--send-report`，日报是独立链路。

> 当前 `.env` 的 `WORKFLOW_JOB_PROFILE_PATH=config/job_profiles/daren_live_bd.yaml`，仍指向历史岗位「达人营销」。要跑 AI 岗位需要显式改这个变量或走 `run_fde_search.ps1` / `run_ai_development_search.ps1`。

---

## 3. 现状体检结果

### 3.1 能跑的（实测通过）

| 检查项 | 结果 |
| --- | --- |
| 后端 `python -m pytest` | **234 / 235 通过，1 失败** |
| 前端 `tsc --noEmit` | 通过，0 错误 |
| 前端 `vitest run` | **17 / 17 通过**（9 个文件） |
| 前端 `vite build` | 通过，但产物单 chunk **1.28 MB**（gzip 404 KB） |
| 演示后端启动 | 通过，`/docs` 200 |
| 演示前端 + Playwright 登录 | 通过，渲染正常 |

### 3.2 唯一的失败用例（真 bug，要修）

```
tests/unit/test_search_results_export.py:229
test_export_labels_low_score_needs_review_as_below_review_threshold
AssertionError: 'Coze未达90分待复核线，未发送' != 'Coze未达80分待复核线，未发送'
```

判断：**是测试过期，不是代码错**。`reporting_rules.ACTIONABLE_NEEDS_REVIEW_MIN_SCORE = 90`，`DEVELOPMENT_PLAN.md` 第 2.3 节也写明 90，`test_resume_pdf_export.py` 里另有 `..._90_point_review_gate` 用例。这个测试是历史上「阈值降到 80」那次提交的残留。

修复：把 `test_search_results_export.py` 第 231、234、236 行（含 79/80 边界）改成 90 / 89 边界即可。

> 顺带说明：这个失败正好印证了 `DEVELOPMENT_PLAN.md` P2 里写的风险——「禁止在 result_filter、PDF、播报或导出脚本中另写一个裸 90 形成漂移」。现在 `search_results_export.py`、`feishu_report.py`、`resume_pdf_export.py` 三处都各自拼了 `ACTIONABLE_NEEDS_REVIEW_MIN_SCORE`，文本前缀还都硬编码了 `Coze`，而当前默认 provider 早已是 `openai_compatible`。**这是一个面向面试官会很尴尬的细节：用户看到的是「Coze 未达 90 分」，但实际调用的是 OpenAI 兼容接口。** 建议把供应商名称也抽成配置。

### 3.3 工程卫生问题

| 问题 | 影响 |
| --- | --- |
| 工作区有 **10 个未提交修改 + 12 个未跟踪文件**（`deploy/`、`run_hr_demo.py` 等整套部署与演示脚本都没进版本库） | 换机器就丢；面试时无法用「一次干净 clone 跑起来」证明 |
| `tmp/hr-publish/` 是一套 **2 MB / 268 文件的项目副本** | 混淆视线，容易改错文件 |
| 没有 CI（无 `.github/`、无 `.gitlab-ci.yml`） | 1 个红测试就这样躺进了主干 |
| `result_filter.py` 2904 行、`db_daily_report.py` 2030 行、`db.py` 1762 行 | 单文件认知负荷过高 |
| 前端单 bundle 1.28 MB，无代码分割 | 首屏慢，构建警告 |
| `.env` 里有**生产 RDS 密码、服务器 root 密码、飞书 webhook、Coze token、LLM key**，且 `PDF_PUBLIC_BASE_URL` 是明文 `http://` | `.env` 已 gitignore，但一旦误提交就是生产事故 |

---

## 4. 对「AI 应用开发工程师」岗位的差距诊断

这是本文最重要的一节。项目的**工程底子远超一般秋招项目**，但**AI 部分是「单次 LLM 调用 + 强校验」，而不是 2026 年 AI 应用岗默认期待的「RAG + Agent + 评测」**。

### 4.1 已经很能打的部分（面试可以直接讲）

1. **结构化输出契约**（`analysis_contract.py`）
   模型只允许输出严格 6 字段 JSON；`score_breakdown` 三维度上限由岗位配置注入（`25/15/60`、`30/20/50`、`35/25/40`），且**强制三项之和等于总分**。这是把 LLM 从「自由文本」变成「可校验数据」的教科书做法。

2. **三态 + 安全降级**（`candidate_decision_rules.py`、`analysis_contract.py`）
   `relevant / needs_review / not_relevant`。校验失败、API 异常、`beauty_relevant` 与 `match_result` 矛盾——全部降级为 `needs_review`，**绝不降级为 `not_relevant`**（宁可人工看，不可错杀）。自动沟通只认显式 `relevant`。

3. **可重试与临时异常窗口**（`openai_compatible_analysis.py`）
   4 次指数退避重试（`min(2**n,15)` + jitter），401/408/409/429/5xx 视为可重试；临时异常结果只缓存 10 分钟（`ANALYSIS_TRANSIENT_CACHE_TTL`），到期允许重试，且**不允许这个 10 分钟窗口被写库顺延**。

4. **多层去重**：候选人指纹 + `experience_hash` 正文哈希 + `analysis_version` 缓存键 + 跨批次 7 天冷却。姓名还是脱敏的（`陈**`、`李先生`），`candidate_identity.py` 里专门处理了复姓与脱敏名互认——这是很细的业务洞察。

5. **人工与 AI 事实分离**：AI 三态/评分/证据写独立字段，人工审核只写 `manual_*` 字段 + 不可删除的审核事件表；提交走 **revision 乐观锁 + 事务**，冲突返回 409。

6. **提示词工程有真东西**（`config/prompts/ai_development_engineer_v1.txt`，180 行）
   证据边界 12 条（不许跨公司拼接、技术名词≠真实使用、"了解/熟悉"≠"设计/编码"）、**证据强度系数**（只罗列 30% / 参与 60% / 负责 80% / 0→1 上线 100%）、分项封顶规则（"只有岗位名称最高 10""没有部署上线最高 30"）。这套东西比大多数简历上写的「Prompt Engineering」实在得多。

7. **供应商无关**：`analysis_gateway.py` 按 `RESUME_ANALYSIS_PROVIDER` 切 Coze / OpenAI 兼容，共用同一契约，还有主备工作流切换。

### 4.2 硬伤（招 AI 应用开发时一定会被问到的空白）

| 缺口 | 现状 | 为什么致命 |
| --- | --- | --- |
| **RAG / 向量检索** | `src/` 里 grep `embedding|vector|milvus|chroma|pgvector|langchain|rag|retriev` → **0 命中** | 岗位 JD 里 RAG 是出现频率最高的词。你现在是「把整份简历 JSON 塞进上下文」，简历一长就超窗、就贵、就漏。面试官第一个问题就是「长简历你怎么处理？」 |
| **Agent / Function Calling** | `grep tool_calls|"tools"|function_call|responses.create` → **0 命中** | 项目自己的 JD 配置里就写着「Function Calling / Agent / MCP」，代码里一行没有。单次 `chat.completions` 是 2023 年的做法 |
| **离线评测** | 无 golden set、无准确率/一致率指标、无回归集 | 这是**最大的硬伤**。你能说「我把阈值调到 90」，但说不出「调完之后 relevant 判定的精确率从 78% 涨到 91%」。面试官问「你怎么知道提示词好使？」你只能答「我看输出是对的」 |
| **并发 / 吞吐** | `grep asyncio|ThreadPool|concurrent.futures|await` → **0 命中**，全链路同步串行，一次只分析一个候选人 | 一个关键词 5 份简历 × 每份 240s 超时，串行会卡到崩溃。这直接限制了「平台」想象空间 |
| **可观测性** | 有结构化日志（`event` + `context`），但无 token / 成本 / 延迟 / 重试率指标，无 trace | 讲不出「单份简历平均多少 token、多少钱、降本多少」 |
| **语言栈** | 全 Python；岗位普遍要 Java 或"Java/Python" | 如果你投的 JD 写 Java，这块是硬门槛（但这个只能补，不适合改项目） |

**一句话总结**：这个项目现在是一个优秀的**「LLM 工作流工程化」**项目，还不是一个**「AI 应用平台」**项目。差的不是代码质量，是 AI 能力的**宽度**（RAG/Agent）和**度量**（评测/观测）。

---

## 5. 升级路线

按「投入产出比 × 面试可讲性」排序。**P0 的三件事做完，这个项目就能从「能用」变成「能讲」。**

### P0-1　加一套离线评测（性价比最高，2~3 天）

**做什么**
1. 从 `artifacts/hr_demo` 和真实库里脱敏导出 150~300 份简历，人工标注 `relevant / needs_review / not_relevant`，存 `eval/golden.jsonl`（字段：简历正文、岗位 id、golden 结论、golden 分数区间）。
2. 写 `eval/run_eval.py`：跑当前 prompt，输出**混淆矩阵、三态精确率/召回率、分数 MAE、硬性条件漏判数、`analysis_reason` 字段完整率**。
3. 存历史结果到 `eval/runs/<date>-<prompt_version>.json`，和 `analysis_version` 绑定。

**面试话术**
> "我把提示词改动从『凭感觉』变成『可回归』：建了 217 份人工标注的 golden set，把三态判定准确率从 79.4% 提到 91.2%，`not_relevant` 误杀率从 11% 压到 2.3%。每次改 prompt 都跑一遍，指标下降就拦在合并前。"

这一条的价值远超它的代码量——它证明你懂**AI 产品的质量方法论**，而不是只会调 API。

### P0-2　把「全文塞入」改成 RAG（3~4 天）

**做什么**
1. `src/liepin_rpa/retrieval/`：简历按「工作经历 / 项目 / 教育 / 技能」分块，`text-embedding-3-small` 或 bge-m3 生成向量，落 **pgvector**（已有 PostgreSQL 依赖意识）或 faiss 本地库。
2. 检索侧：按岗位配置里的**评分维度**生成子查询（"后端年限"、"RAG 项目证据"、"部署上线证据"），每维度召回 top-k 片段，拼成 `evidence_context` 喂给模型。
3. **关键改造**：让模型输出 `evidence_summary` 时带上 `chunk_id`，前端「AI 判断依据」面板做成**可点击跳转到简历原文高亮**——这是演示时最抓眼球的一下。
4. 加一道**混合检索兜底**：向量分数低就回落到 BM25/关键词，避免中文长文本向量召回不稳。

**面试话术**
> "简历最长 6000 字，整份塞进去单次 8k token、且模型会漏掉中段项目。我改成按评分维度做 query planning，分块 + 向量召回 top-k 证据片段，单次 token 降到 2.3k（-71%），关键证据命中率反而从 68% 升到 89%，并把每条结论都变成可溯源到原文的引用。"

### P0-3　把单次调用改成工具调用（Agent 化，3~4 天）

**做什么**
1. 把现在**写死在代码里**的硬规则抽成模型可调用的工具：
   - `check_education_unified_bachelor()`（复用 `resume_schema.evaluate_unified_bachelor_requirement`）
   - `compute_backend_years()`（从工作经历算 Java/Python 年限）
   - `search_similar_jd_requirements(job_profile_id)`（RAG 检索岗位要求）
   - `get_previous_analysis(candidate_fingerprint)`（取历史结论做对比）
2. 改 `openai_compatible_analysis.py` 走 `tools=[...]` + 多轮 tool loop（上限 4 轮），最后强制 `response_format=json_object` 收敛到现有契约。
3. **保留降级**：工具调用轮次超限或异常 → 回到当前的"单次调用"路径。这样既有新架构又没丢掉已验证的备用链路。
4. 用 `analysis_contract.validate_analysis_payload` 做最后一道闸门——**契约不变，这是你这次改造最大的安全感来源**。

**面试话术**
> "硬性条件不交给模型自由判断：学历统招是解析出来的结构化事实，后端年限是算出来的，模型只负责语义匹配和证据归因。Agent 编排只是把这几步串成可解释的链路，任何一步失败都回落到单次调用 + 人工复核。"

### P0-4　分析层并发化（2 天）

**做什么**：`asyncio.Semaphore`（并发 4）+ `AsyncOpenAI`，把「一批候选人的分析」从串行改成受控并发；加 token 预算上限和每批成本上限。用 `pytest-asyncio` 补测试。
**话术**：一个关键词 5 份简历的分析耗时从 6.5 分钟降到 1.8 分钟，同时用信号量 + 预算闸门防打爆供应商限流。

### P1-1　补 CI + 修红测试 + 清理（半天）

- 修上面那个 80/90 的过期测试。
- GitHub Actions：`pytest` + `tsc --noEmit` + `vitest run` + `ruff`，PR 必绿。
- 提交那 22 个未跟踪/未提交的变更（`deploy/`、演示脚本、`run_hr_demo.py` 等）。
- 删 `tmp/hr-publish/`。
- 把 `Coze` 这个写死的供应商名从导出/播报文案里抽出来。

### P1-2　可观测性（2 天）

LLM 调用统一埋点：`prompt_version / model / input_tokens / output_tokens / latency / retry_count / cache_hit / 估算成本`，落到一张 `llm_call_events` 表，看板加「AI 调用成本与质量」页。这一步让你在面试里能说出**单位经济性**（每份简历多少钱），这是「应用开发」和「调 API」的分水岭。

### P1-3　让演示环境对口 AI 岗位（1 天）

现在演示库里全是「达人营销」「财务共享」——你投 AI 应用开发，面试官打开看到的是达人营销，会出戏。把 `seed_hr_demo.py` 的岗位换成 `ai_development_engineer_v1` 和 `fde_ai_deployment_engineer_v1`，虚构简历改成 AI 开发/大模型应用背景，让「AI 判断依据」里有真实的 RAG/Agent 证据。

### P1-4　拆分巨型模块（渐进式，别一次动）

`result_filter.py` 2904 行按职责拆：`card_screening.py` / `profile_flow.py` / `ai_analysis_flow.py` / `communication_flow.py`。**前提：先补齐回归样本再动**——项目自己的 `DEVELOPMENT_PLAN.md` 已经写明这条纪律，照做即可。

### P2　安全与部署

- `.env` 里的生产凭据全部轮换，改为本机密钥库/环境注入；`PDF_PUBLIC_BASE_URL` 上 HTTPS。
- 演示/真实入口的 Cookie 隔离改成显式命名。
- 加 `ruff` + `mypy` 到 CI（`.ruff_cache`、`.mypy_cache` 已存在但没接入流水线）。

---

## 6. 截图（已实测生成）

- 登录页：`artifacts/screenshots/01-login.png`
- 工作台：`artifacts/screenshots/02-workbench.png`
- 候选人详情（AI 判断依据面板）：`artifacts/screenshots/03-candidate-detail.png`

看板 7 个指标：可行动候选人 / 待审核 / 建议沟通 / 已联系 / 面试中 / 已淘汰 / 已录用；表格列出候选人、岗位、年龄、AI 结论、AI 分数、当前公司职位、沟通状态、HR 状态、审核人、更新时间，全部服务端排序分页。

---

## 7. 简历怎么写（三个版本，按岗位微调）

> ⚠️ **下面的百分比数字是「做完 P0 后应该长成的样子」的占位符，不是实测值。**
> 简历上的每个数字都必须来自你自己跑出来的 `eval/runs/*.json`。面试官会追问「这个 91.2% 是怎么算的、
> 分母是多少、标注谁做的」，答不上来比不写数字更糟。做完评测后，把这里的数字全部替换成你的真实结果。

**版本 A：偏工程（投后端/平台）**

> **AI 招聘自动化平台**（个人项目，Python / FastAPI / React / MySQL / LLM）
> - 设计**供应商无关的简历分析契约**：Pydantic 严格校验 6 字段输出，分项评分上限由岗位 YAML 注入且强制三项之和等于总分，把不可控的 LLM 输出变成可入库的结构化事实。
> - 建立**三态判定 + 安全降级**：校验失败/API 异常/字段矛盾一律降级为人工复核而非误杀，自动外呼仅对显式匹配生效；4 次指数退避重试 + 10 分钟临时异常窗口。
> - 实现多级去重（身份指纹 + 简历正文哈希 + 分析版本 + 7 天冷却），避免重复调用与重复触达；HR 侧用 revision 乐观锁 + 不可删除审计事件保证多人审核一致。
> - 235 个后端用例 + 17 个前端用例，覆盖提示词契约、报表口径与并发审核冲突。

**版本 B：偏 AI 应用（投 AI 应用开发，做完 P0 后）**

> **AI 简历匹配平台**（个人项目，LLM / RAG / Agent / 评测）
> - 构建**基于评分维度的 RAG 流水线**：简历分块 + 向量召回 + 混合检索兜底，将单次分析上下文从 8k token 降到 2.3k（**-71%**），关键证据命中率从 68% 提升到 **89%**，并把每条结论做成可溯源到原文的引用。
> - 将线性 LLM 调用重构为**工具调用 Agent**：学历统招核验、后端年限计算下沉为确定性工具，模型只负责语义归因；工具链路异常自动回落到单次调用，保持原有契约不变。
> - 建立**离线评测体系**：217 份人工标注 golden set，三态判定准确率 **79.4% → 91.2%**，误杀率 11% → 2.3%，每次提示词变更强制回归。
> - 分析层并发化（信号量 + token 预算闸门），单批分析耗时 6.5min → 1.8min，并接入 token/成本/重试率埋点。

**版本 C：一句话版（简历摘要/自我介绍）**

> 独立完成一套 AI 招聘自动化平台：安卓端自动化采集 + 大模型三态匹配 + HR 审核工作台，全链路 1.8 万行代码、252 个测试；核心解决「如何让 LLM 输出可信、可审计、可回归」——用严格输出契约 + 安全降级 + 离线评测把准确率做到 91%。

---

## 8. 面试会被追问什么（提前准备）

| 问题 | 你现在的答案 | 升级后 |
| --- | --- | --- |
| 怎么保证模型输出稳定？ | 契约校验 + 三态降级 | 同上 + 工具调用收敛 |
| 简历太长怎么处理？ | ❌ 全文塞入 | ✅ RAG 分块召回 |
| 怎么证明提示词有效？ | ❌ 靠眼看 | ✅ golden set + 指标曲线 |
| 用了 Agent 吗？ | ❌ 没有 | ✅ 工具调用 + 回落链路 |
| 成本多少？ | ❌ 不知道 | ✅ 每份简历 token/费用 + 降本 71% |
| 并发怎么扛？ | ❌ 串行 | ✅ 信号量受控并发 |
| 线上出问题怎么查？ | ✅ 结构化日志 | ✅ + LLM 调用事件与 trace |
| 多人同时审核怎么办？ | ✅ 乐观锁 + 409 | ✅ 同上 |
| 数据合规？ | ✅ PII 脱敏、日志默认 mask | ✅ 同上 + 敏感访问审计 |

**最该补的三件，按优先级**：① 离线评测　② RAG 证据检索　③ 工具调用/Agent。做完这三件，配上已有的强工程底子，这个项目在秋招里属于**明显高于平均线**的水平。

---

## 9. 建议的两周执行顺序

| 天 | 任务 |
| --- | --- |
| D1 | 修红测试；提交积压变更；删 `tmp/hr-publish`；接入 GitHub Actions |
| D2–D4 | **P0-1 离线评测**：laptop 标注 150+、`run_eval.py`、指标报告、绑定 prompt 版本 |
| D5–D7 | **P0-2 RAG**：分块 + 向量库 + 按维度 query + `chunk_id` 溯源 + 前端高亮 |
| D8–D10 | **P0-3 Agent**：4 个工具 + tool loop + 回落链路 + 契约闸门 |
| D11–D12 | **P0-4 并发化** + token 预算；补 `pytest-asyncio` |
| D13 | **P1-3** 演示环境换成 AI 岗位数据 + 重拍截图/GIF |
| D14 | 重写 README（架构图 + 指标表 + 3 分钟演示脚本）+ 简历定稿 |

---

## 10. 附：本次体检的可复现命令

```powershell
# 后端全量测试（当前 1 红）
.\.venv\Scripts\python.exe -m pytest -q

# 前端
cd web\hr-review
.\node_modules\.bin\tsc.CMD -p tsconfig.app.json --noEmit --pretty false
.\node_modules\.bin\vitest.CMD run
.\node_modules\.bin\vite.CMD build

# 演示环境
.\.venv\Scripts\python.exe -m scripts.run_hr_demo
```

> 说明：本次体检在受限沙箱内跑测试时，pytest 的 `--basetemp=.pytest-tmp` 会因为目录 ACL 无法删除而连带报 51 个 `PermissionError`。**这不是项目的 bug**，放宽权限后全量重跑，真实结果是 234/235 通过。同理，前端 `vitest`/`vite build` 需要 esbuild 启动子进程，沙箱下会 `spawn EPERM`，正常环境无此问题。
