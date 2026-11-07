# 工具配置开发对照

固定计划为`manifest.json`，两个项目行为问题、三种工具配置，共6条计划任务。问题与来源关联由AI辅助编写，待独立人工语义审核；不改动原S2题集/保留集或S3资料与标注。

| 模式 | Run中实际可用能力 |
| --- | --- |
| MODEL_ONLY | 无工具、无MCP，通过同一Agent与预算/用量链调用模型 |
| GUIDES_ONLY | 只提供已有受治理的searchPokemonGuides |
| READ_ONLY_TOOLS | 提供攻略、图鉴/资源/整队参考、属性与伤害等明确白名单；不含队伍修改、外部搜索或运营工具 |

开关是进程配置`vio.evaluation.tool-mode`，请求体和提示词不能切换。默认OFF保留日常助手行为；非OFF要求显式`local,evaluation`、本地账号，关闭版本化主链、持久恢复、MCP与高风险工具。缺少该组所需工具或模型时拒绝，不静默变成其他配置或旧问答回退。

三组必须保持相同源码/class身份、模型配置、temperature/topP/maxTokens默认值、上下文和任务预算；只改变工具配置与各自隔离端口/数据库。工具可用性也会改变程序证据保护与回答方式，所以这是管线配置对照，不是单个检索算法的独立因果实验。temperature=0不代表供应商输出必然确定；运行接口展示的是配置默认值，不是完整供应商请求/部署版本的证明。

采集器新建独立账号，每题新会话，不自动确认审批，不读取个人Key配置，不保存密码、恢复码或Cookie。保留失败任务及原始事件/历史；输出目录必须尚不存在。运行时tools字段仍是注册清单，实际Run工具子集以evaluationPolicy.effectiveTools为准。

```powershell
# 完成Maven测试后，启动独立本地evaluation实例；temperature显式设0
# 使用独立账号/历史/队伍/攻略/图鉴目录，完整命令见实施记录
node tools/campus/s2-tool-ablation.mjs run http://127.0.0.1:18151/api MODEL_ONLY artifacts/campus/ablation/my-model
# 停止实例，使用相同代码和默认参数，切换模式和新的隔离目录/端口
node tools/campus/s2-tool-ablation.mjs run http://127.0.0.1:18152/api GUIDES_ONLY artifacts/campus/ablation/my-guides
node tools/campus/s2-tool-ablation.mjs run http://127.0.0.1:18153/api READ_ONLY_TOOLS artifacts/campus/ablation/my-tools
node tools/campus/s2-tool-ablation.mjs compare artifacts/campus/ablation/my-model/capture.json artifacts/campus/ablation/my-guides/capture.json artifacts/campus/ablation/my-tools/capture.json artifacts/campus/ablation/my-comparison
```

比较前重新验证事件和历史摘要、原始问题/回答、实际工具事件、用量及引文。报告区分工程一致性、原文逐字匹配和人工语义审核。标注指定文档中的原文出现不是完整引用相关性或准确率。Token不完整保持unknown，费用不与真实账单核对就不生成降本结论。

`review-template.json`绑定模式、案例和观测摘要；审核人、时间、逐项判定及依据默认空，不能用自动测试代替填写。后续可据此开展人工复核；当前不自动声明人类已验收。

实现与实测入口：[三种工具配置对照](../../../document/秋招升级/S2/三种工具配置与问答对照_2026-10-26.md)。

后续[项目流程来源修复](../../../document/秋招升级/S2/项目流程来源检索修复_2026-10-26.md)为有限中文项目说明补充程序触发检索，事件以`PROGRAM_PROJECT_SOURCE`标明；不能将它当作模型自主选择工具。固定manifest与原标注不变，旧采集保留，另用新目录重测。比较器按JSON对象内容核对配置，忽略键顺序，仍拒绝真实参数、数组顺序和源码身份差异；已有答案观测摘要不变。

实际语义和引用核对可使用[离线审核台](../reviews/README.md)，逐条阅读问题、回答与原文后导出结论。工程对照报告保持原样；新审核报告把原文未匹配、待审核、AI辅助和实际人工提交分别列出，不自动填充人工通过。
