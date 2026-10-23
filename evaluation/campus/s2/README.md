# S2 固定评测种子集 v1

这是秋招升级的第一版**来源关联种子集**：36 条案例、20 个问题组，开发集 14 条、保留集 22 条。它不是 100 条已人工验收的真实用户问题，也不替代已有 v1.0/v1.1 发布门禁。

| 类别 | 数量 | 主要核对对象 |
| --- | ---: | --- |
| 图鉴事实 | 6 | 捕获的 PokéAPI 数值及来源边界 |
| 攻略与引用 | 8 | 本地四篇原创笔记，结论与原文对应关系 |
| 队伍任务 | 6 | 读取、单项培养配置预览、等待确认、存储不变 |
| 伤害计算 | 4 | 固定 @smogon/calc 0.12.0 数值、输入假设 |
| 追问与限制 | 8 | 缺条件、正式规则、当前环境证据及未支持状态 |
| 安全与隔离 | 4 | 跨账号读取、修改、聊天代替确认、资料中的指令 |

相似问法绑定同一个 groupId，不得跨开发集与保留集。部分问题刻意采用不同措辞，因此报告必须同时给出案例数和问题组数，不能声称有 36 个独立业务场景。保留集仅是开发流程上的保留，不是对仓库维护者隐藏的题库。

- `manifest.json`：版本、样本量、SHA-256；首版目标仍为 100 条。
- `cases.json`：问题、前置条件、版本、必要工具、禁用工具、验收条目及标注来源。
- `sources.json`：捕获的公开图鉴字段、原创攻略全文、项目契约路径与摘要。图鉴捕获时保存原始响应摘要；仅保留需要核对的字段，不复制完整招式库。
- `fixtures.json`：独立测试账号使用的公开练习队伍和两组确定性引擎参考。
- `splits.json`：固定分组切分。不能按运行结果重新挑选通过案例。
- [标注规范](annotation.md)：区分确定性检查、模型建议、人类复核与未知项。

作者为 Codex，依据捕获来源与项目契约建立首版；`humanReview: pending` 表示尚未有人工逐项验收。性格解释以保存的原始枚举和词表分别核对，不能把默认 hardy 误说成“固执（无能力修正）”。种族值不是 50 级实际能力值；本地快照不是赛事规则或强度证明。

固定版本修改必须经过显式重新发布；运行脚本不会更新题目或标注。`build-s2-seed.mjs` 是一次性作者工具，检测到 manifest 后拒绝覆盖，不属于执行评测的前置步骤。需要改题时应创建新版本并保留旧报告的版本关系。

冻结 JSON 的摘要按 UTF-8/LF 文本计算，仅统一 CRLF 换行，不修剪空白或改写 JSON 值，避免 Windows Git 换行转换造成误报；运行 class、原始公开响应与原始证据的摘要仍按实际字节计算。

## 本地执行

从项目根目录执行，不需要安装新的 npm 依赖：

```powershell
node tools/campus/s2-evaluate.mjs validate
node --test tools/campus/evaluation.test.mjs tools/campus/s2-quality-regression.test.mjs
```

先按项目 README 启动一个独立的本地测试服务，例如端口 18127，并使用独立的账号、历史、队伍数据库。模型与工具由现有运行配置提供，脚本不读取私有 YAML，不接收 API Key，不改变用户正在使用的服务。

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--server.port=18127 --vio.local-accounts.path=./artifacts/campus/s2/runtime/accounts.sqlite --vio.local-history.path=./artifacts/campus/s2/runtime/history.sqlite --vio.local-teams.path=./artifacts/campus/s2/runtime/teams.sqlite'
```

在另一个终端执行小样本重复验证：

```powershell
node tools/campus/s2-evaluate.mjs run --base-url http://127.0.0.1:18127/api --case S2-FACTS-01,S2-GUIDES-01,S2-TEAM-01,S2-DAMAGE-01,S2-CLARIFICATION-01,S2-SAFETY-01 --repetitions 2 --output artifacts/campus/s2/my-run
```

省略 `--case` 会运行所选切分的所有案例，默认开发集、每题 2 次；这会产生真实供应商请求。每次输出必须使用新目录。读取 `/chat/runtime` 校验实际加载类摘要与本地编译产物一致；旧后端会拒绝采集，应先构建并重启测试后端。模型参数未获接口证明时明确记录 unknown/defaults，不能声称锁定了温度、种子或供应商实际返回型号。

该执行器只支持当前工具增强模式，不提供伪造的“纯模型”或“S3 检索增强”对照。预览案例**不提交批准**，核对预览差异与 SQLite 回读后取消任务。已确认保存、冲突和回读使用独立的 S1 脚本验证，不能把预览成功当成完整保存成功率。测试账号和队伍留在独立数据库用于复查，脚本结束会退出账号。

保留集须显式选择 `--split heldout`；开发调整后应发布新提示词/代码版本，再执行该版本的保留集，不能反复用保留集调参。

## 结果与复算

输出包含 `observations.json`、逐次原始事件、`summary.json`、`report.md` 和待填的 `reviews-template.json`。记录代码提交、脏工作区、源码摘要、加载类摘要、提示词版本、配置模型、数据版本、输入、答案、工具结果、状态与耗时。原始证据目录默认被 Git 忽略，需另行归档；仓库中的代表性记录见 [阶段验证记录](../../../document/秋招升级/S2/验证记录_2026-10-23.md)。

```powershell
node tools/campus/s2-evaluate.mjs report --report artifacts/campus/s2/my-run/observations.json --output artifacts/campus/s2/my-recomputed
```

人工复制并填写 review 模板的 reviewer、reviewedAt、note、每条判定及理由，之后：

```powershell
node tools/campus/s2-evaluate.mjs report --report artifacts/campus/s2/my-run/observations.json --reviews artifacts/campus/s2/my-human-reviews.json --output artifacts/campus/s2/my-reviewed
```

不要把 AI 自动复核或单元测试中的合成 reviewer 填成人工意见。复核绑定案例、轮次和观测摘要，不能复用到另一条回答。

当前已知测量边界：公开事件只有计算参数摘要，不能证明模型传入的所有字段与问题逐项一致；工具执行成功及答案数字出现仅是必要检查。人类没有足够原始输入证据时，对该条应标 unknown。检索排名/chunk 和供应商 usage 尚未接通，引用正确率、Recall@5、费用与真实调用次数不预填；后续分别由 S3、S4 补齐。

针对首轮已复现的引用/性格错误，另有[专项修复与回归](../../../document/秋招升级/S2/来源与配置回答修复_2026-10-23.md)。`s2-quality-regression.mjs` 检查固定开发题的来源和快照约束，不改变人工标注规则，也不能替代完整语义验收。
