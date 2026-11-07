# 第 2 阶段：离线评测工具与合成验证

2026-11-05 用户确认：**先只完成工具与合成样本验证**。本目录不提供真实模型调用入口，不读取 `.env`、数据库或手机，不产生模型费用。人工黄金集、真实质量基线、生产调用观测与生产缓存切换暂缓。

## 1. 保存响应的回放评测

```powershell
.\.venv\Scripts\python.exe -m eval.run_eval --dataset eval/fixtures/synthetic_dev.jsonl --predictions eval/fixtures/synthetic_predictions.jsonl --split dev
```

默认结果在被 Git 忽略的 `artifacts/eval/runs/`。随代码提供的四条样本和预测均为人工编写的合成测试夹具，包含刻意错误和供应商失败，**只验证工具，不代表人工黄金集或真实模型效果**。没有提供冻结测试集，不得复制开发集作为测试集。

数据集每行必须包含 `sample_id/group_id/split/source_type/job_profile_id/resume_text/gold`。ID 使用不含身份信息的字母数字标识；`split` 为 dev/test，来源为 synthetic/authorized_real。真实样本还需 `annotator_id`，但填写这个字段不自动证明已经授权或完成可靠标注。可选 `score_interval` 为 0–100 之间有序整数区间。

每个预测行包含 `sample_id`、执行状态及六字段 `payload` 或 `raw_response`。成功响应仍需通过当前岗位输出契约；缺失、无效或执行失败一律映射为复核，保留在质量指标分母中。重复预测、未知 ID、候选人跨集合、完全相同规范化正文跨集合会被拒绝。近重复与身份分组仍需人工审核。

输出包含三态混淆矩阵、每类精确率/召回率、固定三类 macro-F1、合格误拒、人工复核、自动覆盖/错误、执行失败、评分区间偏差、按岗位/来源分层，以及 dataset/prediction/prompt/job/contract/rules/source hash。指标分母为 0 时比例为 null，固定标签 macro-F1 的零分母按 0 计。

这些 hash 表示回放时的代码、契约与输入文件；无元数据的旧记录会标记 unverified_recording。新模拟记录还校验推理指纹、简历和数据集 hash；不一致会标记 provenance_mismatch 并转复核。通过仅代表记录自洽，不证明来自真实远程模型。

同数据集与 split 的两个报告可做配对描述性比较：

```powershell
.\.venv\Scripts\python.exe -m eval.compare_runs artifacts/eval/runs/before.json artifacts/eval/runs/after.json --output artifacts/eval/comparison.json
```

将示例报告路径替换成实际生成文件。比较会拒绝不同样本集合、重复 ID 或标签定义，重算汇总，并按候选人组做配对百分位 bootstrap，输出 95% 差值区间、有效抽样次数和种子。可设置 `--iterations 500 --seed 2026`。不足两组时返回 insufficient_groups；区间不证明数据有代表性，也不构成真实质量提升结论。

报告不复制简历、响应正文或标注人身份。成本记 unknown、在线延迟记 not_measured，不将回放耗时当成模型延迟，不凭空填 0 元。报告的 `quality_claim_allowed=false` 防止将流程验证结果直接宣传为真实模型质量。

真实数据、响应和标注存放在受控的 `artifacts/eval/private/` 或外部目录，不提交 Git；对外共享前还需检查 ID 可识别性。Git diff 仅计算摘要，不保存原始差异；版本指纹同时覆盖未跟踪的本地实现文件。

## 2. 生成并运行 42 条合成夹具

```powershell
.\.venv\Scripts\python.exe -m eval.generate_synthetic
.\.venv\Scripts\python.exe -m eval.simulate --dataset artifacts/eval/synthetic/synthetic_42.jsonl --scenarios artifacts/eval/synthetic/synthetic_scenarios_42.jsonl --split dev --max-requests 100 --max-tokens 100000 --max-cost 0.1
.\.venv\Scripts\python.exe -m eval.simulate --dataset artifacts/eval/synthetic/synthetic_42.jsonl --scenarios artifacts/eval/synthetic/synthetic_scenarios_42.jsonl --split test --max-requests 100 --max-tokens 100000 --max-cost 0.1
```

生成器固定创建 30 条 dev 和 12 条 test，覆盖 AI/FDE 两岗位、三态、正常响应、无效输出后重试、限流、超时、未知 usage 和认证拒绝。两个集合只验证分组和运行逻辑，模板可能相似，不构成独立人工黄金集。正文的提示注入片段是测试材料，预置输出不证明真实模型能抵御注入。

