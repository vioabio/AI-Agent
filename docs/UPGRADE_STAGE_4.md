# 第 4 阶段：证据检索与长文本离线对照

记录日期：2026-11-05。主计划见 [升级实施方案 V2](AI_APPLICATION_UPGRADE_PLAN_V2.md)。

本阶段交付**当前分析快照内的只读词法检索、原文定位、离线上下文规划与合成对照工具**，已通过本地 SQLite、实际 API 和 Chrome 页面验证。现有模型调用继续使用全文；检索上下文尚未接入生产分析网关。真实模型质量、真实 embedding、费用及生产环境验收继续暂缓，符合用户“先只完成工具与合成样本验证”的范围。

## 1. 已实现的能力

| 能力 | 实现与边界 |
| --- | --- |
| 原文分块 | `retrieval/chunks.py` 按已识别章节、显式公司/项目标记、原文行分块；长行按 512 码点、64 重叠切分。保留快照、半开范围、章节/公司来源范围，不改写原文 |
| 岗位查询 | `config/retrieval_profiles.yaml` 为 AI、FDE 和两个旧演示岗位分别配置三维查询；独立版本，不改岗位评分 YAML |
| 词法检索 | `retrieval/lexical.py` 使用 BM25；中英文规范化只影响索引，定位仍用 exact-codepoints-v1；得分相同按原文顺序稳定排序 |
| 混合检索组件 | `retrieval/hybrid.py` 支持外部提供的向量、余弦检索、RRF、索引 JSON 保存/加载；仅用测试向量验证。没有 embedding 服务、向量数据库或 reranker |
| 上下文规划 | `retrieval/context.py` 提供 fulltext/lexical/hybrid 离线模式；保留教育、意向、日期、否定/程度词及归属标记所在原文行；输出可验证的 JSON 来源段 |
| API | `GET /api/v1/candidates/{fingerprint}/evidence-search?dimension_id=role_evidence`；复用详情权限，仅检索选中分析的已验证快照，最多 5 个片段，无写入/模型调用 |
| 前端 | 详情新增“按岗位查阅原文”，手动选择方向并检索；点击定位到同一快照，区分检索片段与 AI 原始引用；保持未提交审核意见 |
| 离线对照 | `eval/retrieval_benchmark.py` 生成 18 条长文本合成夹具，比较全文来源覆盖、原始词法召回及保护后上下文；保存数据/查询/策略/代码摘要，报告不复制正文 |

