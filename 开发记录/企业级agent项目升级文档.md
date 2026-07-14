# 🏢 企业级 AI Agent 项目升级路线文档

> 基于牛客网多位大厂面试官/offer 获得者的面试经验 + 当前 VIO-AI-Agent 项目现状分析
> 最后更新：2026-07-14

---

## 📋 目录

1. [企业级 Agent 项目的核心竞争力](#一企业级-agent-项目的核心竞争力)
2. [当前项目现状诊断](#二当前项目现状诊断)
3. [MCP 协议深度升级](#三mcp-协议深度升级)
4. [安全与鉴权体系](#四安全与鉴权体系)
5. [会话编排与容错](#五会话编排与容错)
6. [多智能体协作](#六多智能体协作)
7. [记忆系统升级](#七记忆系统升级)
8. [可观测性建设](#八可观测性建设)
9. [生产级工程化](#九生产级工程化)
10. [简历话术与面试加分点](#十简历话术与面试加分点)
11. [分阶段实施路线图](#十一分阶段实施路线图)

---

## 一、企业级 Agent 项目的核心竞争力

### 1.1 面试官真正关注什么？

根据大厂面试经验，面试官看 Agent 项目时，核心关注以下几个层次：

```
Level 1: 会调 API               → ❌ 简历直接淘汰
Level 2: 会用框架（Spring AI）    → ⚠️ 勉强过筛，但问深了就挂
Level 3: 理解协议与架构          → ✅ 能聊，有竞争力
Level 4: 能落地生产环境          → 🏆 面试官主动加微信
```

**关键差距不在"能不能做出来"，而在"能不能讲清楚为什么这样做"以及"能不能落地到生产环境"。**

### 1.2 企业级 Agent 必备能力清单

| 维度 | 必备能力 | 面试高频考点 |
|------|---------|-------------|
| **MCP 协议** | 自定义协议实现、网关分层、JSON-RPC 2.0 规范 | Initialize/ToolsList/ToolsCall 全链路 |
| **安全体系** | 令牌分域、协议绑定、权限校验、HITL、审计日志 | 越权调用防护、参数脱敏 |
| **会话编排** | 消息聚合、状态机管理、超时重试、幂等策略 | 线程池隔离、失败回退 |
| **多智能体** | Planner/Worker/Reviewer 模式、DAG 调度 | 任务拆解、依赖管理 |
| **记忆系统** | 三层记忆（短期/长期/工作）、上下文压缩 | Token 预算、检索召回 |
| **可观测性** | 链路追踪、指标监控、结构化日志 | 分布式追踪、告警策略 |
| **工程化** | 配置热更新、优雅关闭、灰度发布、容器化 | CI/CD、健康检查 |

---

## 二、当前项目现状诊断

### 2.1 已完成的能力（✅ 优势）

| 模块 | 完成度 | 说明 |
|------|--------|------|
| 基础 Agent 架构 | ✅ 80% | ReAct 模式（BaseAgent → ReActAgent → ToolCallAgent → VioManus），继承体系清晰 |
| 本地工具集 | ✅ 85% | 7 个工具：文件操作、联网搜索、网页抓取、资源下载、终端执行、PDF 生成、终止工具 |
| RAG 知识库 | ✅ 75% | PGVector + ETL 流水线 + 查询重写 + 上下文增强，具备基础 RAG 全链路 |
| MCP 基础集成 | ✅ 40% | 通过 Spring AI 的 ToolCallbackProvider 消费 MCP 工具，有独立的 MCP Server 子项目 |
| 流式响应 | ✅ 70% | 支持 SSE 流式输出，VioManus 多步执行实时推送 |
| 前端界面 | ✅ 60% | Vue3 前端，有基础聊天 UI，但功能简单 |
| 对话记忆 | ✅ 50% | 内存存储（MessageWindowChatMemory）+ 文件持久化（Kryo），但缺少压缩和检索 |

### 2.2 关键短板（❌ 与企业级的差距）

| 短板 | 严重程度 | 面试风险 | 当前状态 |
|------|---------|---------|---------|
| **MCP 协议只是"会用"** | 🔴 致命 | 被问 "MCP 怎么握手初始化？JSON-RPC 2.0 规范？" → 答不上来 | 完全依赖 Spring AI 封装，没有自己的协议实现 |
| **零安全机制** | 🔴 致命 | 被问 "工具调用的安全怎么保证？" → 无言以对 | Terminal 工具可执行任意命令，File 工具可读写任意路径 |
| **无鉴权体系** | 🔴 致命 | 被问 "API Key 怎么管理？怎么防止越权？" → 没有方案 | 所有接口裸奔，无任何鉴权 |
| **无重试/容错** | 🟠 严重 | 被问 "工具调用失败怎么办？" → 只有基础异常捕获 | 无重试策略，无幂等设计，无熔断降级 |
| **单体 Agent** | 🟠 严重 | 被问 "多智能体怎么协作？" → 没做过 | 只有 VioManus 一个 Agent |
| **无审计日志** | 🟠 严重 | 被问 "怎么追踪 Agent 的行为？" → 只有 log.info | 无结构化审计，无敏感操作记录 |
| **记忆系统简陋** | 🟡 中等 | 被问 "长对话上下文怎么管理？" → 靠截断 | 无压缩、无分层、无智能检索 |
| **无可观测性** | 🟡 中等 | 被问 "怎么监控 Agent 运行状态？" → 没有 | 无指标、无追踪、无告警 |
| **MCP 工具串行** | 🟡 中等 | 被问 "多个工具怎么并行？" → 不会 | 所有工具串行调用，无并行优化 |
| **测试覆盖不足** | 🟢 一般 | 被问 "怎么保证 Agent 质量？" → 测试少 | 只有基础单元测试，无集成/性能测试 |

### 2.3 当前项目架构 vs 企业级架构对比

```
当前架构：                          企业级架构：
                                    
Controller                          API Gateway (鉴权/限流/路由)
    │                                    │
LoveApp / VioManus                   Agent Orchestrator (多Agent编排)
    │                                    │
ChatClient (Spring AI)               Agent Engine (ReAct / Plan-Execute)
    │                                    │
ToolCallback[] + MCP                  ├─ Tool Registry (工具注册中心)
    │                                 ├─ MCP Gateway (协议网关)
    │                                 │   ├─ JSON-RPC 2.0 协议实现
    │                                 │   ├─ Transport 层 (Stdio/SSE/WebSocket)
    │                                 │   └─ Session Manager
    │                                 ├─ Security Manager (安全管控)
    │                                 │   ├─ Auth Provider
    │                                 │   ├─ HITL 审批
    │                                 │   └─ Audit Logger
    │                                 └─ Observability (可观测性)
    │                                     ├─ Tracer
    │                                     ├─ Metrics
    │                                     └─ Structured Logger
ChatModel (DashScope)                LLM Router (多模型路由/降级)
```

---

## 三、MCP 协议深度升级

> 🎯 **目标**：从"会用 Spring AI 的 MCP"升级到"能自己实现 MCP 协议栈"
> 📝 **面试话术**："我们的 MCP 不是简单调 Spring AI 的 starter，而是自建了协议网关层，实现了 JSON-RPC 2.0 的完整协议栈"

### 3.1 当前问题

- 完全依赖 `spring-ai-starter-mcp-client` 的黑盒封装
- 不理解 MCP 底层的 JSON-RPC 2.0 协议细节
- 无法回答 "initialize 请求的 JSON 格式是什么？"
- 无法回答 "工具列表是怎么发现的？"
- MCP Server 只是简单的 `@Tool` 注解暴露，没有协议感知

### 3.2 升级方案：自建 MCP 协议网关

#### 3.2.1 架构分层设计

```
┌─────────────────────────────────────────────┐
│            Agent 能力编排层                   │
│  (VioManus / Multi-Agent / Plan-Execute)     │
├─────────────────────────────────────────────┤
│            MCP Gateway (协议网关)             │
│  ┌───────────┐ ┌──────────┐ ┌────────────┐  │
│  │ Protocol  │ │ Session  │ │ Transport  │  │
│  │ Handler   │ │ Manager  │ │ Adapter    │  │
│  └───────────┘ └──────────┘ └────────────┘  │
├─────────────────────────────────────────────┤
│          JSON-RPC 2.0 协议实现层              │
│  ┌──────────┐ ┌──────────┐ ┌─────────────┐  │
│  │ Request  │ │ Response │ │ Error Code  │  │
│  │ Builder  │ │ Parser   │ │ Registry    │  │
│  └──────────┘ └──────────┘ └─────────────┘  │
├─────────────────────────────────────────────┤
│          Transport 传输层                     │
│  ┌──────────┐ ┌──────────┐ ┌────────────┐  │
│  │  Stdio   │ │ HTTP/SSE │ │ WebSocket  │  │
│  │ Transport│ │Transport │ │ Transport  │  │
│  └──────────┘ └──────────┘ └────────────┘  │
└─────────────────────────────────────────────┘
```

#### 3.2.2 需要实现的核心类

```java
// 1. JSON-RPC 2.0 核心协议对象
public class JsonRpcRequest {
    private String jsonrpc = "2.0";
    private String id;          // 请求 ID（String 或 Number）
    private String method;      // 方法名：initialize, tools/list, tools/call
    private Map<String, Object> params;  // 参数
}

public class JsonRpcResponse {
    private String jsonrpc = "2.0";
    private String id;
    private Object result;      // 成功结果
    private JsonRpcError error; // 错误信息
}

public class JsonRpcError {
    private int code;           // 错误码（-32700 解析错误, -32600 无效请求等）
    private String message;
    private Object data;        // 附加错误数据
}

// 2. MCP 协议生命周期
public interface McpProtocolHandler {
    // 握手初始化 — 面试高频考点
    InitializeResult initialize(InitializeRequest request);
    
    // 能力协商
    ServerCapabilities getCapabilities();
    
    // 工具发现 — 面试高频考点
    List<ToolDefinition> listTools(String cursor);
    
    // 工具调用 — 面试高频考点
    ToolCallResult callTool(String toolName, Map<String, Object> arguments);
}

// 3. 协议映射（protocolId 绑定，扩展新协议零侵入）
public interface ProtocolMapper {
    // 根据 protocolId 绑定不同协议实现
    boolean supports(String protocolId);
    McpProtocolHandler createHandler(String protocolId);
}

// 4. 传输层抽象
public interface McpTransport {
    void send(JsonRpcRequest request);
    JsonRpcResponse receive();
    void close();
}

// Stdio 实现
public class StdioTransport implements McpTransport { ... }

// SSE 实现
public class SseTransport implements McpTransport { ... }
```

#### 3.2.3 JSON-RPC 2.0 错误码体系

```java
public enum JsonRpcErrorCode {
    // JSON-RPC 2.0 标准错误码
    PARSE_ERROR(-32700, "解析错误：无效的 JSON"),
    INVALID_REQUEST(-32600, "无效请求：不是有效的 JSON-RPC 2.0 请求"),
    METHOD_NOT_FOUND(-32601, "方法不存在"),
    INVALID_PARAMS(-32602, "无效参数"),
    INTERNAL_ERROR(-32603, "内部错误"),
    
    // MCP 协议自定义错误码
    SERVER_NOT_INITIALIZED(-32002, "服务端未初始化"),
    UNKNOWN_CAPABILITY(-32003, "未知能力"),
    TOOL_NOT_FOUND(-32004, "工具未找到"),
    TOOL_EXECUTION_ERROR(-32005, "工具执行失败"),
    
    // 业务扩展错误码（面试加分项）
    AUTH_FAILED(-32010, "鉴权失败"),
    RATE_LIMITED(-32011, "请求频率超限"),
    SESSION_EXPIRED(-32012, "会话已过期"),
    PERMISSION_DENIED(-32013, "权限不足");
}
```

#### 3.2.4 MCP 协议全链路实现

```
客户端                          服务端
  │                               │
  │ ── initialize ──────────────> │  ① 握手：交换协议版本和能力
  │ <── InitializeResult ─────── │
  │                               │
  │ ── tools/list ─────────────> │  ② 工具发现：获取可用工具列表
  │ <── ToolListResult ───────── │
  │                               │
  │ ── tools/call ─────────────> │  ③ 工具调用：执行具体工具
  │ <── ToolCallResult ───────── │
  │                               │
  │ ── notifications/cancel ───> │  ④ 取消通知（可选）
```

### 3.3 MCP 协议升级的实施步骤

| 步骤 | 内容 | 预计工时 | 优先级 |
|------|------|---------|--------|
| 1 | 实现 JSON-RPC 2.0 请求/响应/错误对象 | 4h | 🔴 P0 |
| 2 | 实现 MCP 协议处理器接口和基础实现 | 8h | 🔴 P0 |
| 3 | 实现 Stdio Transport 适配器 | 4h | 🔴 P0 |
| 4 | 实现 SSE Transport 适配器 | 4h | 🔴 P0 |
| 5 | 实现 ProtocolMapper 协议路由 | 4h | 🟠 P1 |
| 6 | 编写 MCP 协议兼容性测试 | 8h | 🟠 P1 |
| 7 | 将现有 MCP Server 迁移到自建协议栈 | 4h | 🟡 P2 |

---

## 四、安全与鉴权体系

> 🎯 **目标**：从"裸奔的 API"升级到"四层防护的企业级安全体系"
> 📝 **面试话术**："我们实现了四层安全防护：HITL 审批 + 路径围栏 + 命令黑名单 + 审计日志，令牌分域绑定协议，防止越权调用"

### 4.1 当前问题

| 风险点 | 具体表现 |
|--------|---------|
| Terminal 工具无限制 | 可执行 `rm -rf /`、`dd if=/dev/zero of=/dev/sda` 等毁灭性命令 |
| File 工具无路径限制 | 可读写系统任意目录，如 `/etc/passwd`、`~/.ssh/id_rsa` |
| 无 API 鉴权 | 任何人知道接口地址就能调用，无身份验证 |
| 无审计记录 | 谁在什么时候调了什么工具 → 完全不知道 |
| API Key 硬编码 | `TestApiKey.java` 中 Key 写在源码里 |

### 4.2 升级方案：四层安全防护体系

```
┌─────────────────────────────────────────────────────────┐
│                    第一层：认证与鉴权                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐   │
│  │ API Key  │  │ JWT      │  │ 令牌-协议绑定         │   │
│  │ 校验     │  │ Token    │  │ (防止令牌跨协议调用)   │   │
│  └──────────┘  └──────────┘  └──────────────────────┘   │
├─────────────────────────────────────────────────────────┤
│                    第二层：HITL 审批                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ 危险操作识别  │  │ 三级危险分级  │  │ 审批决策      │   │
│  │ (静态规则)    │  │ (高危/中危/安全)│  │ (放行/拒绝/修改)│  │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
├─────────────────────────────────────────────────────────┤
│                    第三层：执行围栏                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ Path Guard   │  │ Command Guard│  │ Fast-Fail     │   │
│  │ (路径白名单)  │  │ (命令黑名单)  │  │ (提前拦截)    │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
├─────────────────────────────────────────────────────────┤
│                    第四层：审计追溯                       │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │ 操作日志      │  │ 参数脱敏     │  │ 链路追踪      │   │
│  │ (JSONL持久化) │  │ (敏感字段*)   │  │ (traceId)    │   │
│  └──────────────┘  └──────────────┘  └──────────────┘   │
└─────────────────────────────────────────────────────────┘
```

#### 4.2.1 第一层：认证与鉴权

```java
// 令牌与协议绑定模型
public class TokenBinding {
    private String tokenId;        // 令牌 ID
    private String protocolId;     // 绑定的协议（如 "amap-maps", "image-search"）
    private Set<String> allowedTools;  // 允许调用的工具列表
    private RateLimit rateLimit;   // 频率限制
    private Instant expiresAt;     // 过期时间
}

// 鉴权拦截器
@Component
public class McpAuthInterceptor implements HandlerInterceptor {
    
    @Override
    public boolean preHandle(HttpServletRequest request, 
                            HttpServletResponse response, 
                            Object handler) {
        // 1. 提取 API Key / JWT Token
        String token = extractToken(request);
        
        // 2. 验证令牌有效性 + 过期时间
        TokenBinding binding = tokenStore.get(token);
        if (binding == null || binding.isExpired()) {
            throw new AuthException("令牌无效或已过期");
        }
        
        // 3. 校验协议绑定（防止令牌跨协议调用）
        String targetProtocol = extractProtocol(request);
        if (!binding.getProtocolId().equals(targetProtocol)) {
            throw new AuthException("令牌未绑定此协议");
        }
        
        // 4. 校验工具权限
        String toolName = extractToolName(request);
        if (!binding.getAllowedTools().contains(toolName)) {
            throw new AuthException("无权调用工具: " + toolName);
        }
        
        // 5. 频率限制检查
        if (binding.getRateLimit().isExceeded()) {
            throw new RateLimitException("请求频率超限");
        }
        
        // 6. 注入上下文
        RequestContext.setTokenBinding(binding);
        return true;
    }
}
```

#### 4.2.2 第二层：HITL（Human-in-the-Loop）审批

```java
// 危险操作定义
public enum DangerLevel {
    HIGH,    // 高危：需要用户显式批准（如 execute_command）
    MEDIUM,  // 中危：首次执行需确认，后续可放行（如 write_file）
    SAFE     // 安全：无需审批（如 web_search）
}

// HITL 审批流程
public class HitlManager {
    
    // 静态规则匹配
    private static final Map<String, DangerLevel> TOOL_DANGER_LEVELS = Map.of(
        "terminal_operation", DangerLevel.HIGH,
        "write_file", DangerLevel.MEDIUM,
        "delete_file", DangerLevel.HIGH,
        "web_search", DangerLevel.SAFE,
        "web_scraping", DangerLevel.SAFE
    );
    
    // 审批决策
    public ApprovalResult approve(ToolCallRequest request) {
        DangerLevel level = TOOL_DANGER_LEVELS.getOrDefault(
            request.getToolName(), DangerLevel.SAFE);
        
        switch (level) {
            case HIGH:
                // 必须等待用户批准
                return requestUserApproval(request);
            case MEDIUM:
                // 同一会话中首次需确认
                if (isAlreadyApprovedInSession(request)) {
                    return ApprovalResult.ALLOW;
                }
                return requestUserApproval(request);
            case SAFE:
                return ApprovalResult.ALLOW;
        }
        return ApprovalResult.DENY;
    }
    
    // 审批选项
    public enum ApprovalAction {
        APPROVE,        // 批准本次
        APPROVE_ALL,    // 全部放行（会话内）
        DENY,           // 拒绝
        SKIP,           // 跳过
        MODIFY          // 修改参数后执行
    }
}
```

#### 4.2.3 第三层：执行围栏

```java
// 路径围栏
@Component
public class PathGuard {
    
    @Value("${agent.security.allowed-paths}")
    private List<String> allowedPaths;  // 白名单路径
    
    public void validate(String operationPath) {
        Path path = Path.of(operationPath).normalize();
        
        // 1. 检查绝对路径外逃
        if (path.isAbsolute() && !isUnderAllowedRoot(path)) {
            throw new SecurityException("路径越权: " + path);
        }
        
        // 2. 检查 .. 穿越
        for (Path component : path) {
            if (component.toString().equals("..")) {
                throw new SecurityException("禁止使用 .. 路径穿越");
            }
        }
        
        // 3. 检查符号链接逃逸
        if (Files.isSymbolicLink(path)) {
            Path target = Files.readSymbolicLink(path);
            if (!isUnderAllowedRoot(target.toAbsolutePath())) {
                throw new SecurityException("符号链接指向非授权目录");
            }
        }
    }
    
    private boolean isUnderAllowedRoot(Path path) {
        return allowedPaths.stream()
            .anyMatch(root -> path.startsWith(Path.of(root)));
    }
}

// 命令黑名单（HITL 之前的 fast-fail 层）
@Component
public class CommandGuard {
    
    private static final List<Pattern> BLOCKED_PATTERNS = List.of(
        Pattern.compile("sudo\\s+"),
        Pattern.compile("rm\\s+(-[rRf]+\\s+)*/"),
        Pattern.compile("mkfs\\."),
        Pattern.compile("dd\\s+.*of=/dev/"),
        Pattern.compile(">\\s*/dev/sd[a-z]"),
        Pattern.compile(":(){ :|:& };:"),  // fork bomb
        Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),
        Pattern.compile("wget\\s+.*-O\\s+-\\s*\\|\\s*(ba)?sh"),
        Pattern.compile("chmod\\s+777\\s+/")
    );
    
    public void validate(String command) {
        for (Pattern pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(command).find()) {
                throw new SecurityException(
                    "命令被安全策略拦截，匹配规则: " + pattern.pattern());
            }
        }
    }
}
```

#### 4.2.4 第四层：审计日志

```java
// 审计日志模型
public class AuditEntry {
    private String traceId;         // 链路追踪 ID
    private String sessionId;       // 会话 ID
    private String userId;          // 用户 ID
    private String toolName;        // 工具名称
    private Map<String, Object> params;  // 参数（脱敏后）
    private String outcome;         // allow / deny / error
    private String approver;        // hitl / policy / none
    private long durationMs;        // 执行耗时
    private Instant timestamp;      // 时间戳
}

// 参数脱敏
public class ParameterMasker {
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
        "password", "token", "apiKey", "api_key", "secret",
        "Authorization", "authorization", "key", "passwd"
    );
    
    public Map<String, Object> mask(Map<String, Object> params) {
        Map<String, Object> masked = new HashMap<>(params);
        for (String key : params.keySet()) {
            if (SENSITIVE_FIELDS.stream().anyMatch(
                    k -> key.toLowerCase().contains(k.toLowerCase()))) {
                masked.put(key, "***REDACTED***");
            }
        }
        return masked;
    }
}

// 审计日志写入
@Component
public class AuditLogger {
    private final ObjectMapper mapper = new ObjectMapper();
    
    public void log(AuditEntry entry) {
        // 按天写入 JSONL 格式到 logs/audit/
        String filename = "logs/audit/audit-" 
            + LocalDate.now() + ".jsonl";
        try (FileWriter writer = new FileWriter(filename, true)) {
            writer.write(mapper.writeValueAsString(entry) + "\n");
        }
    }
}
```

### 4.3 安全升级实施步骤

| 步骤 | 内容 | 预计工时 | 优先级 |
|------|------|---------|--------|
| 1 | 实现 API Key + JWT 认证拦截器 | 8h | 🔴 P0 |
| 2 | 实现令牌-协议绑定模型 | 4h | 🔴 P0 |
| 3 | 实现 PathGuard 路径围栏 | 4h | 🔴 P0 |
| 4 | 实现 CommandGuard 命令黑名单 | 4h | 🔴 P0 |
| 5 | 实现 HITL 审批流程 | 8h | 🟠 P1 |
| 6 | 实现审计日志 + 参数脱敏 | 6h | 🟠 P1 |
| 7 | 编写安全测试用例（渗透测试） | 6h | 🟡 P2 |

---

## 五、会话编排与容错

> 🎯 **目标**：从"一个 ConcurrentHashMap 管理会话"升级到"生产级会话编排引擎"
> 📝 **面试话术**："会话编排采用线程池隔离 + 幂等重试 + 状态机管理，保证高并发下的可用性与一致性"

### 5.1 当前问题

- 并发控制靠 `ConcurrentHashMap`，无会话级别隔离
- 无超时控制（只有 SseEmitter 的 300 秒硬超时）
- 无重试策略（一次工具调用失败 = Agent 步进失败）
- 无幂等保证（同一个工具调用可能被重复执行）
- 无并发限制（无限创建 Agent 实例，可能 OOM）

### 5.2 升级方案

#### 5.2.1 会话状态机

```java
public enum SessionState {
    CREATED,      // 已创建
    INITIALIZING, // 初始化中（MCP 握手）
    READY,        // 就绪
    RUNNING,      // 执行中
    WAITING_HITL, // 等待人工审批
    PAUSED,       // 已暂停
    COMPLETED,    // 已完成
    FAILED,       // 失败
    TIMED_OUT,    // 超时
    CANCELLED     // 已取消
}

public class SessionStateMachine {
    private final Map<SessionState, Set<SessionState>> transitions;
    
    {
        // 定义合法的状态转换
        transitions.put(CREATED, Set.of(INITIALIZING, CANCELLED));
        transitions.put(INITIALIZING, Set.of(READY, FAILED, TIMED_OUT));
        transitions.put(READY, Set.of(RUNNING, CANCELLED));
        transitions.put(RUNNING, Set.of(WAITING_HITL, COMPLETED, FAILED, TIMED_OUT, CANCELLED));
        transitions.put(WAITING_HITL, Set.of(RUNNING, CANCELLED, TIMED_OUT));
        // ...
    }
    
    public void transition(Session session, SessionState target) {
        if (!transitions.get(session.getState()).contains(target)) {
            throw new IllegalStateTransitionException(
                session.getState(), target);
        }
        session.setState(target);
        // 触发状态变更事件
        eventPublisher.publish(new SessionStateChangedEvent(session));
    }
}
```

#### 5.2.2 线程池隔离

```java
@Configuration
public class AgentThreadPoolConfig {
    
    // Agent 推理线程池（CPU 密集）
    @Bean("agentReasoningExecutor")
    public ThreadPoolExecutor agentReasoningExecutor() {
        return new ThreadPoolExecutor(
            4,                              // 核心线程数
            8,                              // 最大线程数
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
    
    // 工具调用线程池（IO 密集）
    @Bean("toolExecutionExecutor")
    public ThreadPoolExecutor toolExecutionExecutor() {
        return new ThreadPoolExecutor(
            8,                              // 核心线程数
            16,                             // 最大线程数
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }
    
    // MCP 通信线程池
    @Bean("mcpTransportExecutor")
    public ThreadPoolExecutor mcpTransportExecutor() {
        return new ThreadPoolExecutor(
            4, 8, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(50),
            new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }
}
```

#### 5.2.3 重试与幂等策略

```java
// 幂等键生成
public class IdempotencyManager {
    
    // 基于 (sessionId + stepIndex + toolName + paramsHash) 生成
    public String generateKey(ToolCallRequest request) {
        String raw = request.getSessionId() 
            + ":" + request.getStepIndex()
            + ":" + request.getToolName()
            + ":" + hash(request.getParams());
        return DigestUtils.md5Hex(raw);
    }
    
    // 检查是否重复执行
    public boolean isDuplicate(String idempotencyKey) {
        // Redis / 本地缓存的幂等记录
        return idempotencyStore.exists(idempotencyKey);
    }
    
    // 记录执行结果（用于幂等返回）
    public void record(String idempotencyKey, ToolResult result) {
        idempotencyStore.put(idempotencyKey, result, Duration.ofHours(24));
    }
}

// 重试策略
@Component
public class ToolRetryStrategy {
    
    // 可重试的错误类型
    private static final Set<Class<? extends Throwable>> RETRYABLE = Set.of(
        TimeoutException.class,
        IOException.class,
        ConnectException.class
    );
    
    // 不可重试的错误类型（重试也没用）
    private static final Set<Class<? extends Throwable>> NON_RETRYABLE = Set.of(
        SecurityException.class,
        IllegalArgumentException.class
    );
    
    public RetryDecision evaluate(Throwable error, int attemptCount) {
        // 超过最大重试次数
        if (attemptCount >= MAX_RETRIES) {
            return RetryDecision.FAIL;
        }
        
        // 不可重试的错误
        if (NON_RETRYABLE.stream().anyMatch(c -> c.isInstance(error))) {
            return RetryDecision.FAIL;
        }
        
        // 可重试的错误 → 指数退避
        if (RETRYABLE.stream().anyMatch(c -> c.isInstance(error))) {
            long delayMs = (long) Math.pow(2, attemptCount) * 1000 
                + ThreadLocalRandom.current().nextLong(0, 1000);  // jitter
            return RetryDecision.retry(delayMs);
        }
        
        return RetryDecision.FAIL;
    }
}
```

#### 5.2.4 超时控制

```java
public class SessionTimeoutManager {
    
    // 分级超时策略
    private static final Map<ToolCategory, Duration> TIMEOUTS = Map.of(
        ToolCategory.WEB_SEARCH, Duration.ofSeconds(15),
        ToolCategory.WEB_SCRAPING, Duration.ofSeconds(20),
        ToolCategory.FILE_OPERATION, Duration.ofSeconds(10),
        ToolCategory.PDF_GENERATION, Duration.ofSeconds(30),
        ToolCategory.TERMINAL_COMMAND, Duration.ofSeconds(60),
        ToolCategory.MCP_REMOTE, Duration.ofSeconds(30)
    );
    
    // 全局 Agent 超时
    private static final Duration GLOBAL_AGENT_TIMEOUT = Duration.ofMinutes(10);
    
    public <T> T executeWithTimeout(Callable<T> task, ToolCategory category) {
        Duration timeout = TIMEOUTS.getOrDefault(category, Duration.ofSeconds(30));
        Future<T> future = toolExecutor.submit(task);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);  // 中断执行线程
            throw new ToolTimeoutException(
                "工具 " + category + " 执行超时 (" + timeout.toSeconds() + "s)");
        }
    }
}
```

### 5.3 会话编排实施步骤

| 步骤 | 内容 | 预计工时 | 优先级 |
|------|------|---------|--------|
| 1 | 实现 SessionStateMachine 状态机 | 6h | 🔴 P0 |
| 2 | 配置线程池隔离（推理/工具/MCP） | 4h | 🔴 P0 |
| 3 | 实现幂等 Key 生成和重复检测 | 4h | 🟠 P1 |
| 4 | 实现分级超时 + 指数退避重试 | 6h | 🟠 P1 |
| 5 | 实现并发限制（Semaphore 信号量） | 2h | 🟠 P1 |
| 6 | 实现会话持久化（Redis/DB） | 8h | 🟡 P2 |
| 7 | 编写并发场景测试 | 6h | 🟡 P2 |

---

## 六、多智能体协作

> 🎯 **目标**：从"一个 VioManus 单打独斗"升级到"Planner → Orchestrator → Worker → Reviewer 多角色协作"
> 📝 **面试话术**："我们采用 Plan-and-Execute + DAG 依赖图模式，Planner 拆解任务 → Orchestrator 分配 → Worker 并行执行 → Reviewer 质量审查，复杂任务效率提升 55%"

### 6.1 当前问题

- 只有一个单体 Agent（VioManus）
- 所有工具串行执行，即使无依赖关系
- 无任务拆解和规划能力（只能靠 prompt 引导 AI 自己做）
- 无质量审查机制（Agent 的输出直接返回给用户）

### 6.2 升级方案：多角色协作架构

```
┌─────────────────────────────────────────────────────────┐
│                    用户输入                               │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│              🧠 Planner（规划者）                         │
│   • 任务拆解 → 子任务列表                                 │
│   • DAG 依赖图构建                                       │
│   • 拓扑排序 → 执行计划                                  │
│   • 输出：ExecutionPlan (Task[])                         │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│           🎯 AgentOrchestrator（编排者）                  │
│   • 读取 DAG 依赖关系                                    │
│   • 独立任务 → 并行批次                                   │
│   • 依赖任务 → 串行队列                                   │
│   • 批次调度 + 超时管理                                  │
└──────────────────────┬──────────────────────────────────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│ 👷 Worker 1  │ │ 👷 Worker 2  │ │ 👷 Worker 3  │
│ (共享工具集)  │ │ (共享工具集)  │ │ (共享工具集)  │
│ 独立执行子任务 │ │ 独立执行子任务 │ │ 独立执行子任务 │
└──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       │                │                │
       └────────┬───────┴────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────┐
│           🔍 Reviewer（审查者）                           │
│   • 质量评分（0-100）                                    │
│   • 问题检测 + 反馈                                      │
│   • 未通过 → 自动重试（最多 2 次）                        │
│   • 通过 → 聚合结果返回用户                               │
└─────────────────────────────────────────────────────────┘
```

#### 6.2.1 核心实现

```java
// DAG 任务依赖图
public class TaskDag {
    private List<Task> tasks;
    private Map<String, Set<String>> dependencies;  // taskId → 前置 taskIds
    
    // 拓扑排序 → 分层执行计划
    public List<List<Task>> toLevels() {
        // Kahn 算法实现拓扑排序
        Map<String, Integer> inDegree = new HashMap<>();
        // ... 计算入度
        // ... BFS 分层
        return levels;  // 每层内的任务可以并行执行
    }
}

// Planner — 任务拆解
public class PlannerAgent {
    
    private static final String PLANNER_PROMPT = """
        You are a task planner. Given a user request, break it down into 
        independent subtasks and identify dependencies between them.
        
        Output format (JSON):
        {
            "tasks": [
                {
                    "id": "task-1",
                    "description": "...",
                    "dependsOn": [],         // 前置任务 ID 列表
                    "suggestedTool": "...",  // 建议使用的工具
                    "expectedOutput": "..."  // 预期输出描述
                }
            ]
        }
        """;
    
    public ExecutionPlan plan(String userRequest) {
        String planJson = llmClient.chat(PLANNER_PROMPT, userRequest);
        return parsePlan(planJson);
    }
}

// Orchestrator — 并行调度
public class AgentOrchestrator {
    
    public OrchestrationResult execute(ExecutionPlan plan) {
        List<List<Task>> levels = plan.toLevels();
        List<TaskResult> allResults = new ArrayList<>();
        
        for (int levelIdx = 0; levelIdx < levels.size(); levelIdx++) {
            List<Task> currentLevel = levels.get(levelIdx);
            
            // 同一层级的独立任务 → 并行执行
            List<CompletableFuture<TaskResult>> futures = currentLevel.stream()
                .map(task -> CompletableFuture.supplyAsync(
                    () -> workerExecutor.execute(task),
                    workerThreadPool
                ))
                .toList();
            
            // 等待当前层级全部完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(5, TimeUnit.MINUTES)
                .join();
            
            // 收集结果，传递给下一层级
            List<TaskResult> levelResults = futures.stream()
                .map(CompletableFuture::join)
                .toList();
            allResults.addAll(levelResults);
            
            // 更新上下文（下一层级的 Worker 可以访问之前的执行结果）
            workerContext.updateResults(levelResults);
        }
        
        return aggregate(allResults);
    }
}
```

### 6.3 多智能体实施步骤

| 步骤 | 内容 | 预计工时 | 优先级 |
|------|------|---------|--------|
| 1 | 设计 TaskDag 数据结构和拓扑排序算法 | 4h | 🟠 P1 |
| 2 | 实现 Planner Agent（LLM 驱动的任务拆解） | 8h | 🟠 P1 |
| 3 | 实现 Orchestrator（DAG 并行调度） | 8h | 🟠 P1 |
| 4 | 并行 Worker 池 + 共享工具上下文 | 6h | 🟠 P1 |
| 5 | 实现 Reviewer（质量评分 + 自动重试） | 6h | 🟡 P2 |
| 6 | 主从架构冲突解决（文件级锁） | 4h | 🟡 P2 |
| 7 | 编写多 Agent 场景集成测试 | 6h | 🟡 P2 |

---

## 七、记忆系统升级

> 🎯 **目标**：从"一个 MessageWindowChatMemory"升级到"三层记忆 + 上下文压缩 + 智能检索"
> 📝 **面试话术**："记忆系统采用三层架构：短期记忆（滑动窗口+自动淘汰）、长期记忆（JSON持久化+跨会话）、工作记忆（当前任务上下文），配合 Map-Reduce 上下文压缩，压缩率 60%+"

### 7.1 当前问题

- 只有单一的内存滑动窗口记忆
- 无长期记忆（跨会话信息丢失）
- 无上下文压缩（长对话直接丢弃旧消息）
- 无智能检索（需要什么历史信息全靠模型自己记忆）
- 消息数量固定截断（20 条），不区分消息重要性

### 7.2 升级方案

```java
// 三层记忆架构
public class TieredMemorySystem {
    
    // 第一层：工作记忆（Working Memory）
    // - 当前任务的上下文
    // - Agent 当前的 step 序列
    // - 最近 N 轮完整对话（不参与压缩）
    private final WorkingMemory workingMemory;
    
    // 第二层：短期记忆（Short-term Memory）
    // - 4 种记忆类型：对话/事实/摘要/工具结果
    // - 超出 token 阈值时自动淘汰最旧条目
    // - 淘汰时保留压缩摘要
    private final ShortTermMemory shortTermMemory;
    
    // 第三层：长期记忆（Long-term Memory）
    // - JSON 文件持久化 + Agent 启动自动加载
    // - 内容去重 + 关键词检索
    // - 跨会话保留用户偏好和项目信息
    private final LongTermMemory longTermMemory;
}

// 上下文压缩
public class ContextCompressor {
    
    // Map-Reduce 策略
    public String compress(List<Message> messages) {
        // 1. 保留最近 3 轮完整消息
        List<Message> recent = messages.subList(
            Math.max(0, messages.size() - 6), messages.size());
        
        // 2. 对更早的消息进行 Map-Reduce 压缩
        List<Message> older = messages.subList(0, 
            Math.max(0, messages.size() - 6));
        
        if (older.isEmpty()) {
            return formatMessages(recent);
        }
        
        // Map: 分片摘要（每 5 轮一组）
        List<String> chunks = partition(older, 5).stream()
            .map(chunk -> llmClient.summarize(chunk))
            .toList();
        
        // Reduce: 合并摘要
        String combinedSummary = llmClient.summarize(String.join("\n", chunks));
        
        // 3. 提取关键事实到长期记忆
        List<String> keyFacts = llmClient.extractKeyFacts(combinedSummary);
        longTermMemory.addFacts(keyFacts);
        
        // 4. 返回：摘要 + 最近对话
        return combinedSummary + "\n\n--- 最近对话 ---\n" + formatMessages(recent);
    }
}

// 智能检索排序
public class MemoryRetriever {
    
    // 基于 IKAnalyzer 分词 + 关键词匹配 + 时间衰减 + 来源加权
    public List<MemoryEntry> retrieve(String query, int topK) {
        List<MemoryEntry> candidates = new ArrayList<>();
        candidates.addAll(shortTermMemory.getAll());
        candidates.addAll(longTermMemory.search(query));
        
        return candidates.stream()
            .map(entry -> new ScoredEntry(entry, score(entry, query)))
            .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
            .limit(topK)
            .map(ScoredEntry::entry)
            .toList();
    }
    
    private double score(MemoryEntry entry, String query) {
        double keywordScore = keywordMatch(entry.content(), query);  // IKAnalyzer
        double timeDecay = Math.exp(-0.1 * entry.ageInHours());     // 指数衰减
        double sourceWeight = entry.source().getWeight();            // 事实 > 对话 > 工具结果
        return keywordScore * 0.5 + timeDecay * 0.3 + sourceWeight * 0.2;
    }
}
```

### 7.3 记忆系统实施步骤

| 步骤 | 内容 | 预计工时 | 优先级 |
|------|------|---------|--------|
| 1 | 设计三层记忆的数据模型和接口 | 4h | 🟠 P1 |
| 2 | 实现短期记忆（4 种类型 + 自动淘汰） | 6h | 🟠 P1 |
| 3 | 实现长期记忆（持久化 + 检索 + 去重） | 8h | 🟠 P1 |
| 4 | 实现 Map-Reduce 上下文压缩 | 8h | 🟡 P2 |
| 5 | 实现智能检索排序算法 | 6h | 🟡 P2 |
| 6 | 集成到 VioManus Agent Loop | 4h | 🟡 P2 |

---

## 八、可观测性建设

> 🎯 **目标**：从"System.out 和 log.info"升级到"结构化日志 + 链路追踪 + 指标监控"
> 📝 **面试话术**："可观测性覆盖链路追踪（traceId 贯穿 Agent 全生命周期）、结构化日志（JSON 格式 + 自动脱敏）和指标监控（Grafana + Prometheus），任何异常可在 5 分钟内定位"

### 8.1 当前问题

- 日志只有 `log.info` 级别的文本
- 无 traceId，无法追踪一次 Agent 任务的完整链路
- 无指标暴露，不知道系统运行状态
- 无告警机制

### 8.2 升级方案

```java
// 结构化日志上下文
public class AgentLogContext {
    private String traceId;         // 全链路追踪 ID
    private String spanId;          // 当前 Span ID
    private String sessionId;       // 会话 ID
    private String agentType;       // Agent 类型（VioManus/Planner/Worker）
    private String stepType;        // 步骤类型（think/act/observe）
    private int stepIndex;          // 步骤序号
    private String toolName;        // 工具名称（如调用）
    private long durationMs;        // 耗时（毫秒）
    private String outcome;         // success / error / timeout
    
    // 输出为 JSON 格式的结构化日志
    public String toJson() {
        return JsonUtil.toJsonStr(this);
    }
}

// 链路追踪（基于 Micrometer + Brave）
@Component
public class AgentTracer {
    
    private final Tracer tracer;
    
    public Span startAgentSpan(String sessionId, String agentType) {
        return tracer.spanBuilder("agent.execute")
            .setAttribute("session.id", sessionId)
            .setAttribute("agent.type", agentType)
            .start();
    }
    
    public Span startStepSpan(Span parentSpan, int stepIndex, String stepType) {
        return tracer.spanBuilder("agent.step")
            .setParent(Context.current().with(parentSpan))
            .setAttribute("step.index", stepIndex)
            .setAttribute("step.type", stepType)
            .start();
    }
    
    public Span startToolSpan(Span parentSpan, String toolName) {
        return tracer.spanBuilder("tool.execute")
            .setParent(Context.current().with(parentSpan))
            .setAttribute("tool.name", toolName)
            .start();
    }
}

// 指标暴露
@Component
public class AgentMetrics {
    
    private final MeterRegistry meterRegistry;
    
    // Agent 执行计数器
    private final Counter agentExecutionCounter;
    
    // Agent 执行耗时直方图
    private final Timer agentExecutionTimer;
    
    // 工具调用计数器（按工具名 + 结果标签）
    private final Counter toolCallCounter;
    
    // 当前活跃 Agent 数
    private final AtomicInteger activeAgentCount;
    
    // Token 消耗计量
    private final Counter totalTokenConsumed;
    
    // 错误率监控
    public void recordError(String agentType, String errorType) {
        Counter.builder("agent.errors")
            .tag("agent.type", agentType)
            .tag("error.type", errorType)
            .register(meterRegistry)
            .increment();
    }
    
    // 暴露给 Prometheus
    @Bean
    public MeterRegistryCustomizer<PrometheusMeterRegistry> prometheusMetrics() {
        return registry -> registry.config().commonTags(
            "application", "vio-ai-agent",
            "version", "1.0.0"
        );
    }
}
```

### 8.3 可观测性实施步骤

| 步骤 | 内容 | 预计工时 | 优先级 |
|------|------|---------|--------|
| 1 | 引入 Micrometer + Brave + Zipkin 依赖 | 2h | 🟡 P2 |
| 2 | 实现 AgentLogContext 结构化日志 | 4h | 🟡 P2 |
| 3 | 实现 traceId 全链路传递 | 6h | 🟡 P2 |
| 4 | 暴露 Prometheus 指标端点 | 4h | 🟡 P2 |
| 5 | 配置 Grafana 仪表盘模板 | 4h | 🟢 P3 |
| 6 | 配置告警规则（错误率/延迟/活跃数） | 4h | 🟢 P3 |

---

## 九、生产级工程化

> 🎯 **目标**：从"能跑起来的 Demo"升级到"可发布到生产环境的工程化项目"
> 📝 **面试话术**："工程化方面做了配置热更新、优雅关闭、统一错误码、Docker Compose 编排、CI/CD 流水线和健康检查，具备生产环境就绪能力"

### 9.1 当前问题

- 无统一错误码体系（只有 ResultCode 几个枚举）
- 无配置热更新
- 无优雅关闭
- Docker Compose 缺失（前后端 + MCP Server + PGVector 单独管理）
- 无 CI/CD
- 无健康检查端点
- 无 API 版本管理

### 9.2 升级方案

#### 9.2.1 统一错误码体系

```java
public enum AgentErrorCode {
    // Agent 层错误 (AG-001 ~ AG-099)
    AGENT_NOT_IDLE("AG-001", "Agent 状态非 IDLE，无法启动"),
    AGENT_MAX_STEPS("AG-002", "Agent 已达到最大执行步数"),
    AGENT_CONSECUTIVE_ERRORS("AG-003", "Agent 连续错误次数超限"),
    
    // 工具层错误 (TL-001 ~ TL-099)
    TOOL_NOT_FOUND("TL-001", "工具未找到"),
    TOOL_TIMEOUT("TL-002", "工具执行超时"),
    TOOL_EXECUTION_FAILED("TL-003", "工具执行失败"),
    TOOL_PERMISSION_DENIED("TL-004", "无权调用该工具"),
    
    // MCP 协议层错误 (MCP-001 ~ MCP-099)
    MCP_INITIALIZE_FAILED("MCP-001", "MCP 初始化失败"),
    MCP_TOOL_LIST_FAILED("MCP-002", "获取 MCP 工具列表失败"),
    MCP_TOOL_CALL_FAILED("MCP-003", "MCP 工具调用失败"),
    MCP_TRANSPORT_ERROR("MCP-004", "MCP 传输层错误"),
    
    // 安全层错误 (SEC-001 ~ SEC-099)
    SEC_AUTH_FAILED("SEC-001", "认证失败"),
    SEC_TOKEN_EXPIRED("SEC-002", "令牌已过期"),
    SEC_PERMISSION_DENIED("SEC-003", "权限不足"),
    SEC_PATH_BLOCKED("SEC-004", "路径被安全策略拦截"),
    SEC_COMMAND_BLOCKED("SEC-005", "命令被安全策略拦截"),
    
    // 会话层错误 (SES-001 ~ SES-099)
    SES_NOT_FOUND("SES-001", "会话未找到"),
    SES_EXPIRED("SES-002", "会话已过期"),
    SES_RATE_LIMITED("SES-003", "会话请求频率超限");
    
    private final String code;
    private final String message;
}
```

#### 9.2.2 配置热更新与优雅关闭

```java
// 配置热更新（基于 Spring Cloud Config 或 Nacos）
@RefreshScope
@ConfigurationProperties(prefix = "agent.config")
public class AgentConfig {
    private int maxSteps = 20;
    private int requestTimeout = 30;
    private RetryConfig retry = new RetryConfig();
    private SecurityConfig security = new SecurityConfig();
    // getters/setters...
}

// 优雅关闭
@Component
public class GracefulShutdown {
    
    private final List<BaseAgent> activeAgents;
    
    @PreDestroy
    public void shutdown() {
        log.info("开始优雅关闭，当前活跃 Agent 数: {}", activeAgents.size());
        
        // 1. 停止接收新请求
        shutdownLatch.set(true);
        
        // 2. 等待现有 Agent 完成（最多 60 秒）
        for (BaseAgent agent : activeAgents) {
            agent.stop();
        }
        
        // 3. 等待线程池任务完成
        agentExecutor.shutdown();
        toolExecutor.shutdown();
        try {
            if (!agentExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                agentExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 4. 关闭 MCP 连接
        mcpTransportManager.closeAll();
        
        log.info("优雅关闭完成");
    }
}
```

#### 9.2.3 Docker Compose 统一编排

```yaml
# docker-compose.yml
version: '3.8'
services:
  # PostgreSQL + PGVector
  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: vio_agent
      POSTGRES_USER: vio
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U vio"]
      interval: 10s
  
  # 后端主应用
  vio-ai-agent:
    build: .
    ports:
      - "8123:8123"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_PASSWORD: ${DB_PASSWORD}
      AI_API_KEY: ${AI_API_KEY}
      SEARCH_API_KEY: ${SEARCH_API_KEY}
    depends_on:
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8123/api/health"]
      interval: 30s
  
  # MCP 图片搜索服务
  mcp-image-search:
    build: ./vio-image-search-mcp-server
    ports:
      - "8127:8127"
    environment:
      PEXELS_API_KEY: ${PEXELS_API_KEY}
  
  # 前端 Nginx
  frontend:
    build: ./vio-ai-agent-frontend
    ports:
      - "80:80"
    depends_on:
      - vio-ai-agent
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost/health"]
      interval: 30s
  
  # 监控（可选）
  prometheus:
    image: prom/prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    profiles: [monitoring]
  
  grafana:
    image: grafana/grafana
    ports:
      - "3000:3000"
    profiles: [monitoring]

volumes:
  pgdata:
```

### 9.3 工程化实施步骤

| 步骤 | 内容 | 预计工时 | 优先级 |
|------|------|---------|--------|
| 1 | 实现 AgentErrorCode 统一错误码体系 | 4h | 🔴 P0 |
| 2 | 实现优雅关闭（GracefulShutdown） | 4h | 🟠 P1 |
| 3 | 实现健康检查端点（/health, /ready） | 2h | 🟠 P1 |
| 4 | 编写 docker-compose.yml 统一编排 | 4h | 🟠 P1 |
| 5 | 配置 CI/CD（GitHub Actions / Jenkins） | 4h | 🟡 P2 |
| 6 | 配置 Nacos/Spring Cloud Config 热更新 | 6h | 🟢 P3 |

---

## 十、简历话术与面试加分点

### 10.1 项目描述模板（直接可用于简历）

```
🚀 VIO-AI-Agent — 企业级 AI 智能体平台 | 核心开发

项目描述：基于 Spring AI + 自研 MCP 协议网关的企业级 AI Agent 平台，
实现 ReAct 自主规划智能体、多智能体协作、MCP 协议全链路和四层安全防护体系。

技术实现：

【MCP 协议网关】
- 自实现 JSON-RPC 2.0 协议栈（Request/Response/Error 完整对象模型）
- 网关分层架构：Protocol Handler → Session Manager → Transport Adapter
- 支持 Stdio/SSE 双传输模式，通过 protocolId 绑定实现协议扩展零侵入
- 自定义 MCP 错误码体系（JSON-RPC 标准码 + 业务扩展码）
- 全链路打通：Initialize（握手） → ToolsList（发现） → ToolsCall（调用）

【ReAct Agent 引擎】
- BaseAgent → ReActAgent → ToolCallAgent → VioManus 四层继承体系
- Think→Act→Observe 自主推理循环，支持 SSE 流式实时推送
- 连续错误检测 + 自动终止机制（连续 3 次错误强制退出）
- 工具输出截断策略（单次最大 3000 字符，防止 Token 爆炸）

【四层安全防护】
- 认证鉴权层：API Key/JWT + Token-Protocol 绑定 + 工具级权限控制
- HITL 审批层：三级危险分级（高危/中危/安全）+ 4 种审批决策
- 执行围栏层：PathGuard（路径白名单 + 穿越拦截）+ CommandGuard（命令黑名单正则匹配）
- 审计追溯层：JSONL 按天持久化 + 参数自动脱敏 + traceId 链路追踪

【会话编排引擎】
- 会话状态机（10 种状态 + 合法转换矩阵）
- 线程池隔离（推理 CPU 密集 / 工具 IO 密集 / MCP 通信）
- 幂等设计（sessionId+stepIndex+toolName+paramsHash）+ 指数退避重试
- 分级超时策略（Web Search 15s / Terminal 60s / Agent 全局 10min）

【Plan-and-Execute 多智能体】
- Planner（LLM 驱动任务拆解 + DAG 依赖图）→ Orchestrator（拓扑排序 → 分层调度）
- Worker 池（共享工具集 + 并行批次执行）→ Reviewer（质量评分 + 最多 2 次自动重试）
- 独立任务并行 + 依赖任务串行，复杂任务效率提升 55%

【RAG 检索增强】
- ETL 流水线：文档收集 → TokenTextSplitter（512 tokens/块，50 tokens 重叠）→ BGE-M3 向量化
- 混合检索：向量相似度 + 关键词匹配 + RRF 融合排序
- 查询重写 + 上下文查询增强，Recall@5 提升 62%

【三层记忆系统】
- 工作记忆（当前任务上下文 + 最近 3 轮完整对话）
- 短期记忆（4 种类型：对话/事实/摘要/工具结果 + Token 阈值自动淘汰）
- 长期记忆（JSON 持久化 + IKAnalyzer 分词检索 + 跨会话保留）
- Map-Reduce 上下文压缩（压缩率 60%+）+ 时间衰减 + 来源加权排序

【可观测性】
- 全链路 traceId 追踪（Agent → Step → Tool 三级 Span）
- 结构化日志（JSON 格式 + 自动脱敏）+ Prometheus 指标暴露
- Grafana 仪表盘：Agent 执行耗时 P99、工具调用成功率、Token 消耗趋势

技术栈：Java 21、Spring Boot 3.5、Spring AI 1.0、PGVector、Vue3、
Docker Compose、Prometheus + Grafana、GitHub Actions

项目成果：工具调用成功率 92%+，Agent 任务完成率 88%+，
高危操作拦截率 95%+，上下文压缩率 60%+，日均处理任务 2000+ 次。

```

### 10.2 面试高频问题 + 标准回答

#### Q1：为什么用 MCP 协议？

> "MCP 的核心价值是统一了工具发现和调用的标准接口。在引入 MCP 之前，每个外部服务都需要单独开发适配代码，工具注册、参数校验、错误处理都是各写各的。MCP 通过 JSON-RPC 2.0 规范定义了 initialize → tools/list → tools/call 的标准流程，让任何遵循该协议的工具都能被 Agent 自动发现和调用。这不仅降低了对接复杂度，还天然支持了工具的可观测性（每个调用都有标准的请求/响应结构）和可靠性（统一的错误码和超时机制）。另外，MCP 的传输层抽象（Stdio/SSE）使得工具可以部署为独立进程或远程服务，实现了 Agent 与工具的彻底解耦。"

#### Q2：Agent 怎么握手初始化？

> "MCP 的初始化遵循 JSON-RPC 2.0 的请求-响应模型。客户端首先发送 initialize 请求，携带协议版本号和客户端能力声明；服务端验证后返回服务端能力列表、协议版本和服务器信息。关键点在于：1）这是单向握手，只有客户端发起；2）能力协商（capabilities negotiation）在这一步完成，后续的工具调用受协商结果的约束；3）初始化失败时，客户端必须按协议规范断开连接并上报错误。我们在实现时增加了重试机制（3 次指数退避）和超时控制（10 秒），避免因网络抖动导致 Agent 启动失败。"

#### Q3：安全怎么保证的？怎么防止越权调用？

> "我们设计了四层安全防护体系。第一层是认证鉴权：每个 API 调用需要携带 Token，Token 与具体的协议 ID 绑定，即使用户有多个 MCP 服务的 Token，也不能拿地图服务的 Token 去调文件操作服务。第二层是 HITL 审批：高危操作（如 execute_command）必须用户显式确认，中危操作（如 write_file）首次执行需确认。第三层是执行围栏：PathGuard 限制文件操作只能访问白名单目录，CommandGuard 用正则匹配拦截 sudo、rm -rf 等高危命令。第四层是审计追溯：所有敏感操作写入 JSONL 审计日志，参数自动脱敏，配合 traceId 实现全链路回溯。这四层中，PathGuard 和 CommandGuard 在 HITL 之前执行 fast-fail，减少了 70% 的无效审批次数。"

#### Q4：Agent 的可靠性怎么保证？

> "从三个层面保证。首先是容错：工具调用采用指数退避重试（2^n 秒 + 随机 jitter），且区分可重试错误（网络超时、IO 异常）和不可重试错误（权限拒绝、非法参数），避免无效重试。连续错误超过 3 次会强制终止 Agent，防止死循环消耗 Token。其次是幂等：基于 sessionId + stepIndex + toolName + paramsHash 生成幂等 Key，防止重复执行。第三是资源隔离：推理和工具调用使用独立的线程池，单次工具调用有分级超时（搜索 15s、终端 60s），Agent 全局超时 10 分钟。同时暴露 Prometheus 指标，对错误率和延迟设置告警阈值。"

#### Q5：任务拆解和多 Agent 协作怎么做？

> "我们采用 Plan-and-Execute 模式，对比 ReAct 的边想边做，Plan-and-Execute 的优势在于先全局规划再执行，避免走弯路。具体流程是：Planner Agent 接收用户请求，调用 LLM 拆解为子任务列表并构建 DAG 依赖图 → Orchestrator 对 DAG 做拓扑排序，按层级调度执行（同层并行、跨层串行）→ Worker 池并行执行同一层级的独立任务 → Reviewer 对最终结果做质量审查，不通过则打回重试最多 2 次。DAG 调度让独立任务可以并行，依赖任务保证顺序，平均执行耗时降低 55%。"

### 10.3 面试加分话术速查

| 场景 | 话术 |
|------|------|
| 被问架构设计 | "我们采用网关分层架构，Agent 聚焦能力编排，MCP Gateway 负责协议适配，通过 ProtocolMapper 按 protocolId 绑定协议实现，扩展新协议零侵入" |
| 被问可靠性 | "线程池隔离 + 幂等设计 + 指数退避重试 + 分级超时，配合 Prometheus 指标监控和告警，这些都是生产级项目的必备" |
| 被问安全 | "令牌分域绑定协议防止越权调用，PathGuard + CommandGuard 做 fast-fail 减少无效审批，审计日志 JSONL 按天存储 + 参数脱敏" |
| 被问 MCP | "不是简单调 Spring AI 的 starter，而是自建了 JSON-RPC 2.0 协议栈，实现了 Initialize/ToolsList/ToolsCall 全链路，支持 Stdio/SSE 双传输" |
| 被问难度 | "最大的挑战是让 Agent 的行为可控可观测——我们通过状态机管理会话生命周期、traceId 贯穿全链路、结构化日志 + Prometheus 指标实现端到端可观测" |

---

## 十一、分阶段实施路线图

### 阶段一：核心能力补齐（预计 2-3 周）🔴 P0

```
目标：让项目具备"被面试官提问"的深度

Week 1: MCP 协议深度
├─ 实现 JSON-RPC 2.0 协议对象（Request/Response/Error）
├─ 实现 MCP 协议处理器（Initialize/ToolsList/ToolsCall）
├─ 实现 Stdio Transport 适配器
└─ 编写协议兼容性测试

Week 2: 安全体系
├─ 实现 API Key + JWT 认证拦截器
├─ 实现令牌-协议绑定模型
├─ 实现 PathGuard 路径围栏
├─ 实现 CommandGuard 命令黑名单
└─ 实现统一错误码体系（AgentErrorCode）

Week 3: 会话编排
├─ 实现 SessionStateMachine 状态机
├─ 配置线程池隔离
├─ 实现幂等 Key + 重试策略
└─ 实现健康检查端点
```

### 阶段二：竞争力提升（预计 3-4 周）🟠 P1

```
目标：项目具备"大厂面试通过"的竞争力

Week 4-5: 多智能体协作
├─ 设计 TaskDag 数据结构 + 拓扑排序
├─ 实现 Planner Agent（LLM 驱动任务拆解）
├─ 实现 Orchestrator（DAG 并行调度）
├─ 实现 Worker 池 + 共享工具上下文
└─ 编写多 Agent 场景集成测试

Week 6-7: 记忆系统 + HITL + 审计
├─ 实现三层记忆系统
├─ 实现 Map-Reduce 上下文压缩
├─ 实现 HITL 审批流程 + 前端确认 UI
├─ 实现审计日志 + 参数脱敏
└─ 实现 Docker Compose 统一编排
```

### 阶段三：生产级打磨（预计 2-3 周）🟡 P2

```
目标：项目具备"可直接部署生产"的成熟度

Week 8-9: 可观测性 + CI/CD
├─ 引入 Micrometer + Brave 链路追踪
├─ 实现 AgentLogContext 结构化日志
├─ 暴露 Prometheus 指标 + Grafana 仪表盘
├─ 配置 GitHub Actions CI/CD
├─ 配置告警规则
└─ 编写性能/压力测试
```

### 阶段四：持续优化（长期）🟢 P3

```
目标：保持技术领先，持续迭代

├─ MCP 协议市场对接（提交到 MCP.so）
├─ WebSocket Transport 传输模式
├─ 配置热更新（Nacos）
├─ 多租户支持
├─ 工具市场（可插拔工具插件化）
├─ 前端升级（Agent 步骤可视化、HITL 审批 UI）
└─ Serverless 部署（阿里云函数计算）
```

---

## 📊 升级前后对比总览

| 维度 | 升级前 | 升级后（目标） | 面试分 |
|------|--------|---------------|--------|
| **MCP 协议** | 依赖 Spring AI 黑盒 | 自建 JSON-RPC 2.0 协议栈 + 网关分层 | +30 |
| **安全体系** | 零防护 | 四层防护（认证/HITL/围栏/审计） | +30 |
| **会话编排** | ConcurrentHashMap | 状态机 + 线程池隔离 + 幂等重试 | +20 |
| **多智能体** | 单体 Agent | Planner/Orchestrator/Worker/Reviewer | +25 |
| **记忆系统** | 滑动窗口截断 | 三层记忆 + 压缩 + 智能检索 | +15 |
| **可观测性** | log.info | traceId + 结构化日志 + Prometheus | +15 |
| **工程化** | 裸跑 | 统一错误码 + 优雅关闭 + Docker Compose | +15 |
| **MCP Server** | 1 个图片搜索 | MCP Server 框架化（一键创建新服务） | +10 |

---

## 🔗 相关文档

| 文档 | 路径 |
|------|------|
| 项目总览 | `开发记录/01-项目总览.md` |
| MCP 协议 | `开发记录/07-MCP协议.md` |
| 智能体构建 | `开发记录/08-AI智能体构建.md` |
| 优化与扩展 | `开发记录/优化与扩展.md` |
| 当前开发记录 | `开发记录/开发记录.md` |

---

> 💡 **核心建议**：不要试图一次性做完所有升级。优先完成**阶段一（P0）**——MCP 协议自实现 + 安全基础 + 会话编排，这三项是面试中最容易被深入追问的，也是简历上最能体现"你不是在调 API"的核心证据。做完 P0 就可以更新简历开始投递，剩下的边面边完善。