模拟器拒绝非 synthetic 数据，没有 live/provider URL 参数，不初始化生产 Settings 或 SDK 客户端。每次运行在 `artifacts/eval/simulations/sim-<UTC时间>-<随机ID>/` 中输出：

- `predictions.jsonl`：预置响应、正文/数据集 hash、推理配置。
- `call_events.jsonl`：每次模拟尝试的 trace/run/sample/attempt、版本、状态、usage、费用和模拟延迟，不含原文或原始错误。
- `report.json`：指标、证据检查、版本一致性、预算与调用汇总。

## 3. 预算和观测边界

请求前预留请求数、输入和最大输出 token、费用；所有重试单独入账。未知 usage 不填 0，未知部分保守占用预算；缺少价格时不能在费用上限下发起请求。同一预算不能混用币种，结算不能变更价格版本，重复结算会被拒绝。

价格固定为 synthetic-rate-v1，只是测试费率，模拟延迟来自夹具声明，二者都不是供应商实际数据。事件为 `simulated=true`。未知 usage 时总估算费用为 null，已知部分和保守预算占用另列，线上费用/延迟仍保持 unknown/not_measured。

`analysis_observability.py` 是可复用的单进程工具，尚未接入现有两个生产 provider，也不是分布式额度或 RPM/TPM 系统。实际 usage 超出预留时会按报告值入账并限制后续调用，不能承诺阻止已经发生的超额计费。

## 4. 推理版本与缓存工具

`analysis_versioning.py` 将岗位、prompt、provider/model、采样参数、契约/规则、检索/工具版本绑定为 SHA-256 指纹。缓存键还包含候选人和正文 hash；测试覆盖各因素变化时失效、配置字段顺序不影响键。

新模拟记录的 provenance 为 self_consistent_synthetic_recording。自洽但未认证的其他记录为 self_consistent_unattested_recording；旧记录未验证，错配记录进入复核。`evaluation_fingerprint` 则表示回放工具的代码与输入上下文，不能和推理指纹混同。

**现有生产缓存仍使用既有 analysis_version 策略，本轮没有切换生产缓存键。** 后续切换需要迁移和灰度，不能将工具单测通过说成生产缓存已完成升级。

## 5. 证据工具和暂缓项

可选 `gold_evidence`、`retrieved_spans`、`evidence_refs` 均使用 Unicode 码点半开范围。召回片段的并集覆盖标注范围至少 50% 视为召回；引用还要求 quote 与原文范围完全相同。位置有效不代表语义支持，语义支持率保持 requires_human_judgement。第 3 阶段已另行提供快照和前端原文跳转；本评测模块不负责页面展示，生产模型路径尚未接入 RAG。

按当前用户范围，真实人工标注、独立冻结集、预算化真实调用入口、真实 provider 事件接入、生产缓存迁移和语义证据支持标注暂缓。当前工具交付不依赖这些真实数据，不继续催要人工标签。

## 6. 长文本检索合成对照

```powershell
.\.venv\Scripts\python.exe -m eval.retrieval_benchmark --generate-fixtures
```

输出位于 `artifacts/eval/retrieval/retrieval-<时间>-<ID>/`，包含 18 条合成夹具（dev 12 / test 6）与 dev 报告。再用该目录中 `synthetic_retrieval.jsonl` 运行 `--dataset <文件路径> --split test`。可设置 `--context-chars 6000 --fulltext-chars 20000 --top-k 2`，策略、查询版本、数据与源代码摘要进入报告。

工具对比全文原文覆盖、BM25 召回及事实/预算保护后的上下文，记录缺失事实、超预算和缺维度候选导致的回退。全文覆盖不代表模型答对；字符比不代表 token/费用改善。混合检索只有外部向量融合组件单测，没有实际 embedding，报告标为 unavailable。质量和成本保持未测量，默认 provider 全文路径不变。实跑数字和页面验证见 [第 4 阶段记录](../docs/UPGRADE_STAGE_4.md)。

## 7. 持久化队列故障模拟

```powershell
.\.venv\Scripts\python.exe -m eval.queue_demo
```

每次创建独立 `artifacts/analysis_queue/queue-<时间>-<ID>/queue.sqlite` 与 `report.json`。12 个固定合成任务覆盖快照入队、重复提交、旧版本历史、租约回收、429、超时、无效输出、取消和预算耗尽。脚本重建连接池验证持久化；回归测试另有真实子进程领取后退出的恢复案例。

