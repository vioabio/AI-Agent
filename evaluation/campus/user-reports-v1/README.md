# 用户反馈开发回归 v1（暂存）

本目录是独立的两题开发回归，来源于[候选队列](../question-intake/README.md)中的杰尼龟、雪妖女反馈。两组问题都只用于开发，没有保留切分，不改写原36题或22条保留集。`newFormalAdmissions: 0`，不把正式题数写成38或100。

## 输入与来源

| 案例 | 输入方式 | 来源边界 |
| --- | --- | --- |
| S2-GUIDES-09 | 用户原话：杰尼龟、当前版本策略及强势阵容 | 本地缓存没有实时阵容/赛事证据，应明确限制 |
| S2-GUIDES-10 | 用当前图鉴按钮的 `pokemonFactQuestion` 生成雪妖女可读提问与事实卡片 | 原始应用提问另存，重放不是原旧JSON消息逐字复现 |

`sources.json` 保存两个公开参考投影、原始缓存响应摘要、URL及抓取时间，并复制冻结的12篇项目笔记作为审核依据。原始完整Pokemon/species响应只在本机公开缓存包中，不重复入库。

杰尼龟缓存抓取于2026-10-21，雪妖女缓存抓取于2026-10-23；打包时间不代表上游重新获取。Pokemon/species字段可支持类型、种族值及资源名称，不能证明特性机制、完整配招合法性或当前热门程度。`sources.capturedAt`为本次材料组装时间；原始时间保留在`pokemonSnapshot.retrievedAt`和`speciesSnapshot.retrievedAt`。

两题的事实基线、验收项和分组由AI辅助整理，人工审核保持pending。与既有题目语义去重、独立机制审核及正式入集尚未完成，不能视为两个独立无重叠的正式业务样本。

## 运行

从根目录校验：

```powershell
node tools/campus/s2-evaluate.mjs validate --suite evaluation/campus/user-reports-v1
node --test tools/reference-cache.test.mjs tools/campus/*.test.mjs
```

真实重放使用独立 `local,evaluation` 实例，`vio.evaluation.tool-mode=READ_ONLY_TOOLS`，关闭v1、持久恢复、MCP和高风险工具；保持本地账号启用。用已有公开缓存导出杰尼龟/雪妖女的配对包，再导入全新的实例缓存目录。该包只包含Pokemon/species，不包含所有招式/特性资源；模型仍需要联网供应商。

```powershell
node tools/reference-cache.mjs export --cache .local-data/pokeapi --pokemon squirtle,froslass --out artifacts/campus/user-reports/my-public-bundle
node tools/reference-cache.mjs import --bundle artifacts/campus/user-reports/my-public-bundle --out artifacts/campus/user-reports/my-runtime/pokeapi
# 先按实施记录启动独立服务，禁止使用日常8123
node tools/campus/s2-evaluate.mjs run --suite evaluation/campus/user-reports-v1 --base-url http://127.0.0.1:18161/api --repetitions 2 --output artifacts/campus/user-reports/my-run
```

导入前需创建目标父目录，目标缓存目录必须不存在。为复现本批“只有固定参考缓存”的条件，设置资料代理为 `http://127.0.0.1:1`；这是明确的受限资料场景，不代表测试了公网搜索、全部外部资料或日常OFF模式。固定两题各两次，失败、超时和未执行都留在四次分母。

图鉴API在模型前后与固定投影核对；原始提问需要本轮成功的目标查询参数证据。雪妖女使用经核验的界面事实卡片，允许不重复读取图鉴，但卡片必须由当前固定生成函数原样构建。工具成功、实际参数、API事实和建议正确性分别判断；物种名出现也只是必要检查。

## 复算与审核

```powershell
node tools/campus/s2-evaluate.mjs report --suite evaluation/campus/user-reports-v1 --report artifacts/campus/user-reports/my-run/observations.json --output artifacts/campus/user-reports/my-recomputed
# 用浏览器/编辑器阅读原始回答及sources.json，实际填写reviews-template.json后另存
node tools/campus/s2-evaluate.mjs report --suite evaluation/campus/user-reports-v1 --report artifacts/campus/user-reports/my-run/observations.json --reviews ./my-reviews.json --output artifacts/campus/user-reports/my-reviewed
```

复算重新校验原事件摘要、问题、回答、观测绑定和参考检查。输出独立`report-producer.json`，区分采集器与复算器版本。实际人工记录才计入人工通过；`ai-assisted`只作辅助意见，负面标记也不冒充人工判分，旧观测不能给新答案复用。

首次资料包由 `build-user-report-seed.mjs` 依据候选、公开配对缓存和当前界面生成函数建立，已有目录拒绝覆盖。需要改题、更新快照或改界面模板时新建独立版本并保留旧材料，不能运行后换标签追求通过。

实施与边界见[真实反馈重放与事实保留修复](../../../document/秋招升级/S2/用户反馈重放与事实保留修复_2026-10-26.md)。
