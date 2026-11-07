# VIO AI Agent 项目架构图

> 基于当前源码、配置、前端 API 与 Docker Compose 的静态接线核对。
>
> - 实线：当前主请求链。
> - 虚线：按配置启用、半接入或尚未进入主链。

```mermaid
flowchart TB
    classDef live fill:#eaf8f0,stroke:#2e8b57,color:#173d2a
    classDef optional fill:#f8fafc,stroke:#94a3b8,color:#475569,stroke-dasharray:6 5
    classDef external fill:#fff8e6,stroke:#e07b00,color:#6b3d00

    USER[用户浏览器] --> FE[Vue 3 + Vite SPA / Nginx]
    FE -->|HTTP / SSE| API[ObservabilityFilter → API Key → AiController]

    API --> GAME[GameApp<br/>基础 / RAG / Tools / MCP]
    API --> MANUS[VioManus<br/>ReAct Agent Loop]

    GAME --> CHAT[Spring AI ChatClient]
    GAME --> RAG[QueryRewriter + QuestionAnswerAdvisor]
    GAME --> TOOLS[8 类本地 ToolCallback]
    GAME -.-> SMCP[Spring AI MCP Client]

    MANUS --> CHAT
    MANUS --> TOOLS
    MANUS -.-> SMCP

    CHAT --> QWEN[DashScope Qwen / Embedding]
    RAG --> DOC[Markdown ETL → SimpleVectorStore]
    TOOLS --> RESOURCE[文件 / 网络 / 终端 / PDF]
    SMCP -.-> MCP[独立图片 MCP Server → Pexels]

    EXT[多智能体 / 三层记忆 / 自建 MCP 协议栈 / HITL·Guard·Audit·Retry·Metrics]:::optional
    API -. 尚未直接接入或未闭环 .-> EXT

    OPS[Docker Compose + 可选监控<br/>PostgreSQL / Prometheus / Grafana / Alertmanager]:::optional
    OPS -. 当前配置未打通主数据链 .-> API

    class USER,FE,API,GAME,MANUS,CHAT,RAG,TOOLS live
    class QWEN,DOC,RESOURCE,MCP external
    class SMCP,EXT,OPS optional
```

## 两条当前主业务链

1. 宝可梦问答：`Vue EventSource → AiController → GameApp → Spring AI → DashScope → SSE`。
2. 通用智能体：`Vue EventSource → AiController → VioManus → Think → ToolCall → Observe → SSE`。

## 接入状态说明

- 当前 RAG 使用 `SimpleVectorStore` 内存向量库；PGVector/PostgreSQL 已声明但未进入当前生产链。
- 当前业务 MCP 路线使用 Spring AI `ToolCallbackProvider`；自建 `mcp/` JSON-RPC 协议栈是独立预备能力。
- 多智能体、三层记忆、HITL/围栏/审计及 Prometheus 监控资产已有代码或配置，但没有完整接入当前主请求链。
