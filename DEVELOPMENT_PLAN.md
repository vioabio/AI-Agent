# 猎聘双岗位简历自动化开发计划

更新日期：2026-08-17

主项目：`D:\jianlizhuanqu`

详细交接：[docs/PROJECT_HANDOFF.md](docs/PROJECT_HANDOFF.md)

## 1. 当前阶段

项目已从早期原型进入“受控生产运行 + 可靠性维护”阶段。当前主链路已经覆盖：

1. 单台 Android 真机和单个猎聘账号串行操作；
2. `jd_smart` 双岗位搜索目录；
3. 结果卡片前置筛选、详情采集、上海求职意向硬门槛；
4. 岗位独立 Coze 工作流分析；
5. 候选人身份去重、AI 缓存、正式分享链接/收藏和沟通记录；
6. 关键词级 checkpoint、运行锁、run_state、双岗位批次状态；
7. MySQL 真值、可行动候选人 PDF 和飞书播报生成链路。

当前不是“重新搭项目”的阶段。后续会话必须先读主目录代码、`git status` 和本文件，保留已有未提交变更，禁止从旧工作树或旧会话复制代码覆盖主项目。

## 2. 冻结的产品规则

### 2.1 双岗位

| 顺序 | 岗位 | 配置 | 当前关键词数 |
|---|---|---|---:|
| 1 | 达人营销与达播负责人 | `config/job_profiles/daren_live_bd.yaml` | 96 |
| 2 | 财务共享与管理经理 | `config/job_profiles/finance_shared_management.yaml` | 22 |

- 财务岗位只能在达人岗位 checkpoint `96/96`、搜索进程正常退出、锁释放且对应状态完成后启动。
- 财务职位选择页找不到“财务共享与管理经理”时必须停止，不能误选其他职位。
- 两个岗位使用各自配置、JD、分析版本和 Coze 工作流，不能混用。

### 2.2 采集与沟通

- 每个搜索词最多进入 5 份详情；不存在“整轮累计 50 份详情后停止”。
- 后续关键词必须继续滚动执行。
- 两个岗位都要求求职意向明确为上海；财务岗位另有 35 岁上限。
- `match_result=relevant` 才允许自动打招呼。
- `needs_review` 和 `not_relevant` 均不自动打招呼。
- 已沟通过或人工抑制沟通的候选人不得重复发送。
- 分享额度不足后按账号和日期降级为收藏，不重复消耗分享额度。

### 2.3 可行动数据与报表口径

共享阈值定义在 `src/liepin_rpa/reporting_rules.py`：

```text
可行动候选人 = relevant（全部分数）
              + needs_review 且 match_score >= 90
```

- `not_relevant`、`needs_review < 90`、Coze 临时 API 异常以及 `manual_suppress_report=1` 不进入可行动 PDF 或待复核统计。
- 人工处理只写独立人工字段，不覆盖历史 AI 三态、评分和证据。
- PDF、播报的当天/按岗位/累计可行动统计、报告历史水位和高价值链接规则必须引用同一口径。
- 禁止在 `result_filter`、PDF、播报或导出脚本中另写一个裸 `90` 形成漂移。

## 3. 当前维护优先级

### P0：运行安全（持续约束）

- 保证单手机实例互斥；运行前核验真实进程链和 `artifacts/search_workflow.lock`。
- checkpoint 只按 `completed_keyword_keys` 判定完成，并检查 `in_progress_keyword`。
- 遇验证码、滑块、登录异常、账号风控、实名认证、设备离线或连续错误熔断立即停止并转人工。
- 正常续跑不能使用 `--no-resume` 或 `--reset-checkpoint`。
- 搜索命令不得夹带 `--send-report`；日报必须独立、显式执行。

### P1：采集与判定可靠性（已完成）

- [x] 详情采集统一使用候选人姓名清洗，互动提示与姓名分离。
- [x] 候选人指纹、`experience_hash`、搜索命中和定位链接保持关联。
- [x] AI 三态和中文状态集中到 `candidate_decision_rules.py`：`relevant → 匹配`、`needs_review → 待复核`、`not_relevant → 不匹配`。
- [x] 自动沟通授权只认显式 `match_result=relevant`，不得由旧状态文字或兼容布尔字段放宽。
- [x] Coze `beauty_relevant` 必须与 `match_result` 严格一致；矛盾输出重试后安全降级为 `needs_review`。
- [x] Coze `code=4000`/可重试错误最多尝试 4 次；耗尽后进入临时待复核，10 分钟缓存到期可重试，不得改成不匹配。
- [x] 两个岗位均配置独立主/备 Coze 工作流；主工作流 API/空响应/载荷校验异常耗尽安全重试后自动切换同岗位备用工作流，禁止跨岗位混用。
- [x] 主工作流正常返回 `relevant`、`needs_review` 或 `not_relevant` 时不切备用；主备均失败时安全降级为临时 `needs_review`，不得沟通。

### P2：数据与报表（核心口径已完成）

- [x] 将可行动待复核阈值集中到共享策略模块。
- [x] PDF 纳入所有 `relevant`，仅对 `needs_review` 使用 90 分门槛。
- [x] 飞书播报当天和按岗位匹配数纳入低分 `relevant`。
- [x] 报告候选人历史水位使用同一选择集，避免低分 `relevant` 重复出现。
- [x] 当前窗口、累计、累计按岗位和历史待复核查询排除 `manual_suppress_report=1`。
- [x] 保留人工处理候选人的历史 AI 数据用于审计。
- [x] 增加相关查询和选择集回归测试。
- [ ] 后续将日报“流水线原始 AI 三态计数”和“可行动待办计数”在字段命名上彻底分离，减少阅读歧义。
- [ ] 用数据库事件表替换部分依赖 `logs/app.log` 推导的浏览量/关键词统计。

### P3：运行状态语义（下一优先级）

- 修复熔断后 `_run_main()` 仍可能返回 0、外层把 run_state 写成 `completed` 的语义风险。
- 为串行队列增加独立队列互斥，避免两个 PowerShell 队列观察器同时存在。
- 将风险事件集合抽成共享定义，供队列、心跳和手工诊断复用。

### P4：可维护性

- 拆分体积较大的 `search_execution/result_filter.py`，但不得在没有回归样本时改动核心手机流程。
- 数据库字段演进逐步转为显式迁移；当前仍有运行时 `ALTER TABLE` 兼容逻辑。
- 清理旧版日报和过时文档，但先保留可审计历史，禁止误删运行产物。

## 4. 验收标准

每轮代码维护至少执行：

```powershell
.\.venv\Scripts\python.exe -m pytest
.\.venv\Scripts\python.exe -m compileall -q src scripts tests
git diff --check
git status --short
```

手机流程未被明确授权时，只允许离线测试和只读检查；不得启动 `run_search_workflow.py`、串行队列、日报发送或日报测试。

交付必须记录：

- 修改文件和口径；
- 相关测试与全量测试真实结果；
- 是否触碰数据库、手机或外部服务；
- review 发现、剩余风险和下一步；
- 当前 Git 分支、最后提交和未提交变更范围。
