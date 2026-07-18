# 🎮 VIO AI Agent — 企业级宝可梦智能体

> 从 Demo 到生产级：全世代宝可梦 AI 顾问 | 自建 MCP 协议栈 | 四层安全防护 | Plan-and-Execute 多智能体协作 | 192 测试全通过

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-green.svg)](https://spring.io/projects/spring-ai)
[![Vue](https://img.shields.io/badge/Vue-3.4-4FC08D.svg)](https://vuejs.org/)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

---

## 📋 目录

- [项目简介](#-项目简介)
- [核心亮点](#-核心亮点)
- [系统架构](#-系统架构)
- [技术栈](#-技术栈)
- [项目结构](#-项目结构)
- [快速启动](#-快速启动)
- [API 文档](#-api-文档)
- [部署指南](#-部署指南)
- [CI/CD](#-cicd)
- [文档索引](#-文档索引)
- [项目规模](#-项目规模)
- [贡献指南](#-贡献指南)

---

## 📋 项目简介

VIO AI Agent 是一个**企业级 AI 智能体平台**，以宝可梦游戏为应用场景，展示从 Demo 级到生产级的完整 AI Agent 工程实践。

### 应用场景

| 场景 | 说明 |
|------|------|
| 🎯 **AI 宝可梦大师** | 929 只宝可梦图鉴查询 + 612 个技能数据库 + 303 条进化链 + 18 种属性克制实时计算 |
| 🤖 **AI 超级智能体** | 基于 ReAct 模式的自主规划智能体，可联网搜索、网页抓取、资源下载、生成 PDF 报告 |
| 🛠️ **MCP 服务** | 自建 JSON-RPC 2.0 MCP 协议栈，支持 STDIO / SSE 双传输模式 |

---

## ✨ 核心亮点

### 🏗️ 自建 MCP 协议栈

不依赖 Spring AI MCP 封装，**从零实现完整的 JSON-RPC 2.0 协议栈**：

```
┌─────────────────────────────────────────┐
│            MCP Gateway (会话管理层)        │
├─────────────────────────────────────────┤
│  JSON-RPC 2.0 编解码  │  MCP 协议生命周期  │
├─────────────────────────────────────────┤
│   STDIO Transport     │   SSE Transport   │
└─────────────────────────────────────────┘
```

- ✅ 完整的 JSON-RPC 2.0 请求/响应/错误编解码（13 个错误码）
- ✅ MCP 协议生命周期：`Initialize → ToolsList → ToolsCall`
- ✅ 双传输模式：STDIO（进程通信）+ SSE（HTTP 流式）
- ✅ Spring AI 桥接层：无缝对接 Spring AI ToolCallback 生态

### 🔒 四层安全防护

```
API Key 认证 → HITL 人机审批 → 路径围栏 → 审计追溯
```

| 层级 | 组件 | 能力 |
|------|------|------|
| **L1 认证** | `ApiKeyAuthInterceptor` | API Key 分域管理，令牌绑定协议 |
| **L2 审批** | `HitlManager` | 高危操作人工确认（DangerLevel：LOW/MEDIUM/HIGH/CRITICAL） |
| **L3 围栏** | `PathGuard` + `CommandGuard` | 文件路径白名单 + 命令黑名单（内置 9 条危险命令拦截） |
| **L4 审计** | `AuditLogger` + `ParameterMasker` | 结构化审计日志 + 敏感参数脱敏 |

### 🧠 Plan-and-Execute 多智能体协作

```
用户输入 → Planner（任务规划）→ Worker（并行执行）→ Reviewer（质量审查）→ 最终输出
```

- **Planner Agent**：将复杂任务拆解为 DAG 子任务
- **Worker Agent**：并行执行子任务，支持依赖管理
- **Reviewer Agent**：审查执行结果，决定是否重新规划
- **Orchestrator**：全局编排，管理会话状态机

### 💾 三层记忆系统

| 记忆类型 | 存储 | 容量 | 策略 |
|----------|------|------|------|
| **短期记忆** | 内存 | 4000 tokens | 滑动窗口 + 自动截断 |
| **长期记忆** | 文件（Kryo 序列化） | 无限制 | Top-K 语义检索召回 |
| **工作记忆** | Agent 状态机 | 会话生命周期 | 上下文压缩 + 任务链追踪 |

### 🛡️ 生产级会话编排

- **会话状态机**：IDLE → PLANNING → EXECUTING → REVIEWING → COMPLETED / FAILED
- **并发控制**：Agent 线程池隔离 + `AgentConcurrencyGuard` 限流
- **超时重试**：分类重试策略（网络 3 次 / 文件操作 0 次 / 幂等安全的方法重试）
- **幂等管理**：`IdempotencyManager` 确保重复请求安全

### 📊 可观测性

- **健康检查**：`/api/health` + `/api/ready`（含 DB 连接检测）
- **指标监控**：`/api/metrics` → Prometheus → Grafana（3 面板仪表板 + 4 条告警规则）
- **零外部依赖指标**：基于 JDK `AtomicInteger`/`AtomicLong` 自建，无需 Micrometer
- **结构化日志**：Logback MDC 注入 traceId/sessionId，生产环境 JSON 格式输出
- **结构化审计日志**：所有工具调用 + 审批操作可追溯

### ⚡ SSE 流式架构

- **全链路流式**：后端 `Flux<String>` / `SseEmitter` → 前端 `EventSource`（原生 SSE，非 WebSocket）
- **Nginx 优化**：`proxy_buffering off` + `chunked_transfer_encoding off` + 600s 超时
- **智能体流式推送**：VioManus 每步执行结果实时推送到前端
- **API Key 注入**：前端通过 URL 参数注入（SSE `EventSource` 不支持自定义 Header）

### 🔧 工程化细节

- **环境自适应前端**：自动切换 `localhost:8123`（开发）/ `/api`（生产 Nginx 代理）
- **OpenAPI 自动生成**：后端 SpringDoc → 前端 `openapi-typescript-codegen` 生成 TS 客户端
- **配置安全**：`application-local.yml` 被 `.gitignore` 忽略，提供 `.example.yml` 模板
- **优雅关闭**：`GracefulShutdown` 处理 Spring 上下文关闭事件，确保资源释放
- **Profile 分级**：`local`（关闭安全/编排/记忆，内存存储）vs `prod`（全开，JSON 日志）

---

## 🏗️ 系统架构

```
                              ┌──────────────────────┐
                              │   Vue 3 前端 (Vite)   │
                              │   localhost:3000      │
                              └──────────┬───────────┘
                                         │ HTTP / SSE
                              ┌──────────▼───────────┐
                              │   Spring Boot 3.5.16  │
                              │   REST Controller     │
                              │   port: 8123          │
                              └──────────┬───────────┘
                                         │
          ┌──────────────────────────────┼──────────────────────────────┐
          │                              │                              │
┌─────────▼─────────┐    ┌───────────────▼───────────────┐    ┌────────▼────────┐
│  AI 宝可梦大师     │    │  VioManus 超级智能体          │    │  安全过滤链      │
│  (GameApp)        │    │  (ReAct Agent)               │    │  Auth → HITL     │
│  RAG + Tools +    │    │  自主规划 → 工具调用 → 审查   │    │  → Guard → Audit │
│  MCP              │    └───────────────┬───────────────┘    └─────────────────┘
└──────────────────┘                    │
          │                             │
┌─────────▼─────────────────────────────▼──────────────┐
│              Spring AI 1.0.0 / LangChain4j           │
│   ChatClient · ToolCallback · Advisor · ChatMemory   │
└─────────┬───────────────────────────┬───────────────┘
          │                           │
┌─────────▼─────────┐   ┌─────────────▼─────────────┐
│   DashScope (Qwen) │   │   Ollama (Gemma/Llama)    │
│   云大模型          │   │   本地大模型               │
└───────────────────┘   └───────────────────────────┘

工具层:
┌──────────────────────────────────────────────────────────┐
│ WebSearch │ WebScraping │ FileOp │ Terminal │ PDF │ ...  │
│ MCP Server (vio-image-search) │ PokeTypeTool            │
│ PostgreSQL + PGVector (RAG 向量知识库)                   │
└──────────────────────────────────────────────────────────┘
```

---

## 🛠️ 技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **语言** | Java | 21 | Record、虚拟线程、Switch 表达式 |
| **基础框架** | Spring Boot | 3.5.16 | 企业级应用框架 |
| **AI 框架** | Spring AI | 1.0.0 GA | 核心 AI 开发框架 |
| **AI 框架** | LangChain4j | 1.0.0-beta2 | 多框架互补 |
| **大模型** | DashScope (Qwen-Plus) | — | 阿里云百炼大模型平台 |
| **本地模型** | Ollama (Gemma 3) | — | 离线推理 / 开发调试 |
| **前端** | Vue 3 + Vite | 3.4 / 5.4 | 响应式 UI 框架 |
| **HTTP 客户端** | Axios | 1.7 | 前端 HTTP 请求 |
| **向量数据库** | PostgreSQL + PGVector | 16 | 向量存储与语义检索 |
| **序列化** | Kryo | 5.6.2 | 对话记忆高性能持久化 |
| **网页抓取** | Jsoup | 1.19.1 | HTML 解析与内容提取 |
| **PDF 生成** | iText | 9.1.0 | PDF 文档生成 |
| **工具库** | Hutool | 5.8.37 | Java 工具类库 |
| **API 文档** | SpringDoc + Swagger UI | 2.7.0 | OpenAPI 3.0 接口文档 |
| **容器化** | Docker + Docker Compose | — | 一键部署 |
| **监控** | Prometheus + Grafana | — | 指标采集与可视化（可选启用） |
| **测试** | JUnit 5 + Spring Boot Test | — | 192 个测试用例 |

---

## 📁 项目结构

```
vio-ai-agent/
├── src/main/java/com/vio/vioaiagent/
│   ├── VioAiAgentApplication.java      # 应用入口
│   ├── controller/                     # REST API 控制层
│   │   ├── AiController.java           # AI 对话 + VioManus 智能体接口
│   │   ├── HealthController.java       # 健康检查（/health）
│   │   ├── ReadyController.java        # 就绪探针（/ready）
│   │   ├── HitlController.java         # HITL 人机审批接口
│   │   └── MetricsController.java      # 指标采集接口
│   ├── agent/                          # 🤖 AI 智能体继承体系
│   │   ├── BaseAgent.java              #   Agent 抽象基类
│   │   ├── ReActAgent.java             #   ReAct 模式（Thought → Action → Observe）
│   │   ├── ToolCallAgent.java          #   工具调用分发
│   │   └── VioManus.java               #   自主规划超级智能体
│   ├── app/                            # 宝可梦大师对话应用
│   │   └── GameApp.java                #   5 种对话模式（基础/RAG/工具/MCP × 同步/SSE）
│   ├── mcp/                            # 🔧 自建 MCP 协议栈
│   │   ├── jsonrpc/                    #   JSON-RPC 2.0 编解码（5 文件）
│   │   ├── protocol/                   #   MCP 协议模型 + 生命周期
│   │   ├── transport/                  #   STDIO / SSE 传输层
│   │   ├── gateway/                    #   会话管理 + MCP 网关
│   │   └── bridge/                     #   Spring AI ToolCallback 桥接
│   ├── security/                       # 🔒 四层安全防护
│   │   ├── ApiKeyAuthInterceptor.java  #   L1: API Key 认证
│   │   ├── HitlManager.java            #   L2: 人机审批
│   │   ├── PathGuard.java              #   L3: 路径围栏
│   │   ├── CommandGuard.java           #   L3: 命令围栏
│   │   ├── AuditLogger.java            #   L4: 审计日志
│   │   └── ParameterMasker.java        #   敏感参数脱敏
│   ├── multiagent/                     # 🧠 Plan-and-Execute 多智能体
│   │   ├── MultiAgentOrchestrator.java #   主编排器
│   │   ├── PlannerAgent.java           #   任务规划智能体
│   │   ├── WorkerAgent.java            #   任务执行智能体
│   │   ├── ReviewerAgent.java          #   质量审查智能体
│   │   └── model/                      #   任务模型 (Task, ExecutionPlan, TaskResult)
│   ├── orchestration/                  # 🔄 会话编排与容错
│   │   ├── SessionStateMachine.java    #   会话状态机
│   │   ├── AgentConcurrencyGuard.java  #   并发限流
│   │   ├── ToolRetryStrategy.java      #   分类重试策略
│   │   ├── ToolTimeoutManager.java     #   超时管理
│   │   └── IdempotencyManager.java     #   幂等管理
│   ├── memory/                         # 💾 三层记忆系统
│   │   ├── model/                      #   记忆模型 (MemoryType, MemoryEntry)
│   ├── observability/                  # 📊 可观测性
│   │   ├── AgentMetrics.java           #   原子计数器指标（执行/工具/Token/SSE）
│   │   ├── AgentLogContext.java        #   MDC traceId/sessionId 注入
│   │   └── ObservabilityFilter.java    #   HTTP 请求日志过滤器
│   ├── rag/                            # 📚 RAG 知识库
│   │   ├── GameAppDocumentLoader.java  #   宝可梦文档 ETL 加载
│   │   ├── PgVectorVectorStoreConfig.java  # PGVector 配置
│   │   ├── QueryRewriter.java          #   查询重写
│   │   └── GameAppContextualQueryAugmenterFactory.java  # 上下文增强
│   ├── tools/                          # 🛠️ 工具集
│   │   ├── WebSearchTool.java          #   联网搜索
│   │   ├── WebScrapingTool.java        #   网页抓取
│   │   ├── FileOperationTool.java      #   文件操作
│   │   ├── TerminalOperationTool.java  #   终端操作
│   │   ├── ResourceDownloadTool.java   #   资源下载
│   │   ├── PDFGenerationTool.java      #   PDF 生成（中文支持）
│   │   ├── PokeTypeTool.java           #   宝可梦 18 属性克制矩阵
│   │   └── TerminateTool.java          #   终止工具
│   ├── config/                         # ⚙️ 配置 (CORS / OpenAPI / Security)
│   ├── advisor/                        # ChatClient 自定义 Advisor（日志 / 复读）
│   ├── chatmemory/                     # 对话记忆持久化（Kryo）
│   ├── demo/                           # 📖 学习示例（4 种大模型调用方式）
│   └── common/                         # 公共基础设施
│       ├── Result.java                 #   统一 API 响应封装
│       ├── AgentErrorCode.java         #   65+ 错误码枚举（9 类别）
│       ├── RequestContext.java         #   ThreadLocal 请求上下文
│       └── GracefulShutdown.java       #   优雅关闭
├── src/main/resources/
│   ├── application.yml                 # 主配置
│   ├── application-local.yml           # 本地环境配置（gitignore）
│   ├── application-prod.yml            # 生产环境配置
│   └── application-local.example.yml   # 配置模板
├── src/test/                           # 测试（192 个用例）
├── vio-ai-agent-frontend/              # Vue 3 前端
│   ├── src/
│   │   ├── views/
│   │   │   ├── Home.vue                # 首页
│   │   │   ├── GameMaster.vue          # AI 宝可梦大师
│   │   │   └── SuperAgent.vue          # AI 超级智能体
│   │   ├── components/
│   │   │   ├── ChatRoom.vue            # 聊天室组件
│   │   │   └── AiAvatarFallback.vue    # AI 头像
│   │   ├── router/index.js             # Vue Router 路由配置
│   │   └── api/index.js                # Axios API 封装
│   ├── Dockerfile                      # 前端 Nginx 容器
│   └── package.json                    # 前端依赖
├── vio-image-search-mcp-server/        # MCP 图片搜索服务
├── .github/workflows/                   # GitHub Actions CI/CD
│   ├── ci.yml                           #   持续集成（构建 + 测试）
│   └── deploy.yml                       #   自动部署（Docker Compose）
├── docker-compose.yml                  # 一键部署编排
├── Dockerfile                          # 后端容器
├── .env.example                        # 环境变量模板
├── prometheus.yml                      # Prometheus 指标采集配置
├── alert-rules.yml                     # 告警规则（4 条）
├── grafana-dashboard.json              # Grafana 仪表板（3 面板）
├── pom.xml                             # Maven 依赖管理
└── 开发记录/                            # 📖 完整学习文档（17 篇）
```

---

## 🚀 快速启动

### 前置要求

- **JDK 21+**
- **Maven 3.8+**（或使用项目自带的 Maven Wrapper `./mvnw`）
- **Node.js 18+**（前端开发）
- **PostgreSQL + PGVector**（可选，RAG 知识库需要）
- **DashScope API Key**（阿里云百炼，[免费申请](https://dashscope.aliyun.com/)）

### 1. 克隆项目

```bash
git clone https://github.com/vioviovio/vio-ai-agent.git
cd vio-ai-agent
```

### 2. 配置 API Key

```bash
# 复制配置模板
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml

# 编辑 application-local.yml，填入你的 DashScope API Key
# dashscope.api-key: your-api-key-here
```

### 3. 启动后端

```bash
# 使用 Maven Wrapper（无需安装 Maven）
./mvnw spring-boot:run

# 后端启动在 http://localhost:8123/api
# Swagger UI: http://localhost:8123/api/swagger-ui.html
```

### 4. 启动前端（可选）

```bash
cd vio-ai-agent-frontend
npm install
npm run dev

# 访问 http://localhost:3000
```

### 5. 启动 RAG 知识库（可选）

```bash
# 启动 PostgreSQL + PGVector
docker-compose up -d postgres

# 切换配置文件以启用 PGVector
# 在 application-local.yml 中设置：
#   spring.profiles.active: local,pgvector
```

---

## 📡 API 文档

启动后端后，访问 Swagger UI 查看完整 API 文档：

> **http://localhost:8123/api/swagger-ui.html**

### 主要接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ai/game_app/chat/sync` | GET | 同步对话（基础） |
| `/api/ai/game_app/chat/rag/sync` | GET | 同步对话（RAG 增强） |
| `/api/ai/game_app/chat/tools/sync` | GET | 同步对话（工具调用） |
| `/api/ai/game_app/chat/mcp/sync` | GET | 同步对话（MCP 服务） |
| `/api/ai/game_app/chat/sse` | GET | SSE 流式对话 |
| `/api/ai/game_app/chat/rag/sse` | GET | SSE 流式对话（RAG） |
| `/api/ai/game_app/chat/tools/sse` | GET | SSE 流式对话（工具调用） |
| `/api/ai/game_app/chat/mcp/sse` | GET | SSE 流式对话（MCP） |
| `/api/ai/manus/chat` | GET | VioManus 超级智能体（SSE） |
| `/api/ai/manus/stop` | GET | 停止智能体 |
| `/api/health` | GET | 健康检查 |
| `/api/ready` | GET | 就绪探针 |
| `/api/metrics` | GET | 指标采集 |

### 前端路由

| 路由 | 页面 | 说明 |
|------|------|------|
| `/` | Home | 首页 / 导航 |
| `/game-master` | AI 宝可梦大师 | 宝可梦智能对话 |
| `/super-agent` | AI 超级智能体 | VioManus 自主规划 |

---

## 🐳 部署指南

### Docker Compose 一键部署

```bash
# 1. 构建后端 JAR
./mvnw clean package -DskipTests

# 2. 构建前端
cd vio-ai-agent-frontend && npm install && npm run build && cd ..

# 3. 启动全部服务
AI_DASHSCOPE_API_KEY=your-key docker-compose up -d

# 4. 访问
# 前端: http://localhost
# 后端: http://localhost:8123/api
# Swagger: http://localhost:8123/api/swagger-ui.html
```

### 启用监控（可选）

```bash
docker-compose --profile monitoring up -d

# Grafana: http://localhost:3001 (admin/admin)
# Prometheus: http://localhost:9090
```

### 服务架构

```
┌──────────────────────────────────────────────┐
│                  Docker Compose               │
│                                               │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │ Frontend │  │ Backend  │  │ PostgreSQL │  │
│  │ :80      │  │ :8123    │  │ :5432      │  │
│  └──────────┘  └──────────┘  └────────────┘  │
│                                               │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐  │
│  │Prometheus│  │ Grafana  │  │AlertManager│  │
│  │ :9090    │  │ :3001    │  │ :9093      │  │
│  └──────────┘  └──────────┘  └────────────┘  │
│           (monitoring profile)                │
└──────────────────────────────────────────────┘
```

---

## 🔄 CI/CD

项目通过 GitHub Actions 实现持续集成与部署：

### CI 流水线（`.github/workflows/ci.yml`）

触发条件：`push` / `pull_request` 到 `main` 分支

| 步骤 | 说明 |
|------|------|
| 检出代码 | `actions/checkout@v4` |
| JDK 21 环境 | `actions/setup-java@v4` (Eclipse Temurin 21) |
| 后端构建 | `./mvnw clean package -DskipTests` |
| 运行测试 | `./mvnw test` |
| 前端构建 | `npm ci && npm run build` |

### 部署流水线（`.github/workflows/deploy.yml`）

触发条件：`workflow_dispatch`（手动触发）或 `v*` 标签推送

| 步骤 | 说明 |
|------|------|
| 构建 JAR | `./mvnw clean package -DskipTests` |
| 构建前端 | `npm ci && npm run build` |
| Docker 部署 | `docker compose up -d --build` |
| 健康检查 | `curl` 轮询 `/api/health`（30s 超时） |

### 环境变量（`.env.example`）

| 变量 | 必填 | 说明 |
|------|------|------|
| `AI_DASHSCOPE_API_KEY` | ✅ | 阿里云百炼大模型 API Key |
| `SEARCH_API_KEY` | — | SearchAPI 联网搜索 Key |
| `DB_PASSWORD` | — | PostgreSQL 数据库密码（默认 `vio123`） |
| `AMAP_API_KEY` | — | 高德地图 API Key |
| `PEXELS_API_KEY` | — | Pexels 图片搜索 API Key |

---

## 📖 文档索引

### 学习路线与开发记录

| 文档 | 说明 |
|------|------|
| [📘 学习路线 — 从 0 到 1 全面指南](开发记录/学习路线-从0到1全面指南.md) | 完整学习路径 + 面试题 + 知识点脑图 |
| [📗 企业级升级文档](开发记录/企业级agent项目升级文档.md) | 11 个维度的企业级升级路线 |
| [📙 企业级升级步骤](开发记录/企业级agent升级步骤.md) | 分阶段实施记录与设计决策 |

### 分期教程

| 期数 | 文档 | 核心内容 |
|------|------|----------|
| 01 | [项目总览](开发记录/01-项目总览.md) | 项目介绍、技术选型、架构设计 |
| 02 | [AI 大模型接入](开发记录/02-AI大模型接入.md) | 4 种调用方式、Ollama 本地部署 |
| 03 | [AI 应用开发](开发记录/03-AI应用开发.md) | Prompt 工程、ChatClient、Advisor、ChatMemory |
| 04 | [RAG 知识库基础](开发记录/04-RAG知识库基础.md) | RAG 概念、本地/云端知识库实战 |
| 05 | [RAG 知识库进阶](开发记录/05-RAG知识库进阶.md) | ETL、向量存储、查询增强、最佳实践 |
| 06 | [工具调用](开发记录/06-工具调用.md) | 8 个工具开发、Tool Calling 高级特性 |
| 07 | [MCP 协议](开发记录/07-MCP协议.md) | MCP 概念、3 种使用方式、安全与部署 |
| 08 | [AI 智能体构建](开发记录/08-AI智能体构建.md) | ReAct 模式、Manus 原理、智能体工作流 |
| 09 | [AI 服务化](开发记录/09-AI服务化.md) | SSE 接口、Serverless 部署、前端代码生成 |

### 进阶文档

| 文档 | 说明 |
|------|------|
| [🔧 优化与扩展](开发记录/优化与扩展.md) | 性能优化、功能扩展思路 |
| [🐳 Docker 部署](开发记录/Docker.md) | 容器化详细指南 |
| [🔄 迁移指南](开发记录/游戏智能体迁移指南.md) | 从恋爱大师到宝可梦智能体的迁移 |
| [📋 开发记录](开发记录/开发记录.md) | 日常开发日志与问题记录 |

---

## 📊 项目规模

| 指标 | 数值 |
|------|------|
| 源文件 | 122+ |
| 测试用例 | 192 |
| 测试通过率 | 100% |
| 核心包数 | 18 |
| MCP 协议自建文件 | 15+ |
| 安全模块文件 | 10+ |
| 多智能体文件 | 8+ |
| 内置工具 | 8 |
| 错误码定义 | 65+（9 个类别） |
| 开发文档 | 17 篇（~550KB） |
| 新增依赖 | 0（纯 Spring Boot + Spring AI 生态） |

---

## 🧪 运行测试

```bash
# 运行全部测试
./mvnw test

# 运行指定模块测试
./mvnw test -pl vio-image-search-mcp-server

# 运行并生成测试报告
./mvnw test jacoco:report
```

---

## 🤝 贡献指南

本项目为学习型项目，欢迎提 Issue 或 PR 讨论交流：

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交改动 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 开启 Pull Request

### 开发规范

- Java 代码遵循阿里巴巴 Java 开发手册
- 前端使用 ESLint + Prettier 统一格式
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范

---

## 📄 许可证

本项目基于 Apache 2.0 许可证开源。

---

## 🙏 致谢

- [Spring AI](https://spring.io/projects/spring-ai) — Java 生态 AI 开发框架
- [阿里云百炼](https://dashscope.aliyun.com/) — 大模型服务平台
- [Ollama](https://ollama.com/) — 本地大模型部署
- [PGVector](https://github.com/pgvector/pgvector) — PostgreSQL 向量扩展

---

<p align="center">
  <b>⭐ 如果这个项目对你有帮助，欢迎 Star 支持！</b><br>
  <sub>Built with ❤️ by VIO</sub>
</p>
