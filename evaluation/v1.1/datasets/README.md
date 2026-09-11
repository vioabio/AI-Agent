# v1.1 评测数据导入说明

这里是 v1.1 评测契约使用的七套 JSONL 路径。文件由
`tools/normalize-evaluation-v1.1-datasets.mjs` 从 `evaluation/datasets/`
逐行迁移生成，迁移只做版本字段别名归一化，不新增、复制或改写样本的
`input`、`expected` 和断言内容。

## 版本字段

每行保留现有的 `caseId`/`suite`/`assertion` camelCase 契约，并增加
`schemaVersion: "v1.1"`。`versions` 中使用 v1.1 候选 registry 的稳定键：

| 字段 | 值/规则 |
|---|---|
| `gameReleaseId` | `pokemon-scarlet-violet`（`sv-v1`、`sv-1`、`scarlet-violet` 仅为旧别名） |
| `dlcId` | 未在 case 中显式选择篇章时为 `null`；总 DLC 候选为 `the-hidden-treasure-of-area-zero` |
| `language` | `zh-CN` |
| `battleFormatId` | 默认 `casual-singles`；`casual-doubles` 仅在 case 明确选择双打时使用 |
| `rulesetVersionId` | 默认 `casual-v1`（旧 `rules-v1` 映射到该候选） |
| `dataReleaseId` | `pokemon-scarlet-violet-data-v1`（旧 `data-v1` 仅为别名） |

映射细节、源文件/目标文件 hash 和各字段默认次数记录在
[`../dataset-version-mapping.json`](../dataset-version-mapping.json) 中。该映射
以及 support matrix 仍是 `pending-review` 候选，不代表领域负责人已经批准
registry；因此不得把这些样本宣称为生产黄金集。

## 规模与门禁

迁移前后每套样本数必须相同（当前仅为脱敏小样例：2/4/2/2/2/2/3），脚本
以 `noSampleExpansion` 明确记录这一点。七套数据的最低规模、来源许可、
人工审核和真实 evaluation run 尚未完成，`evaluation/v1.1/manifest.json`
保持 `draft`，`manifest.ready.json.blocked.json` 保持 `blocked`；任何发布门禁
仍必须失败闭锁。
