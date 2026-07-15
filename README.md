# 🎮 VIO AI Agent — 企业级宝可梦智能体

> 全世代宝可梦 AI 顾问 | 自建 MCP 协议栈 | 四层安全防护 | 多智能体协作 | 192 测试全通过

## 📋 项目简介

VIO AI Agent 是一个**企业级 AI 智能体平台**，以宝可梦游戏为应用场景，展示从 Demo 级到生产级的完整 AI Agent 工程实践。

**核心能力**：
- 929 只宝可梦图鉴查询 + 612 个技能数据库 + 303 条进化链（RAG 知识库）
- 18 种属性克制关系实时计算（PokeTypeTool）
- 自建 JSON-RPC 2.0 MCP 协议栈
- 四层安全防护（认证/HITL审批/路径围栏/审计追溯）
- Plan-and-Execute 多智能体协作

## 🚀 快速启动

```bash
# 1. 配置 API Key
cp application-local.example.yml src/main/resources/application-local.yml

# 2. 启动后端
./mvnw spring-boot:run

# 3. 启动前端
cd vio-ai-agent-frontend && npm install && npm run dev
# 访问 http://localhost:3000
```

## 📊 项目规模

| 指标 | 数值 |
|------|------|
| 源文件 | 122 个 |
| 测试用例 | 192 个 |
| 测试通过率 | 100% |
| 新增依赖 | 0 个 |

## 📖 文档

| 文档 | 路径 |
|------|------|
| 学习路线+面试题 | `开发记录/学习路线-从0到1全面指南.md` |
| 升级步骤 | `开发记录/企业级agent升级步骤.md` |
| 迁移指南 | `开发记录/游戏智能体迁移指南.md` |
