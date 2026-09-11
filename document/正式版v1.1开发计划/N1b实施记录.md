# N1b：评测门禁后的知识发布（已实现的最小闭环）

更新时间：2026-09-03

## 已实现

- `rag/promotion/ReleasePromotionRequest` 将 `dataReleaseId`、`knowledgeReleaseId`、`indexReleaseId`、`evaluationRunId`、`reportHash` 和 `reviewer` 设为显式必填字段；`unknown`/`untraceable` 等占位值直接拒绝。
- `ReleaseQualityGate` 先按 evaluationRunId 回读 `EvaluationReport`，再校验报告 hash、三段 release 血缘、suite/model 版本、不可变标记、样本/指标/阈值证据，并对 `>=`、`>`、`<=`、`<`、`==` 做程序化阈值断言。没有报告仓储或报告读取异常时 fail-closed。
- `KnowledgeReleaseApproval` 是独立的追加式审批事实，不复用 `DocumentVersion.reviewStatus`。本地使用 `InMemoryKnowledgeReleaseApprovalRepository`，生产使用 `JdbcKnowledgeReleaseApprovalRepository`。
- `KnowledgePromotionService` 只有在候选状态、质量门禁和 approval 回读全部通过后才调用 `KnowledgeCatalog.activateIndex`；审批 ID 按请求证据确定性生成，重复提交复用同一审批。
- 新增 `POST /knowledge/indexes/{indexReleaseId}/promote`；旧 `/publish` 保留兼容，但 Spring 装配 promotion 服务时不再允许无证据切换 active。
- 新增 `V23__knowledge_release_approval.sql`：审批表、自然键唯一约束、索引、append-only 触发器和公共角色 UPDATE/DELETE 撤销。

## 验证

- `N1bPromotionTest` 覆盖：默认 fail-closed、精确血缘与报告 hash、阈值检查、候选不被污染、重复 promotion 幂等。
- N1b 源码使用 Java 21 source-first `javac` 编译通过（退出码 0）；Windows 本地 Maven 仓库的 jar 关闭时会输出已知 `AccessDeniedException` 尾部噪音，不代表源码编译失败。
- 已检查 Flyway 文件版本无重复（V24 为当前最高版本）；真实 PostgreSQL `flyway validate/migrate` 尚未在本环境执行。

## 当前边界

- 评测报告仓储当前提供内存适配器；生产 profile 没有自动生成报告的 API/流水线，未配置可信报告仓储时 promotion 必然被拒绝。这是安全的 fail-closed 状态，不应宣称已完成线上发布。
- `KnowledgeCatalog.activateIndex` 仍是底层迁移/回滚接口；业务 HTTP 发布必须使用 `KnowledgePromotionService`。后续可在数据库权限层进一步限制应用角色直接更新 active 指针。
- PostgreSQL 原子切换、并发实例、备份回滚和真实 Golden Set 证据需在 N6a/N7 staging 环境补充验证。
