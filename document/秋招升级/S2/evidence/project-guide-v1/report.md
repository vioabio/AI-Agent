# 三种工具配置的开发对照

固定分母6条；工程检查6条通过，人工语义验收待完成。

| 模式 | 案例 | 完成/回读 | 工具边界 | 调用数 | 输入/输出Token | 耗时ms | 引文逐字匹配 | 指定文档原文出现 |
| --- | --- | --- | --- | ---: | --- | ---: | --- | --- |
| MODEL_ONLY | ABLATION-DEFAULTS | true | true | 2 | 2512/675 | 16478 | 0/1 | false |
| MODEL_ONLY | ABLATION-CONFIRMATION | true | true | 2 | 2902/761 | 19063 | 0/0 | false |
| GUIDES_ONLY | ABLATION-DEFAULTS | true | true | 1 | 4905/371 | 12646 | 4/4 | true |
| GUIDES_ONLY | ABLATION-CONFIRMATION | true | true | 1 | 5068/425 | 10421 | 4/4 | true |
| READ_ONLY_TOOLS | ABLATION-DEFAULTS | true | true | 1 | 6867/568 | 14770 | 4/4 | true |
| READ_ONLY_TOOLS | ABLATION-CONFIRMATION | true | true | 1 | 7030/400 | 10000 | 4/4 | true |

Same configured model and prompts, differing tool availability and program evidence guards; literal corpus matching is not citation relevance, semantic quality, causal RAG gain or heldout acceptance
费用未与实际账单核对，不生成金额或降本比例。
