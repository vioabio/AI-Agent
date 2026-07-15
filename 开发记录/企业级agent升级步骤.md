# 🏢 企业级 AI Agent 升级步骤 — 开发记录

> 基于 `企业级agent项目升级文档.md` 的分布实施记录
> 最后更新：2026-07-15

---

## Phase 1: JSON-RPC 2.0 协议基础

### 技术栈清单

| 技术 | 用途 | 掌握要求 |
|------|------|---------|
| JSON-RPC 2.0 规范 | 请求/响应/错误对象结构 | 能手写规范格式的请求和响应 JSON |
| Java 21 Records | 不可变协议对象 | 紧凑构造器校验、Jackson 序列化兼容 |
| Jackson ObjectMapper | 序列化/反序列化 | JsonNode 遍历、多态 id 处理 |
| MCP 协议生命周期 | Initialize → ToolsList → ToolsCall | 理解三步握手全链路 |

### 创建文件

**JSON-RPC 2.0 协议对象层** (`com.vio.vioaiagent.mcp.jsonrpc`):

| 文件 | 行数 | 说明 |
|------|------|------|
| `JsonRpcErrorCode.java` | ~70 | 13 个错误码枚举（标准+MCP+业务扩展） |
| `JsonRpcError.java` | ~40 | 不可变错误 record (code, message, data) |
| `JsonRpcRequest.java` | ~70 | 请求 record + 静态工厂 of() |
| `JsonRpcResponse.java` | ~60 | 响应 record + success/error 静态工厂 |
| `JsonRpcCodec.java` | ~120 | 编解码器（基于 Jackson ObjectMapper） |

**MCP 协议模型** (`com.vio.vioaiagent.mcp.protocol.model`):

| 文件 | 行数 | 说明 |
|------|------|------|
| `InitializeRequest.java` | ~30 | 握手请求 |
| `InitializeResult.java` | ~30 | 握手结果 |
| `ServerCapabilities.java` | ~25 | 服务端能力声明 |
| `ToolDefinition.java` | ~30 | 工具定义 (name, description, inputSchema) |
| `ToolCallResult.java` | ~50 | 工具调用结果 + ContentItem 内部 record |
| `ToolListResult.java` | ~25 | 工具列表 + cursor 分页 |

**MCP 协议处理器** (`com.vio.vioaiagent.mcp.protocol`):

| 文件 | 行数 | 说明 |
|------|------|------|
| `McpProtocolHandler.java` | ~50 | MCP 生命周期接口 |
| `McpProtocolSpec.java` | ~130 | 参考实现（工具注册 + 分发） |

**测试:**

| 文件 | 行数 | 说明 |
|------|------|------|
| `JsonRpcErrorCodeTest.java` | ~50 | 13 个错误码查找验证 |
| `JsonRpcCodecTest.java` | ~120 | 编解码往返 + 边界条件 |
| `McpProtocolHandlerTest.java` | ~100 | 协议生命周期测试 |

### 关键设计决策

1. **Records 而非 Lombok @Data**：协议对象是纯数据载体，Java 21 Records 提供开箱即用的不可变性、equals/hashCode、Jackson 兼容
2. **JsonRpcRequest.id 用 Object 类型**：JSON-RPC 2.0 规范允许 String 或 Number 类型的 id，Object + JsonNode 遍历兼容两种
3. **JsonRpcCodec 独立类而非 static 方法**：可注入自定义 ObjectMapper，便于测试和未来扩展
4. **协议模型在 model/ 子包**：与处理器接口分离，保持清晰的依赖方向（model ← handler）
5. **McpProtocolSpec 为参考实现而非唯一实现**：通过 McpProtocolHandler 接口解耦，未来可替换

### 面试话术

> "我们的 JSON-RPC 2.0 协议栈不是简单的 JSON 序列化，而是完整实现了规范定义的对象模型——包括 13 个结构化错误码（从 -32700 的解析错误到 -32013 的权限不足）、请求/响应的严格校验（jsonrpc 字段必为 '2.0'、result 和 error 互斥）、以及基于 Jackson JsonNode 的多态 id 处理（兼容 String 和 Number 两种 id 类型）。编解码器使用策略模式注入 ObjectMapper，方便测试和扩展。"

