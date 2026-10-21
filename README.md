# 🎮 VIO AI Agent — 企业级宝可梦智能体

> 面向朱/紫首发范围的企业级宝可梦智能体候选构建：版本化知识与确定性计算、受治理工具/MCP、唯一 Run 事件流、反馈评测与可回滚运维基线。真实生产依赖和发布门禁见 [`document/正式版v1.0开发计划/M2-M7实施完成记录.md`](document/正式版v1.0开发计划/M2-M7实施完成记录.md)。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-green.svg)](https://spring.io/projects/spring-ai)
[![Vue](https://img.shields.io/badge/Vue-3.4-4FC08D.svg)](https://vuejs.org/)
![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)

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

VIO AI Agent 是一个以宝可梦游戏为场景的**企业级 AI 智能体候选实现**，覆盖版本化知识、确定性计算、受控工具、Run 事件流、反馈评测和容器化运维。生产发布仍以开发计划中的真实环境证据为准。

### 应用场景

| 场景 | 说明 |
|------|------|
| 🎯 **AI 宝可梦大师** | 版本化图鉴/规则事实、属性/伤害/速度和队伍合法性确定性计算 |
| 🤖 **AI 超级智能体** | 基于 Run 状态机和受控工具网关的多步骤编排，支持 SSE 事件流与人工审批 |
| 🛠️ **MCP 服务** | MCP 能力通过配置开关和治理网关接入；自研协议栈保留为实验/兼容模块，需单独现场验收 |

---

## ✨ 核心亮点

### 🏗️ MCP 协议与治理网关

仓库保留自研 JSON-RPC 2.0 协议栈作为实验/兼容模块；生产接入必须经过 capability card、白名单、Schema、HITL、超时、脱敏和审计网关：

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
- ✅ Spring AI 桥接层：可对接 ToolCallback；生产是否启用由 profile 和现场配置决定

### 🔒 四层安全防护

```
API Key 认证 → HITL 人机审批 → 路径围栏 → 审计追溯
```

| 层级 | 组件 | 能力 |
|------|------|------|
| **L1 认证** | `ApiKeyAuthInterceptor` | Header API-Key/Bearer 校验；TokenBinding 工具范围与进程内配额（多实例待 Redis/边缘限流） |
| **L2 审批** | `HitlManager` | 高危操作人工确认（DangerLevel：LOW/MEDIUM/HIGH/CRITICAL） |
| **L3 围栏** | `PathGuard` + `CommandGuard` | 文件路径白名单 + 命令黑名单（内置 9 条危险命令拦截） |
| **L4 审计** | `AuditLogger` + `ParameterMasker` | 结构化审计日志 + 敏感参数脱敏 |

### 🧠 Plan-and-Execute 多智能体协作（兼容/实验模块）

```
用户输入 → Planner（任务规划）→ Worker（并行执行）→ Reviewer（质量审查）→ 最终输出
```

- **Planner Agent**：将复杂任务拆解为 DAG 子任务
- **Worker Agent**：并行执行子任务，支持依赖管理
- **Reviewer Agent**：审查执行结果，决定是否重新规划
- **Orchestrator**：兼容链的编排示例；v1.0 推荐使用持久化 `PokemonAgentRunService`，不要把该示例当作生产多 Agent 集群

### 💾 三层记忆系统（当前默认关闭持久记忆）

| 记忆类型 | 存储 | 容量 | 策略 |
|----------|------|------|------|
| **短期记忆** | 内存 | 4000 tokens | 滑动窗口 + 自动截断 |
| **长期记忆** | 文件（Kryo 序列化，单机） | 无限制 | Top-K 语义检索召回；生产需按用户隔离的持久适配器 |
| **工作记忆** | Agent 状态机 | 会话生命周期 | 上下文压缩 + 任务链追踪 |

### 🛡️ 会话编排基线（候选 v1.0）

- **会话状态机**：IDLE → PLANNING → EXECUTING → REVIEWING → COMPLETED / FAILED
- **并发控制**：Agent 线程池隔离 + `AgentConcurrencyGuard` 限流
- **超时重试**：治理网关提供有限、可审计的重试（当前实现最多 1 次）；跨实例重试和持久化队列仍待生产验收
- **幂等管理**：Run/工具具备请求级幂等契约；部分兼容编排链的 `IdempotencyManager` 仍是 JVM 内存实现，不能作为跨重启保证

### 📊 可观测性

- **健康检查**：`/api/health` + `/api/ready`（含 DB 连接检测）
- **指标监控**：Micrometer/Actuator `/api/actuator/prometheus` → Prometheus → Grafana；`/api/metrics` 保留为受保护的诊断快照（现场 Targets/Alerts 仍需验收）
- **结构化日志**：Logback MDC 注入 traceId/sessionId，生产环境 JSON 格式输出
- **结构化审计日志**：所有工具调用 + 审批操作可追溯

### ⚡ Run 事件流与 SSE 兼容架构

- **现代 Run 主链**：后端 `SseEmitter`/结构化事件 → 前端 `fetch` + `ReadableStream`，按 `runId/lastEventId` 重放
- **旧兼容链**：历史 `/ai/**` 接口仍使用 `EventSource`；生产默认关闭，不作为 v1.0 主链
- **Nginx 优化**：`proxy_buffering off` + `chunked_transfer_encoding off` + 600s 超时
- **智能体流式推送**：VioManus 每步执行结果实时推送到前端
- **认证边界**：现代 Run 使用受保护的 Header API-Key/可信边缘身份；浏览器不会把 API Key 写入 URL、历史记录或 localStorage。前端已预留 CSRF 头，但后端 CSRF/Origin 强校验仍是发布前门禁；旧 EventSource 兼容链默认关闭于生产。

### 🔧 工程化细节

- **环境自适应前端**：自动切换 `localhost:8123`（开发）/ `/api`（生产 Nginx 代理）
- **OpenAPI 契约**：后端 SpringDoc/Swagger UI 暴露接口；当前前端使用受控的 JavaScript API wrapper，代码生成客户端仍是后续工作
- **配置安全**：`application-local.yml` 被 `.gitignore` 忽略，提供 `.example.yml` 模板
- **优雅关闭**：`GracefulShutdown` 处理 Spring 上下文关闭事件，确保资源释放
- **Profile 分级**：`local` 默认关闭安全/PGVector/远程 MCP/高危工具/持久记忆以便开发；`prod` 打开认证、Run/PGVector 与观测骨架，但 MCP、高危工具和记忆仍由显式环境开关控制，不能写作“全部开启”。

---

## 🏗️ 系统架构

现代生产主链为 `Vue → AgentRunController → PokemonAgentRunService → DefaultRunExecutor`；
`AiController/GameApp/VioManus` 在下图中作为本地兼容/迁移链保留，生产可通过开关关闭。

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
| **AI 框架（兼容/示例）** | LangChain4j | 1.0.0-beta2 | 历史多框架示例，现代 Run 主链以 Spring AI 为准 |
| **大模型** | DashScope (Qwen-Plus) | — | 阿里云百炼大模型平台 |
| **本地模型** | Ollama (Gemma 3) | — | 离线推理 / 开发调试 |
| **前端** | Vue 3 + Vite | 3.4 / 5.4 | 响应式 UI 框架 |
| **HTTP 客户端（兼容）** | Axios | 1.7 | 历史接口封装；现代 Run 流使用原生 fetch |
| **向量数据库** | PostgreSQL + PGVector | 16 | 向量存储与语义检索 |
| **序列化** | Kryo | 5.6.2 | 对话记忆高性能持久化 |
| **网页抓取** | Jsoup | 1.19.1 | HTML 解析与内容提取 |
| **PDF 生成** | iText | 9.1.0 | PDF 文档生成 |
| **工具库** | Hutool | 5.8.37 | Java 工具类库 |
| **API 文档** | SpringDoc + Swagger UI | 2.7.0 | OpenAPI 3.0 接口文档 |
| **容器化** | Docker + Docker Compose | — | 一键部署 |
| **监控** | Prometheus + Grafana | — | 指标采集与可视化（可选启用） |
| **测试** | JUnit 5 + Spring Boot Test | — | 测试数量以最新 CI/`mvnw clean test` 输出为准 |

---

## 📁 项目结构

```
vio-ai-agent/
├── src/main/java/com/vio/vioaiagent/
│   ├── VioAiAgentApplication.java      # 应用入口
│   ├── controller/                     # REST API 控制层
│   │   ├── AgentRunController.java     # v1.0 Run 创建/事件/取消主链
│   │   ├── KnowledgeLifecycleController.java # 摄取/审核/发布接口
│   │   ├── FeedbackController.java     # 反馈与纠错闭环
│   │   ├── AiController.java           # 历史兼容 AI/Manus 接口
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
│   │   └── GameApp.java                #   4 类对话能力，各提供同步/流式入口（兼容模块）
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
│   │   ├── AgentMetrics.java           #   Micrometer + 诊断快照指标
│   │   ├── AgentLogContext.java        #   MDC traceId/sessionId 注入
│   │   └── ObservabilityFilter.java    #   HTTP 请求日志过滤器
│   ├── rag/                            # 📚 RAG 知识库
│   │   ├── catalog/                    #   JDBC/内存版本目录
│   │   ├── ingestion/                  #   显式摄取、索引与发布生命周期
│   │   ├── retrieval/                  #   词法/语义/RRF 检索
│   │   ├── contract/                   #   AnswerContract/Citation
│   │   ├── GameAppDocumentLoader.java  #   兼容文档 ETL 加载
│   │   ├── VersionedPgVectorStoreConfig.java  # 生产 PGVector 配置
│   │   ├── QueryRewriter.java          #   查询重写
│   │   └── GameAppContextualQueryAugmenterFactory.java  # 上下文增强
│   ├── tools/                          # 🛠️ 工具集
│   │   ├── governance/                 #   Capability card + 统一执行网关
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
│       ├── AgentErrorCode.java         #   23 个错误码（5 个域）
│       ├── RequestContext.java         #   ThreadLocal 请求上下文
│       └── GracefulShutdown.java       #   优雅关闭
├── src/main/resources/
│   ├── application.yml                 # 主配置
│   ├── application-local.yml           # 本地环境配置（gitignore）
│   ├── application-prod.yml            # 生产环境配置
│   └── application-local.example.yml   # 配置模板
├── src/test/                           # JUnit/Spring Boot 测试（数量以 CI 输出为准）
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
│   │   └── api/index.js                # Run fetch/事件流 + 兼容 API 封装
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
└── document/                            # 📖 架构、范围、实施与领域资料
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
# 本地前端默认允许 http://localhost:3000；生产环境必须配置
# VIO_CORS_ALLOWED_ORIGINS，并通过 Session/CSRF 或可信代理提供 API 认证。
```

### 3. 启动后端

```bash
# 使用 Maven Wrapper（无需安装 Maven）
./mvnw spring-boot:run

# 后端启动在 http://localhost:8123/api
# Swagger UI: http://localhost:8123/api/swagger-ui.html
```

### 4. 启动前端（可选）

#### 本地改用 DeepSeek（无需服务器、数据库或 Docker）

1. 在 IDEA 的运行配置中将 **Active profiles** 设置为 `local,deepseek`。
2. 在同一运行配置的环境变量中设置 `DEEPSEEK_API_KEY`，值填自己的 DeepSeek Key；不要提交或发到聊天。
3. 停止旧后端后重新启动，避免占用同一个 8123 端口。
4. 终端启动可使用 `./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,deepseek"`，前提是该终端已配置 `DEEPSEEK_API_KEY`。
5. 默认模型为 `deepseek-flash`，可通过 `DEEPSEEK_MODEL` 修改为账号可用的模型。

DeepSeek profile 使用专用的 OpenAI 兼容客户端，关闭 DashScope 自动装配，因此不需要百炼 Key。
它开启本地 Run 的模型正文流式生成；前端通过约 500ms 的事件游标补读逐步显示真实增量，
不是把完整答案拆成打字动画。普通回答只做一次流式生成；模型选择工具时，执行工具后再继续生成。
当前 Spring AI 1.0 适配器不回传 DeepSeek 的 `reasoning_content`，因此请求显式关闭厂商 thinking 模式，
避免多轮工具调用 400；仍支持任务规划、工具调用和最终回答，不展示模型内部推理。
知识库和 durable resume 路径保持原有完成提交规则，不提前开放未校验的流式答案。
本地聊天的资料版本、反馈和限制信息默认折叠；工具错误和取消按钮继续可见。
API Key 不等于搜索 Key，联网搜索超时仍需单独排查；不能将旧知识宣称为当前赛季排名。

本地会话默认保存到 `.local-data/history.sqlite`（已加入 Git 忽略），不需要安装数据库服务。
`VIO_LOCAL_HISTORY_PATH` 可覆盖存储路径，`VIO_LOCAL_HISTORY_ENABLED=false` 可切回内存模式。
这里只持久化会话和完整聊天消息，不宣称运行中的 Run/工具在重启后可自动续跑。
已完成的历史可在资料工作台查看；同一标签页刷新也会回读已保存消息。
每次提问最多带入同一用户、同一会话的最近 20 条已完成历史，总计不超过 12000 字符。
未完成请求、当前问题和 system/tool 消息不会被回填为历史。
联网搜索仅在配置有效 `search-api.api-key` 且 `vio.tools.search.enabled=true` 时提供，
没有配置时模型不会看到搜索工具。HTTP 连接/请求超时为 2/3 秒，认证失败与空搜索结果分别处理。

接口依据：[DeepSeek 官方接入说明](https://api-docs.deepseek.com/)、[Thinking 模式与工具历史要求](https://api-docs.deepseek.com/guides/thinking_mode/)。

#### 启动前端

本地模式现已提供账号登录：重启后端后，在页面选择“创建账户”，保存恢复码再登录。
工作区左下角头像可进入账户设置、切换账号或退出；账号、聊天、队伍、收藏均保存在本机 `.local-data/`。
首次创建的账号可在账户设置中确认认领旧匿名聊天和队伍。
本地账号与企业 OIDC 互斥，切换企业登录前设置 `VIO_LOCAL_ACCOUNTS_ENABLED=false`。
详见[本地账号实施与使用说明](document/正式版v1.1开发计划/本地账号与登录界面实施_2026-10-21.md)。

```bash
cd vio-ai-agent-frontend
npm ci
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
| `/api/ai/game_app/...` | GET | 历史兼容对话链；生产建议关闭/不作为 v1.0 主链 |
| `/api/ai/manus/chat` | GET | 历史 Manus SSE；生产默认 410，使用现代 Run |
| `/api/ai/manus/stop` | GET | 历史停止接口；生产默认 410，不能替代 Run cancel |
| `/api/chat/runs` | POST | 创建可恢复 Agent Run（返回 202） |
| `/api/chat/runs/{runId}` | GET | 查询 Run 快照 |
| `/api/chat/runs/{runId}/events/stream` | GET | 按游标订阅 Run 事件 |
| `/api/chat/runs/{runId}/cancel` | POST | 幂等取消 Run |
| `/api/chat/conversations/{conversationId}` | DELETE | 删除会话及关联消息 |
| `/api/answers/{answerId}/feedback` | POST | 提交 Helpful/Not helpful 反馈 |
| `/api/health` | GET | 健康检查 |
| `/api/ready` | GET | 就绪探针 |
| `/api/metrics` | GET | 指标采集 |

> 说明：Spring Boot 的 context-path 是 `/api`，Controller 内部路径因此写作 `/chat/...`、`/knowledge/...`。上表是外部 URL。现代 Run 是推荐入口；旧 `/api/ai/**` 仅用于本地兼容和迁移验证。

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
cd vio-ai-agent-frontend && npm ci && npm run build && cd ..

# 3. 启动全部服务
cp .env.example .env
# 编辑 .env，填入 DB_PASSWORD、VIO_API_KEY、AI_DASHSCOPE_API_KEY 等真实密钥
docker compose up -d --build

# 4. 访问
# 前端: http://localhost
# 后端: http://localhost:8123/api
# Swagger: http://localhost:8123/api/swagger-ui.html
```

### 启用监控（可选）

```bash
docker-compose --profile monitoring up -d

# Grafana: http://localhost:3001（账号/密码由 GRAFANA_ADMIN_USER/GRAFANA_ADMIN_PASSWORD 提供）
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
| 后端构建 | `./mvnw -B -ntp -DskipTests compile`（发布前再执行 package/image） |
| 运行测试 | `./mvnw test` |
| 前端构建 | `npm ci && npm run build` |

### 部署流水线（`.github/workflows/deploy.yml`）

触发条件：`workflow_dispatch`（手动触发）或 `v*` 标签推送

| 步骤 | 说明 |
|------|------|
| 构建 JAR | `./mvnw clean package -DskipTests` |
| 构建前端 | `npm ci && npm run build` |
| Docker/Compose | 由发布门禁执行构建、拓扑校验与可选 smoke；当前仓库未在本机完成现场演练 |
| 健康检查 | `curl` 轮询 `/api/health`（30s 超时） |

### 环境变量（`.env.example`）

| 变量 | 必填 | 说明 |
|------|------|------|
| `AI_DASHSCOPE_API_KEY` | ✅ | 阿里云百炼大模型 API Key |
| `SEARCH_API_KEY` | — | SearchAPI 联网搜索 Key |
| `DB_PASSWORD` | Compose 必填 | PostgreSQL 数据库密码（无公开默认值） |
| `VIO_API_KEY` | 生产必填 | 后端 API Key；仅由 Header/可信边缘代理传递 |
| `VIO_CORS_ALLOWED_ORIGINS` | 生产必填 | 逗号分隔的完整前端 origin 白名单，禁止 `*` |
| `VIO_LEGACY_AI_ENABLED` | — | 旧 `/api/ai/**` 兼容链开关；生产默认 `false` |
| `VIO_LEGACY_MANUS_ENABLED` | — | 旧 Manus GET 链开关；生产默认 `false` |
| `AMAP_API_KEY` | — | 高德地图 API Key |
| `PEXELS_API_KEY` | — | Pexels 图片搜索 API Key |

---

## 📖 文档索引

### 架构、范围与实施记录

| 文档 | 说明 |
|------|------|
| [📦 正式版 v1.0 开发计划](document/正式版v1.0开发计划/README.md) | M0～M7 实施手册、验收清单与实施完成记录 |
| [🚀 正式版 v1.1 开发步骤手册](document/正式版v1.1开发计划/正式版v1.1开发步骤手册.md) | 基于 v1.0 当前差距推进生产化收口、灾备和 GA 验收 |
| [📐 VIO-AI-Agent 架构图](document/VIO-AI-Agent-architecture.md) | 当前模块边界、运行链和外部依赖 |
| [📊 企业级架构与技术对照报告](document/企业级智能体架构与技术对照报告.md) | 企业级能力对照与成熟度边界（历史快照） |
| [📚 宝可梦图鉴与对战攻略](document/宝可梦图鉴与对战攻略.md) | 首发知识范围与领域资料 |

### 分期教程与运维材料

| 文档 | 说明 |
|------|------|
| [🧪 评测与质量门禁](evaluation/README.md) | 数据集、指标字典、CI/release 门禁 |
| [🛟 恢复演练手册](ops/recovery/README.md) | 备份、探针、恢复、RPO/RTO 记录模板 |
| [📦 M6-M7 交付说明](document/正式版v1.0开发计划/M6-M7交付说明.md) | 评测、容器、发布和演示交付边界 |

---

## 📊 项目规模

| 指标 | 数值 |
|------|------|
| 源文件 | 以 `rg --files` 与构建产物为准（当前 Java 主源码 223 个） |
| 测试用例 | 以最新 `mvnw clean test` 输出为准 |
| 测试通过率 | 以 CI/本地命令输出为准 |
| 核心包数 | 18 |
| MCP 协议自建文件 | 15+ |
| 安全模块文件 | 10+ |
| 多智能体文件 | 8+ |
| 内置工具 | 8 |
| 错误码定义 | 23 个（5 个域：AG/TL/MCP/SEC/SES） |
| 开发文档 | 以 document/、evaluation/、ops/ 目录中的最新文件为准 |
| 依赖清单 | 以后端 `pom.xml` 与前端 `package-lock.json` 的锁定版本为准 |

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

## 🎮 本地宝可梦助手扩展（2026-10-20）

使用 `local,deepseek` 启动后，新对话可按需读取真实 PokéAPI 资料并在本地缓存，支持中文名称、英文名称和编号。队伍现在保存招式、特性、道具、性格、等级、EV/IV，并用 SQLite 保留至下次启动。

伤害计算通过 Java 调用本地 `@smogon/calc` 0.12.0，无需额外服务器。首次准备在 `tools/pokemon-engine` 执行 `npm ci`；可以从已保存队伍选择攻防成员，计算后交给 AI 解释。新增“攻略”支持检索四篇原创基础笔记并携带证据提问。

参考资料不等同于排位合法性或实时热门阵容；当前计算桥接的条件范围见[四阶段落地与操作说明](document/正式版v1.1开发计划/PokéAPI与队伍伤害攻略四阶段落地_2026-10-20.md)。

如果浏览器可以联网、图鉴查询却超时，可通过 `VIO_POKEAPI_PROXY=http://127.0.0.1:你的代理端口` 为资料客户端指定 HTTP 代理。也可在 `.local-data/pokeapi-network.properties` 写入 `proxy=http://127.0.0.1:端口`，重启后端生效；环境配置优先，`direct` 表示显式直连。这里不填写模型 API Key，也不支持带账号密码的代理 URL。资料上游不可达时返回 503；原有校验通过的缓存仍可查询。

Windows 本地排障可执行 `./tools/warm-pokemon-reference.ps1 -Query squirtle`，通过系统代理下载官方 Pokémon 与 species 资料并写入相同校验格式的缓存，无需重启即可查询。脚本保留已有文件，不会覆盖损坏或人工修改的快照。

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
