# 三种工具配置的开发对照

固定分母6条；工程检查6条通过，人工语义验收待完成。

| 模式 | 案例 | 完成/回读 | 工具边界 | 调用数 | 输入/输出Token | 耗时ms | 引文逐字匹配 | 指定文档原文出现 |
| --- | --- | --- | --- | ---: | --- | ---: | --- | --- |
| MODEL_ONLY | ABLATION-DEFAULTS | true | true | 2 | 2428/561 | 14255 | 0/0 | false |
| MODEL_ONLY | ABLATION-CONFIRMATION | true | true | 2 | 2838/753 | 17920 | 0/0 | false |
| GUIDES_ONLY | ABLATION-DEFAULTS | true | true | 1 | 4905/570 | 15587 | 1/1 | true |
| GUIDES_ONLY | ABLATION-CONFIRMATION | true | true | 1 | 5068/353 | 10574 | 1/1 | true |
| READ_ONLY_TOOLS | ABLATION-DEFAULTS | true | true | 1 | 6867/519 | 13544 | 1/1 | true |
| READ_ONLY_TOOLS | ABLATION-CONFIRMATION | true | true | 1 | 7030/453 | 11645 | 1/1 | true |

Same configured model and prompts, differing tool availability and program evidence guards; literal corpus matching is not citation relevance, semantic quality, causal RAG gain or heldout acceptance
费用未与实际账单核对，不生成金额或降本比例。