### 测试结果

Tests run: 23 (jsonrpc: 15 + protocol: 8) — 0 failures, 0 errors ✅
Phase 1 全部测试通过, BUILD SUCCESS

---

## Phase 2: 传输层适配器

### 技术栈清单

| 技术 | 用途 | 掌握要求 |
|------|------|---------|
| Java Process API | StdioTransport 子进程管理 | ProcessBuilder、stdin/stdout 重定向、子进程生命周期 |
| SSE 协议 (RFC 6202) | SseTransport 事件流解析 | text/event-stream 格式、data/event/id 字段 |
| Spring RestClient | SSE Transport HTTP 通信 | 同步 HTTP POST、SSE 订阅 |
| 线程安全 | StdioTransport 写保护 | synchronized、AtomicReference |
| MCP Transport 规范 | Stdio 逐行 JSON + SSE session 关联 | 理解两种传输模式的差异和适用场景 |

### 创建文件

| 文件 | 说明 |
|------|------|
| `McpTransport.java` | 传输层抽象接口 (connect/send/close) |
| `StdioTransport.java` | STDIO 实现（子进程 + JSON 行协议），含内部 ServerParameters record |
| `SseTransport.java` | SSE 实现（RestClient + 手动 SSE 解析 + session URI 关联） |
| `TransportFactory.java` | 静态工厂方法 (createStdio/createSse) |
| `StdioTransportTest.java` | STDIO echo 测试 |
| `SseTransportTest.java` | SSE 集成测试（嵌入 MCP Server） |

### 关键设计决策

1. **send() 同步返回 JsonRpcResponse**：与 Spring AI McpSyncClient 对齐，简化调用方逻辑
2. **StdioTransport 使用 synchronized**：保证 stdin 写入的线程安全，单 writer 模型
3. **SseTransport 手动解析 SSE**：不依赖第三方 SSE 库，RFC 6202 足够简单
4. **TransportFactory 为静态工厂**：轻量级，不引入 Spring 依赖

### 面试话术

> "我们的传输层做了抽象设计——McpTransport 接口定义了统一的 connect/send/close 协议，StdioTransport 和 SseTransport 分别实现了基于子进程管道的通信和基于 HTTP SSE 的通信。Stdio 模式下使用 ProcessBuilder 启动子进程，通过 stdin/stdout 逐行交换 JSON-RPC 消息，并用 synchronized 保证线程安全。SSE 模式下手动实现了 RFC 6202 的事件流解析，支持 session URI 关联——服务端在 SSE 连接建立后返回专属的消息端点，客户端后续的 JSON-RPC 请求通过 POST 发送到该端点。"

### 测试结果

Tests run: 12 (Stdio: 3 + SSE: 3 + Phase 1 accumulated: 23) — 0 failures, 0 errors ✅

---

## Phase 3: 网关 + 会话管理 + 兼容性测试

### 技术栈清单

| 技术 | 用途 | 掌握要求 |
|------|------|---------|
| 会话状态机 | McpSession 生命周期管理 | CREATED→INITIALIZING→READY→CLOSED 转换 |
| ConcurrentHashMap | 并发会话管理 | computeIfAbsent 原子操作 |
| AtomicReference | 线程安全状态管理 | CAS 状态转换 |
| Spring List 注入 | ProtocolMapper 多实现注册 | 优先级排序、责任链模式 |

### 创建文件

| 文件 | 说明 |
|------|------|
| `ProtocolMapper.java` | 协议路由接口 (supports/createHandler/priority) |
| `McpSession.java` | 单连接会话（状态机 + 工具缓存 + 传输 + 处理器） |
| `McpSessionManager.java` | 会话管理器（ConcurrentHashMap + 创建/淘汰/关闭） |
| `McpGateway.java` | 顶层门面（discoverTools/invokeTool/connect/shutdown） |
| `ProtocolMapperTest.java` | 多 mapper 优先级、无匹配测试 |
| `McpSessionManagerTest.java` | 会话 CRUD + 淘汰测试 |
| `McpGatewayTest.java` | 完整生命周期测试 |
| `McpFullStackIntegrationTest.java` | 全栈端到端测试（initialize→listTools→callTool→shutdown） |