报告包含任务/尝试状态、保守预留与未知 usage、源码和输出夹具摘要，没有简历正文/原始错误或真实准确率、成本、线上延迟。SQLite 强制单 Worker；线程池 stub 验证不证明 MySQL 并发正确。现有生产采集/模型/缓存未接入队列，真实 SDK 重试须逐次预留，不能用一次回调掩盖多次请求。

状态查看与受限重试使用 `python -m scripts.manage_analysis_queue --database <合成目录中的queue.sqlite> status --batch-id synthetic-main`，实际命令、迁移和限制见 [第 5 阶段记录](../docs/UPGRADE_STAGE_5.md)。管理入口拒绝合成目录以外或不匹配环境/价格身份的数据库。

## 8. 实际 SDK 与本地 HTTP 逐请求验证

```powershell
.\.venv\Scripts\python.exe -m eval.sdk_queue_demo
```

使用已安装的 OpenAI SDK、临时数字 loopback HTTP 服务及独立 SQLite，预置 14 个场景，覆盖成功、429/503 后队列重试、认证/额度拒绝、无效输出、未知/部分 usage 和模型身份异常。SDK 重试关闭，每个租约只允许一次请求开始；规范请求和回执持久化，晚到 usage 可以补记而不能发布结果。

产物写入 `artifacts/analysis_queue/sdk-<时间>-<ID>/`：报告、无正文的 `request_receipts.jsonl` 和私有 SQLite。保守占用与估算费用分开；缺失/不可信费用为 null。输入单位、价格及响应为合成定义，真实模型调用为 0；SDK 本地 HTTP 耗时不等于模型在线延迟。没有外部 URL/密钥参数，不读取 `.env`。实跑 16 次 HTTP 与 16 条回执，以及迁移 `_07`/测试范围见 [第 5 阶段第 6 节](../docs/UPGRADE_STAGE_5.md#6-第三轮单次-sdk-请求与持久化回执)。

## 9. 有限只读工具的脚本对照

```powershell
.\.venv\Scripts\python.exe -m eval.bounded_tools_demo
```

20 个固定场景使用纯脚本 provider，验证白名单、严格参数、当前快照、规则必跑、月份来源、最终引用、有限轮数、同预算回退/修复及审计。产物在 `artifacts/eval/tools/tools-<时间>-<ID>/`；无工具 1 回合、有工具 3 回合/4 次工具执行，内容预置相同，不能当作模型质量对照。

此 runner 只复用单进程 RequestBudget，尚未接入数据库共享账本或真实 SDK 工具调用。报告不含原文或响应 payload，quality_claim_allowed=false；边界、脚本实跑和后续要求见 [阶段 6](../docs/UPGRADE_STAGE_6.md)。

## 10. 统一本地验收包

根目录执行 `python -m scripts.run_upgrade_acceptance --include-sdk --verify`，在全新 `artifacts/acceptance/` 子目录聚合检索、队列、脚本工具、影子 API、SDK 本地 HTTP 验证并复核。省略 `--include-sdk` 则 SDK 明确记为未运行。报告按实际落盘字节计算 SHA-256，失败返回非零退出码；未包含的 pytest、前端、浏览器和真实环境检查单列于清单。已有包用 `python -m scripts.verify_upgrade_acceptance <manifest路径> --require-sdk` 检查，按需加入 `--check-source` 对比当前源码。schema 2 增加复核器自身摘要，旧 schema 1 仍支持归档复核。命令不触发真实推理，详细范围与三分钟演示见 [阶段 7](../docs/UPGRADE_STAGE_7.md)。

## 11. 剩余链路的一体化验证

根目录执行 `python -m eval.remaining_acceptance`，使用新的本地合成库与真实 SDK 对临时 loopback 假服务，验证逐回合预留/响应保存/回放、结果投影、人工审核冲突和旧版本隐藏。产物仅报告合成集成结果，不声称真实模型质量；详细口径见 [剩余阶段补齐记录](../docs/UPGRADE_REMAINING_IMPLEMENTATION.md)。

隔离 MySQL 环境可显式执行 `python -m eval.mysql_queue_acceptance --execute`，要求 `UPGRADE_MYSQL_TEST_URL` 指向 127.0.0.1 下 `hr_upgrade_test_*` 空库；不会读取生产 `.env`。本机因 Docker 引擎管道拒绝访问而未实跑，新增 CI 服务任务也尚未推送执行。