BM25 使用 `k1=1.2`、`b=0.75` 和正值 IDF，参考 [Lucene BM25Similarity](https://lucene.apache.org/core/10_5_2/core/org/apache/lucene/search/similarities/BM25Similarity.html)。融合使用各路排名的 `1/(60+rank)` 之和，参考 [Elasticsearch RRF](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion)，不直接相加余弦与 BM25 原始分数。

## 2. 回退与版本边界

以下策略仅属于离线规划器，**没有切换生产评分或生产缓存键**：

- 短简历（默认不超过 1000 码点）使用全文。
- 某维度没有检索候选片段，或精简上下文超过默认 6000 字符预算，回退全文。
- 全文 JSON 超过默认 20000 字符预算时返回规划状态 `needs_review`，不静默截断。
- 缺少教育、意向或时间标记，或明确写有“学历：未提供”等未知值，返回 `unknown_required_facts`。这不是候选人的匹配结论。
- 缺少 embedding、查询向量或向量索引过期时回退词法，并记录原因。真实混合检索实验保持 `unavailable`。
- 快照、正文、分块/分词/排序、查询模板、上下文策略及向量模型/内容变化均参与相应版本或上下文指纹；向量文件不能跨正文版本复用。

预算是**简历上下文 JSON 字符数**，包括来源元数据，不是模型 token，也不包括完整岗位提示词。没有 tokenizer 或真实调用，不能据此宣称费用节省。

事实保留和公司归属采用显式标记启发式：没有标记的公司不会被猜测；日期或“学历”出现不证明资格满足。紧凑自由文本、隐含归属、复杂否定及矛盾履历仍需规则解析和人工核验。规划器不能替代既有资格规则，也不能证明检索片段在语义上支持某项能力。

## 3. 合成对照实跑结果

相同策略 `top_k=2 / compact=6000 / fulltext=20000` 分别运行 dev/test；没有在结果上调参后宣称独立冻结集效果。

| 指标 | dev：12 条 | test：6 条 |
| --- | --- | --- |
| 全文原文对预置证据的覆盖 | 38/38 | 19/19 |
| 原始 BM25 证据召回 | 32/38 | 16/19 |
| 保护后上下文证据召回 | 32/38 | 16/19 |
| 使用精简词法上下文 | 6 条 | 3 条 |
| 回退全文 | 4 条 | 2 条 |
| 关键事实未知，规划转复核 | 2 条 | 1 条 |

“召回”沿用评测工具定义：有效召回范围的并集覆盖预置证据范围至少 50%。全文覆盖是来源对照，不是模型答对率；规划转复核时没有输出正文段，仍计入证据分母。原始召回与保护后召回总数相同，不代表逐例行为相同。两集合来自相近合成模板，不能作为独立人工黄金集。

只有精简路由子集的平均 JSON 字符比约为全文的 0.202；这不是全批次压缩比或 token/费用降幅。报告中准确率、实际成本和 token 保持未测量，`quality_claim_allowed=false`。

本地产物不提交 Git：

- dev：`artifacts/eval/retrieval/retrieval-20261105T031545Z-8076634c/report.json`
- test：`artifacts/eval/retrieval/retrieval-20261105T031559Z-e5525386/report.json`
- 相同数据集 SHA-256：`ce4d9319cf248084cb05adde2cd2a2a7e6f3105dd94cc8b400155cc8a4e56954`

当前选择：**保留现有全文模型路径，开放只读词法查阅；混合检索未启用**。证据遗漏和保护路由证明需要回退，不代表模型质量提升。

## 4. 本地复现

从项目根目录运行，不连接生产数据库或模型服务：

```powershell
.\.venv\Scripts\python.exe -m eval.retrieval_benchmark --generate-fixtures
```

记录输出目录，再用该目录中的同一数据集运行另一 split。以下为本轮实际路径，新生成时替换目录：

```powershell
.\.venv\Scripts\python.exe -m eval.retrieval_benchmark --dataset artifacts/eval/retrieval/retrieval-20261105T031545Z-8076634c/synthetic_retrieval.jsonl --split test
```

`--context-chars`、`--fulltext-chars`、`--top-k` 可做开发集实验；每次生成独立目录并保存策略。调整参数后先固定方案再评价后续集。工具拒绝非 synthetic 数据，没有真实 provider 或发送消息入口。

浏览器集成验证在三个终端分别运行：

```powershell
# 根目录，终端一
.\.venv\Scripts\python.exe -m uvicorn scripts.run_hr_demo:build_demo_app --factory --host 127.0.0.1 --port 18081

# 终端二
cd web/hr-review
$env:HR_API_PROXY_TARGET="http://127.0.0.1:18081"
$env:VITE_HR_DEMO="true"
node.exe node_modules/vite/bin/vite.js --host 127.0.0.1 --port 15174 --strictPort

# web/hr-review，终端三
node.exe scripts/check-evidence-demo.mjs --retrieval
```

脚本仅访问本地演示地址，校验实际 API 响应、精确原文、高亮唯一性及审核意见保留；不提交审核，结束时注销并关闭浏览器。服务前台 Ctrl+C 停止。本轮验证后两个服务均已关闭。

截图：[检索结果](../artifacts/screenshots/stage4-retrieval-results.png)、[精确原文高亮](../artifacts/screenshots/stage4-retrieval-highlight.png)。演示仍为历史营销/财务样本；AI/FDE 页面种子改造属于第 7 阶段，当前 AI/FDE 查询通过合成工具验证。

## 5. 验证、部署及后续

后端 349 个测试、前端 31 个测试、类型检查及生产构建通过；Python 编译、空白检查和实际 API + Chrome 演示通过。覆盖跨候选人/版本拒绝、旧数据兼容、只读查询、原文更新仍使用历史快照、Unicode/CRLF、未知事实、预算回退、向量索引失效、空结果与重试。

JS 单 chunk 为 1282.72 kB，保留既有体积警告；两条既有 Python 依赖弃用警告仍在。Windows 构建使用工作区 `tmp/upgrade-build` 临时目录，避开本机系统临时目录的既有权限问题。

没有新增数据库迁移，仍需第 3 阶段的 `20261105_04`。OpenAPI 与生成的 TS 类型已同步。两个 API Docker 模板已增加查询配置复制，但没有构建/发布镜像；真实环境文件权限和查询性能未验证。

下一开发阶段为持久化任务、幂等领取、租约及过期结果保护，先用临时 SQLite/故障模拟验证工具语义；真实 MySQL 并发、provider 接入与生产效果单列验收。所有真实质量指标和上线决策继续留空。