### 测试结果

Tests run: 18 (Gateway: 5 + SessionManager: 5 + ProtocolMapper: 3 + FullStack: 5) — 0 failures, 0 errors ✅

### 面试话术

> "我们的 MCP 网关层封装了完整的会话生命周期管理——每次 MCP 连接都有独立的 McpSession，通过 AtomicReference 驱动的状态机保证线程安全的状态转换。会话管理器使用 ConcurrentHashMap 维护所有活跃连接，支持空闲淘汰和优雅关闭。McpGateway 作为顶层门面为 Agent 层提供了 discoverTools/invokeTool 两个核心 API，屏蔽了底层传输和协议的复杂性。"

---

## Phase 4: 桥接层（与 Spring AI MCP 共存）

### 技术栈清单

| 技术 | 用途 | 掌握要求 |
|------|------|---------|
| Spring AI ToolCallback | Agent 工具调用接口 | getToolDefinition() / call(String) |
| 适配器模式 | 自定义 ↔ Spring AI 双向转换 | 接口适配、JSON Schema 字符串 ↔ Map 转换 |

### 创建文件

| 文件 | 说明 |
|------|------|
| `SpringAiMpcBridge.java` | Spring AI → 自定义方向（ToolCallbackProvider → List\<ToolDefinition\>） |
| `CustomToolCallbackAdapter.java` | 自定义 → Spring AI 方向（implements ToolCallback, wrap → ToolCallback[]） |
| `SpringAiMpcBridgeTest.java` | 桥接器单元测试 |
| `CustomToolCallbackAdapterTest.java` | 适配器集成测试 |

### 测试结果

Tests run: 4 (Bridge: 2 + Adapter: 2) — 0 failures, 0 errors ✅

### 面试话术

> "桥接层是迁移过渡的关键——CustomToolCallbackAdapter 将自定义 MCP 栈发现的工具包装为 Spring AI 的 ToolCallback 接口，现有的 ToolCallAgent.getMergedTools() 无需任何修改就能使用自定义栈的工具。反向的 SpringAiMpcBridge 则把 Spring AI MCP 的工具纳入自定义栈的 ToolDefinition 模型管理。这种双向适配器模式保证了新旧协议栈的无缝共存。"

---

## 📊 最终统计

| 指标 | 数值 |
|------|------|
| 新建源文件 | 23 个（main） |
| 新建测试文件 | 14 个（test） |
| 新测试用例数 | 51 个（全部通过） |
| 总源文件数 | 69 个（46 原有 + 23 新增） |
| 既有测试影响 | 0 个（全部通过，仅既有 MCP SSE 测试保持原状） |

### 完整测试清单

| 测试类 | 用例数 | 状态 |
|--------|--------|:--:|
| JsonRpcErrorCodeTest | 6 | ✅ |
| JsonRpcCodecTest | 9 | ✅ |
| McpProtocolHandlerTest | 8 | ✅ |
| ProtocolMapperTest | 3 | ✅ |
| StdioTransportTest | 3 | ✅ |
| SseTransportTest | 3 | ✅ |
| McpGatewayTest | 5 | ✅ |
| McpSessionManagerTest | 5 | ✅ |
| McpFullStackIntegrationTest | 5 | ✅ |
| SpringAiMpcBridgeTest | 2 | ✅ |
| CustomToolCallbackAdapterTest | 2 | ✅ |
| **总计** | **51** | **✅** |

### 包结构总览

```
com.vio.vioaiagent.mcp/
  jsonrpc/          ← Layer 3: JSON-RPC 2.0 (5 files)
  protocol/
    model/          ← MCP 协议数据模型 (6 files)
    (3 files)       ← 接口+实现+路由
  transport/        ← Layer 4: 传输适配器 (4 files)
  gateway/          ← Layer 1+2: 门面+会话 (3 files)
  bridge/           ← 共存桥接 (2 files)
```

---
