# 物种与主题检索开发探针

`retrieval-dev-v1.json` 在运行前固定8条探针：两种成员、英文名称、通用培养、保存流程、伤害条件、错误赛制和无关问题。来源是14篇新版资料，原S3的13/24条种子及其标注不改。

正例只标注必要命中文档；`excludedDocumentIds` 单列已知不切题来源，正例命中不能抵消错误召回。片段回读逐一核对原文、偏移、摘要、来源登记和适用元数据。八条由AI辅助编写并待人工审核，不是完整相关性标注、独立保留集或策略正确率。

```powershell
node tools/campus/s3-retrieval.mjs http://127.0.0.1:18162/api artifacts/campus/species-guides/my-retrieval evaluation/campus/s3-species/retrieval-dev-v1.json
```

先启动独立本地账号服务并使用新版资料；采集器创建独立测试账号，只读取该资料索引，不调用模型。实际结果与边界见[实施记录](../../../document/秋招升级/S3/物种培养资料与主题检索_2026-10-26.md)。
