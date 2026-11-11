# S3 来源绑定版本说明（2026-11-10）

新增 `src/main/resources/pokemon-guides/expanded-v2.json` 与 `retrieval-expanded-dev-v2.json`：复核 TeamBuild 的太晶字段扩展和 TeamWorkflowService 的提交隔离后，保留12篇原文及题目，重新绑定现有代码摘要，仍标记待人工语义复核。旧 v1 文件和其24题数据集保持冻结，不能声称旧摘要代表当前代码。

- `node tools/campus/audit-s3-corpus.mjs` 现在审计 v2；随机损坏摘要及越界路径仍失败。
- 使用新版开发题集时，隔离后端需明确指定 `--vio.local-guides.location=classpath:pokemon-guides/expanded-v2.json`，评测选择 `evaluation/campus/s3/retrieval-expanded-dev-v2.json`。正常业务默认语料不自动切换。
- 来源回归命令 `s3-source-regression.mjs` 默认核对 v2 身份；旧观测包不能冒充新版证据，旧计划与语料仍可用于历史对照。
- 正文未改、题目未换，因此这些仍是已知开发回归，不是新保留集。来源身份审计不代表人工策略质量验收。

下面保留历史版本的执行说明；当前业务实际语料以 `/api/pokemon/guides/catalog` 返回为准。

# S3 本地检索开发回归

当前默认后端加载12篇扩充资料。新24题及来源登记见[第二批说明](../../../document/秋招升级/S3/来源与语料扩充_2026-10-23.md)，使用 `retrieval-expanded-dev.json` 作为命令第三个参数。以下保留原4篇/13题基线；运行旧集时需启动后端并显式设置 `--vio.local-guides.location=classpath:pokemon-guides/index.json`。评测会核对实际加载的文件摘要，拒绝混用语料。

本批只验证固定 4 篇项目原创笔记的检索工程链路。`retrieval-dev.json` 是实现时编写的 13 条来源关联开发回归，其中 8 条相关问题、5 条无资料或范围不符问题；未经过独立人工验收，不是 S2 保留集，也不用于宣称全量效果提升。

数据文件绑定原攻略文件的 UTF-8/LF SHA-256。保留 S2 已冻结的题目、切分和来源文件；新增检索回归不能改写旧基线。资料增加或标注变化时，应新建数据版本，并保留旧结果。

在本地后端启动后运行（使用空的新输出目录）：

```powershell
node --test tools/campus/s3-retrieval.test.mjs
node tools/campus/s3-retrieval.mjs http://127.0.0.1:18132/api artifacts/campus/s3/my-retrieval
```

命令注册独立测试账号，调用真实 HTTP 检索、片段定位和运行身份接口；不调用模型，不保存密码、恢复码或 Cookie。真实结果写入 `retrieval-observations.json`，逐条保留原片段、排名、定位、失败及固定计划分母。接口类摘要应与本机编译产物相同。

指标定义：

- **前 5 个片段的文档召回率**：前 5 条 chunk 中命中的不同相关文档数 / 标注相关文档数，随后对正例求平均。它不是具备完整 chunk 相关性标注的 Recall@5；同一文档的多个 chunk 不重复计分。
- **MRR@5**：首个相关文档所在 chunk 的排名倒数，未召回记 0。
- **负例空召回率**：应为空的请求实际成功返回空数组的比例。HTTP 失败不能当作正确空召回。
- 引用来源核对：片段的标题、作者、日期、全文、UTF-16 偏移和内容摘要需与冻结原文匹配；核对失败的请求不给召回分。

四文档小库的前五召回很容易取得较高值，需同时看排名和负例。该结果不能替代引用语义正确率、真实用户问题、扩充后的复杂检索评测、纯模型对照或人工质量验收。
