# v1.1 最小宝可梦 fixture

这是 N0/N1 的隔离 smoke 数据，不是生产全量数据，也不构成官方数据发布。输入经过脱敏，来源、许可和事实准确性仍需领域负责人审核；在审核完成前，`status` 保持 `candidate`，不得写入 active release。

- 版本键：`pokemon-scarlet-violet` / `the-hidden-treasure-of-area-zero` / `zh-CN`
- 数据发布：`pokemon-scarlet-violet-data-v1`
- 规则集：`story-v1`、`casual-v1`（VGC 记录为待审核）
- 文件：`fixture.json`（机器输入）、`fixture.sql`（隔离 PostgreSQL 导入）
- 许可：待审核；请在正式发布前替换为获批准的数据源和许可证记录。
