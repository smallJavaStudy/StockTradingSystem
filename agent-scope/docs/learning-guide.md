# AgentScope Java 2.0 完整学习指南

> 基于官方文档 (java.agentscope.io) 和 GitHub 源文件，覆盖 14 个核心文档页面

---

## 一、框架总览

**AgentScope** 是一个面向企业级的分布式智能体框架，基于 **Project Reactor** 响应式编程。核心是双层架构：

```
HarnessAgent (工程能力层)
  └── ReActAgent (核心推理循环)
        ├── Model (模型层: OpenAI/DashScope/Anthropic/Gemini/Ollama)
        ├── Toolkit (工具容器: Java Tool + MCP + Skill + ToolGroup)
        ├── Middleware (中间件: 5阶段钩子)
        ├── Permission System (权限: 三态决策)
        └── AgentStateStore (状态持久化: JSON/Redis/MySQL)
```

**Maven 依赖：**

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-harness</artifactId>
  <version>2.0.0</version>
</dependency>
<!-- 按需选模型扩展 -->
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-model-dashscope</artifactId>
  <version>2.0.0</version>
</dependency>
```

---

## 二、快速开始

### 最简 HarnessAgent

```java
HarnessAgent agent = HarnessAgent.builder()
    .name("assistant")
    .sysPrompt("你是一个有用的 AI 助手。")
    .model("dashscope:qwen-plus")  // 字符串形式，自动读环境变量
    .workspace(Paths.get(".agentscope/workspace"))
    .build();

RuntimeContext ctx = RuntimeContext.builder()
    .sessionId("demo").userId("alice").build();

agent.call(new UserMessage("你好！"), ctx).block();
```

### 流式事件

```java
agent.streamEvents(new UserMessage("帮我总结三点"), ctx)
    .doOnNext(event -> {
        if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
            System.out.print(((TextBlockDeltaEvent) event).getDelta());
        } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
            System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallName());
        }
    })
    .blockLast();
```

---

## 三、核心构建块

### 3.1 Agent（智能体）

**核心接口** `ReActAgent`：

| 方法 | 说明 |
|------|------|
| `call(List<Msg>)` / `call(List<Msg>, RuntimeContext)` | 运行推理-行动循环，返回 `Mono<Msg>` |
| `streamEvents(List<Msg>)` / `streamEvents(Msg)` | 流式产出 `AgentEvent` 对象 |
| `observe(Msg)` / `observe(List<Msg>)` | 将消息注入上下文，不触发推理 |

**结构化输出重载**：`call(msgs, structuredOutputClass, runtimeContext)`

**主循环流程：**

```
flowchart TD
A([输入: 消息 / 事件]) --> B{等待外部事件?}
B -- 是 --> C[处理事件 / 更新工具状态]
B -- 否 --> D[将消息添加到上下文]
C --> E
D --> E
E{检查下一步动作} -- 退出 --> F([返回: 等待外部交互])
E -- 推理 --> G[必要时压缩上下文]
G --> H[LLM 调用]
H -- 无工具调用 --> I([返回最终消息])
H -- 有工具调用 --> J[批量工具调用: 串行 / 并发]
J --> L[执行工具调用]
L --> M{权限检查}
M -- 允许 --> N[运行工具 → 结果]
M -- 询问 / 外部 --> O([暂停并发出 RequireUserConfirmEvent])
M -- 拒绝 --> P[将错误结果返回 LLM]
N --> E
P --> E
```

**Builder 参数：**

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `name` | String | 必填 | 智能体标识符 |
| `sysPrompt` | String | 必填 | 系统提示词 |
| `model` | Model | 必填 | 模型（字符串id或Model实例） |
| `toolkit` | Toolkit | `new Toolkit()` | 工具容器 |
| `middlewares` | List | `List.of()` | 中间件链 |
| `stateStore` | AgentStateStore | null（不持久化） | 状态持久化后端 |
| `defaultSessionId` | String | agent name | 无sessionId时的兜底值 |
| `permissionContext` | PermissionContextState | DEFAULT | 权限模式 |
| `modelConfig` | ModelConfig | 默认 | 重试次数和备用模型 |
| `reactConfig` | ReactConfig | 默认 | 最大迭代次数和拒绝处理方式 |
| `maxIters` | int | 10 | ReAct最大迭代次数 |

**关键设计**：Agent 实例是无状态的——同一个 agent 实例可以同时服务多个用户和会话，通过 `RuntimeContext` 的 `(userId, sessionId)` 隔离。

**中断执行：**

```java
// per-session中断
agent.interrupt("alice", "session-001");

// 带消息中断
agent.interrupt(target, new UserMessage("用户已取消操作"));
```

中断后返回带 `GenerateReason.INTERRUPTED` 的 Msg，对话上下文自动保存，下次 call 从中断点恢复。

**多用户并发：**

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant").sysPrompt("...").model("dashscope:qwen-plus")
    .stateStore(new JsonFileAgentStateStore(Paths.get(".../sessions")))
    .build();

// 不同session完全并行，同一session自动串行
agent.call(List.of(new UserMessage("你好")),
    RuntimeContext.builder().userId("alice").sessionId("session-1").build()).block();

agent.call(List.of(new UserMessage("Hi there")),
    RuntimeContext.builder().userId("bob").sessionId("session-2").build()).block();
```

**结构化输出：**

```java
// 定义输出结构
public record WeatherResponse(String location, String temperature, String condition) {}

Msg result = agent.call(List.of(new UserMessage("旧金山天气如何？")), WeatherResponse.class).block();
WeatherResponse weather = result.getStructuredData(WeatherResponse.class);
```

框架自动选择实现路径：
| 路径 | 条件 | 行为 |
|------|------|------|
| 原生路径 | 模型支持 response_format + tools 并行 | 通过 response_format 直接传 JSON Schema |
| 降级路径 | 模型不支持原生结构化输出 | 注入 `generate_response` 合成工具 |

**模型容错：**

```java
ReActAgent.builder()
    .maxRetries(3)                       // 模型调用失败时自动重试
    .fallbackModel("dashscope:qwen-max") // 主模型连续失败后切换
    .build();
```

**内置工具开关：**

| Builder 方法 | 说明 |
|------|------|
| `enableMetaTool(true)` | 注册 list_tools / activate_group 元工具 |
| `enableTaskList()` | 注册任务列表工具，LLM 拆解复杂任务 |

### 3.2 Model（模型层）

**两层架构**：`Credential`（凭证）→ `ChatModelBase`（模型实现）

**支持的Provider**：DashScope / OpenAI / Anthropic / Gemini / Ollama

**字符串模型ID（推荐）**：格式 `<provider>:<model>`，自动读取环境变量

```java
.model("dashscope:qwen-plus")          // 自动读 DASHSCOPE_API_KEY
.model("openai:gpt-4o")                // OPENAI_API_KEY
.model("anthropic:claude-sonnet-4-5")  // ANTHROPIC_API_KEY
.model("gemini:gemini-2.0-flash")      // GEMINI_API_KEY
.model("ollama:llama3")                // 本地Ollama
```

需要对应的模型扩展模块在 classpath 中。

**显式Builder**（需要精细控制超时、自定义endpoint）：

```java
.model(DashScopeChatModel.builder()
    .apiKey("YOUR_API_KEY").modelName("qwen-max")
    .stream(true).formatter(new DashScopeChatFormatter()).build())
```

### 3.3 Message & Event（消息与事件）

**核心区别**：
- **消息（Msg）**：智能体间通信与持久化的基本单元，代表完整对话轮次
- **事件（AgentEvent）**：前端交互与流式传输的基本单元，携带增量进度

单次 call 产生的事件序列最终汇聚成恰好一条 assistant Msg。

**Msg 结构：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `getId()` | String | 唯一消息标识符 |
| `getName()` | String | 发送方名称（可空） |
| `getRole()` | MsgRole | USER / ASSISTANT / SYSTEM / TOOL |
| `getContent()` | List\<ContentBlock\> | 有序内容块列表（不可变） |
| `getMetadata()` | Map | 任意键值元数据 |
| `getTimestamp()` | String | 创建时间 |
| `getUsage()` | ChatUsage | Token 用量（仅assistant消息） |
| `getGenerateReason()` | GenerateReason | 退出原因 |

**GenerateReason 枚举**：MODEL_STOP / TOOL_SUSPENDED / REASONING_STOP_REQUESTED / ACTING_STOP_REQUESTED / ALL_TOOLS_DENIED / INTERRUPTED / MAX_ITERATIONS

**ContentBlock 类型：**

| 块类型 | 说明 | 允许出现在 |
|------|------|------|
| `TextBlock` | 纯文本 | USER、ASSISTANT、SYSTEM |
| `DataBlock` | 二进制数据（图片/音频/视频），通过base64或URL | USER、ASSISTANT |
| `ThinkingBlock` | 模型推理过程（思维链） | ASSISTANT |
| `ToolUseBlock` | 工具调用（id/name/input/state） | ASSISTANT |
| `ToolResultBlock` | 工具执行结果（state） | ASSISTANT |
| `HintBlock` | 以用户上下文形式注入循环的指令 | ASSISTANT |

**创建消息：**

```java
// 用户消息 —— 文本
UserMessage userText = new UserMessage("user", "这张图片里有什么？");

// 多模态用户消息
UserMessage userMulti = new UserMessage("user",
    TextBlock.builder().text("描述这张图片：").build(),
    DataBlock.builder()
        .source(Base64Source.builder().data("...").mediaType("image/png").build())
        .build());

// 系统消息 —— 仅文本
SystemMessage systemMsg = new SystemMessage("system", "你是一个有用的助手。");

// 助手消息 —— 允许所有块类型
AssistantMessage assistantMsg = new AssistantMessage("agent", "结果如下...");
```

**访问内容辅助方法：**

| 方法 | 返回值 |
|------|------|
| `getTextContent()` | 所有TextBlock拼接文本 |
| `getContentBlocks(Class<T>)` | 按类型过滤的块列表 |
| `getFirstContentBlock(Class<T>)` | 首个匹配类型的块 |
| `hasContentBlocks(Class<T>)` | 是否存在指定类型块 |

**28种事件类型**，遵循 `start → delta → end` 模式：

所有事件继承自 `AgentEvent`，携带 `getId()` / `getCreatedAt()` / `getType()` / `getSource()`。事件用 `getReplyId()` 关联到正在构建的消息，用 `getBlockId()` 或 `getToolCallId()` 做流内关联。

**生命周期事件**：
- `AgentStartEvent` — 智能体开始新回复（含 replyId、sessionId、name、role）
- `AgentEndEvent` — 智能体完成回复
- `ExceedMaxItersEvent` — 达到最大迭代次数
- `RequestStopEvent` — 中间件或工具发起的提前停止

**文本流式事件**：`TextBlockStartEvent` → `TextBlockDeltaEvent`（getDelta()） → `TextBlockEndEvent`

**思考流式事件**：`ThinkingBlockStartEvent` / `ThinkingBlockDeltaEvent` / `ThinkingBlockEndEvent`

**数据流式事件**：`DataBlockStartEvent`（getMediaType()） → `DataBlockDeltaEvent`（getData()） → `DataBlockEndEvent`

**工具调用流式事件**：`ToolCallStartEvent`（getToolCallId、getToolCallName） → `ToolCallDeltaEvent`（getDelta()） → `ToolCallEndEvent`

**工具结果流式事件**：`ToolResultStartEvent` → `ToolResultTextDeltaEvent` → `ToolResultDataDeltaEvent` → `ToolResultEndEvent`（getState: SUCCESS/ERROR）

**HITL事件**：
- `RequireUserConfirmEvent` — 需要用户确认工具调用
- `RequireExternalExecutionEvent` — 需要外部执行工具
- `AllToolsDeniedEvent` — 全部工具被拒绝

### 3.4 Tool（工具系统）

三种概念层级：
- **Tool** — 实现 AgentTool 接口（通常通过 ToolBase 或 @Tool 注解）
- **Toolkit** — 容器，注册 tool/MCP/skill，暴露 JSON schema，分发调用
- **Tool Group** — 命名的 tool/MCP/skill 集合，可通过 meta tool 切换

**① 注解式 @Tool（最轻量）**：

```java
public class SimpleTools {
    @Tool(name = "get_current_time", description = "Returns the current time.",
          readOnly = true, concurrencySafe = true)
    public String getCurrentTime(
        @ToolParam(name = "timezone", description = "IANA timezone, e.g. Asia/Shanghai")
        String timezone) {
        return LocalDateTime.now(ZoneId.of(timezone))
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}

Toolkit toolkit = new Toolkit();
toolkit.registerTool(new SimpleTools());
```

**@Tool 常用属性**：

| 属性 | 说明 |
|------|------|
| `name` | tool名（默认方法名） |
| `description` | 面向agent的描述 |
| `readOnly` | 是否只读 |
| `concurrencySafe` | 是否可并发 |
| `stateInjected` | 是否注入AgentState |
| `dangerousFiles/dangerousDirectories` | 追加危险路径 |
| `converter` | 自定义返回值转换器 |

**② 继承 ToolBase（需自定义权限/外部执行）**：

```java
public class WebSearchTool extends ToolBase {
    public WebSearchTool() {
        super(ToolBase.builder()
            .name("WebSearch").description("Search the web.")
            .inputSchema(Map.of("type", "object", ...))
            .readOnly(true).concurrencySafe(true));
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> toolInput, ToolExecutionContext context) {
        return Mono.just(PermissionDecision.allow("Web search is read-only."));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = (String) param.getInput().get("query");
        return doSearchAsync(query)
            .map(text -> ToolResultBlock.builder()
                .id(param.getId()).name(getName())
                .output(List.of(TextBlock.builder().text(text).build())).build());
    }
}
```

**③ 外部执行 Tool**：

设置 `externalTool(true)`，不必实现 `callAsync`。调用时发 `RequireExternalExecutionEvent` 并暂停。
适用于 Human-in-the-loop 工作流。

**④ MCP 集成**：

```java
// STDIO
McpClientWrapper fs = McpClientBuilder.stdio()
    .name("filesystem").command("mcp-server-filesystem").args(...).build();

// Streamable HTTP
McpClientWrapper weather = McpClientBuilder.streamableHttp()
    .name("weather").url("https://...").build();

// SSE
McpClientWrapper search = McpClientBuilder.sse()
    .name("search").url("https://...").build();

toolkit.registerMcpClient(client).block();
```

**接收 Context**：
@Tool 方法中，无 `@ToolParam` 的参数被框架自动注入：

| 参数类型 | 注入来源 |
|------|------|
| `Agent` | 当前agent实例 |
| `AgentState` | per-session状态 |
| `RuntimeContext` | per-call上下文 |
| `ToolExecutionContext` | 兼容层（已deprecated） |
| 自定义POJO | `runtimeContext.get(ParamType.class)` |

**内置 Tool**：`TodoTools.todoWrite` — 维护结构化任务列表

### 3.5 Context & AgentState（上下文与状态）

**核心设计**：Agent 实例是无状态引擎（只持有不可变配置），所有 per-session 状态存在 `AgentState` 中，以 `(userId, sessionId)` 为索引。

```
HarnessAgent (单例)
  不可变配置: sysPrompt, model, toolkit, middlewares

  state cache:
    ("alice","s1") → AgentState  ← call(…, RC(alice,s1))
    ("bob","s2")   → AgentState  ← call(…, RC(bob,s2))

  per-session 门: 同 (uid,sid) 串行, 不同 (uid,sid) 并行
```

**这意味着什么**：
- 不需要 agent-per-user 注册表
- 不同 (userId, sessionId) 完全并行，相同自动串行
- 状态完全内部化（call入口加载，退出保存）
- per-call 隔离（使用各自 AgentState 快照）

**AgentState 字段：**

| 字段 | 内容 |
|------|------|
| `getSessionId()` | 会话标识 |
| `getUserId()` | 用户标识 |
| `getContext() / contextMutable()` | 对话历史 |
| `getSummary()` | 压缩摘要 |
| `getPermissionContext()` | 工具权限规则 |
| `getPlanModeContext()` | Plan Mode 状态 |
| `getTasksContext()` | todo_write 任务清单 |
| `getToolContext()` | 工具组激活状态 |

**自动持久化链路**：

```
call(msgs, RuntimeContext(userId, sessionId))
  │
  ├─ per-session 门: 相同 (uid,sid) 串行
  ▼
  从缓存或 stateStore 加载 AgentState
  │  注入到 RuntimeContext: rc.setAgentState(state)
  ▼
  推理循环（中间件改写 state.contextMutable()）
  ▼
  保存 AgentState（stateStore.save(...)）
  ▼
  返回结果
```

只在 call 结束/shutdown 时整体写入，吞吐压力很低。

**AgentStateStore 实现：**

| 实现 | 模块 | 适用场景 |
|------|------|------|
| `InMemoryAgentStateStore` | agentscope-core | 单元测试/单进程demo |
| `JsonFileAgentStateStore` | agentscope-core | 单机开发，JSON落盘 |
| `RedisAgentStateStore` | agentscope-extensions-redis | 多副本生产，跨进程共享 |
| `MysqlAgentStateStore` | agentscope-extensions-mysql | 关系型库（审计/报表） |

**跨进程实时恢复**：只要状态存储是分布式的（如Redis），不同JVM/节点的agent实例能自动恢复对话。支撑故障转移、滚动发布、跨场景接续。

**RuntimeContext**（per-call 元数据袋）：

三类槽位：
| 槽位 | 设置方式 | 读取方式 |
|------|------|------|
| 会话字段 | `sessionId(String)` / `userId(String)` | `getSessionId()` / `getUserId()` |
| 字符串属性 | `put(String key, Object value)` | `get(String key)` |
| 类型化属性 | `put(Class<T> type, T value)` | `get(Class<T> type)` |

```java
RuntimeContext ctx = RuntimeContext.builder()
    .userId("alice").sessionId("s-001")
    .put("request_id", "req-123")           // 字符串属性
    .put(UserContext.class, new UserContext("alice", "en"))  // 类型化属性
    .build();
```

类型化属性给 tool 用（@Tool 方法声明同类型参数自动注入）；字符串属性用于内部协调。自由/类型属性不会持久化。

**Per-session 中断**：每份 AgentState 携带瞬态的 `InterruptControl`（不会被序列化），支持精确中断单个session。

### 3.6 Middleware（中间件）

在 5 个位置上设置 hook，覆盖全链路：

| 位置 | 类型 | 说明 |
|------|------|------|
| `onAgent` | Onion | 包裹完整 reply 流程 |
| `onReasoning` | Onion | 包裹单轮推理（输入组装→模型调用→流式解码） |
| `onActing` | Onion | 包裹单次工具调用执行 |
| `onModelCall` | Onion | 包裹底层 ChatModel API 调用 |
| `onSystemPrompt` | Transformer | 组装 system prompt 时串行接力 |

```
onAgent/
└── ReAct loop（每一轮）/
    ├── onReasoning/
    │   ├── onSystemPrompt（组装 system prompt）
    │   └── onModelCall（模型 API 调用）
    └── onActing（每次工具调用）
```

**两种类型**：
- **Onion**（洋葱式）：`mw1前 → mw2前 → 内层 → mw2后 → mw1后`
- **Transformer**（变换式）：`原始prompt → mw1 → mw2 → 最终prompt`

**装备 Middleware**：

```java
ReActAgent agent = ReActAgent.builder()
    .name("assistant").sysPrompt("...").model(model).toolkit(toolkit)
    .middlewares(List.of(new OtelTracingMiddleware()))
    .build();
```

**内置 Middleware**：
- `OtelTracingMiddleware` — OpenTelemetry 追踪（invoke_agent / chat / execute_tool 三层 span）
- `TaskReminderMiddleware` — 配合 TodoTools，每轮推理前注入任务提醒

**自定义 Middleware**：

```java
public class TimingMiddleware implements MiddlewareBase {
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        long start = System.nanoTime();
        return next.apply(input)
            .doFinally(sig -> System.out.println(
                "[timing] " + agent.getName() + ": " + (System.nanoTime()-start)/1_000_000 + "ms"));
    }
}
```

**每个 hook 的 Input record**：

| Hook | Input record | 字段 |
|------|------|------|
| `onAgent` | `AgentInput` | `msgs: List<Msg>` |
| `onReasoning` | `ReasoningInput` | `messages, tools, options` |
| `onActing` | `ActingInput` | `toolCalls: List<ToolUseBlock>` |
| `onModelCall` | `ModelCallInput` | `messages, tools, options, model` |
| `onSystemPrompt` | `String` | 当前 prompt |

**RuntimeContext 读取**：所有 hook 都将 RuntimeContext 作为第二个参数传入，可读可写。同一份 RuntimeContext 在 reply 内被各层共享。

**实用示例**：计时 middleware、限速 middleware、动态 system prompt middleware、模型回退 middleware、全部工具被拒绝时停止 agent middleware。

### 3.7 Permission System（权限系统）

拦截每次工具调用，给出三态决策：**ALLOW**（允许）/ **DENY**（拒绝）/ **ASK**（询问用户）

**三个组件**：

1. **Rules** — 显式 allow/deny/ask 模式（最高优先级）。来源：静态预配置 或 ASK 提示中用户接受建议规则
2. **Mode** — 全局静态策略，决定未命中规则的调用的默认行为
3. **Built-in Checks** — Tool 自身的运行时检查（不可绕过）

**决策流程**：Deny Rules? → Ask Rules? → Tool-Specific Checks → Allow Rules? → 根据 Mode 最终决策

**五种 PermissionMode**：

| Mode | 行为 | 适用场景 |
|------|------|------|
| `DEFAULT` | 所有操作需显式规则或用户确认 | 最安全，推荐默认 |
| `ACCEPT_EDITS` | 自动放行工作目录内文件操作 | 用户在场的活跃开发 |
| `EXPLORE` | 只读：放行读、拒绝所有写与命令 | 代码探索、规划 |
| `BYPASS` | 放行一切（deny/ask 规则仍生效） | 完全可信的沙箱 |
| `DONT_ASK` | 所有 ASK → DENY | 无人值守/计划任务 |

**PermissionRule**：
- `toolName` — 适用 tool 名
- `ruleContent` — 匹配模式（null 表示对该 tool 的所有调用均匹配）
- `behavior` — ALLOW/DENY/ASK/PASSTHROUGH
- `source` — "userSettings"/"projectSettings"/"session"/"suggested"

**配置示例**：

```java
PermissionContextState permCtx = PermissionContextState.builder()
    .mode(PermissionMode.DEFAULT)
    .addAllowRule("safe_read",
        new PermissionRule("safe_read", null, PermissionBehavior.ALLOW, "userSettings"))
    .addAskRule("dangerous_delete",
        new PermissionRule("dangerous_delete", null, PermissionBehavior.ASK, "userSettings"))
    .addDenyRule("drop_table",
        new PermissionRule("drop_table", null, PermissionBehavior.DENY, "userSettings"))
    .build();
```

**Built-in Checks**：每个 Tool 的 `checkPermissions()` 方法返回四种决策之一：`allow(message)` / `deny(message)` / `ask(message)` / `passthrough(message)`。PASSTHROUGH 表示交给引擎按 rules/mode 评估。

**危险路径保护**：ToolBase 内置危险路径列表（.bashrc、.gitconfig、.ssh、.env、.git/、.kube/ 等），命中后即使在 BYPASS 模式也强制 ASK。可通过 @Tool 的 `dangerousFiles`/`dangerousDirectories` 追加。

**HITL 交互流程**：
1. 配置 ASK 规则
2. Agent 遇到 ASK 时暂停，返回 `GenerateReason.PERMISSION_ASKING`
3. 从返回的 Msg 中提取 `ToolUseBlock`（状态为 ASKING）
4. 构建 `ConfirmResult`，附在新消息的 metadata（`Msg.METADATA_CONFIRM_RESULTS`）中恢复 agent

**Blocking vs Streaming**：
- Blocking call()：从返回 Msg 的 getContent() 筛选 ToolUseBlock（状态 ASKING）
- Streaming streamEvents()：从 RequireUserConfirmEvent.getToolCalls() 直接获取
- 恢复方式相同：构建 ConfirmResult 附在 metadata 中发起新 call

---

## 四、高级特性

### 4.1 Plan Mode（计划模式）

通过四个内置工具让智能体维护结构化任务清单：

| Tool | 操作 |
|------|------|
| `TaskCreate` | 追加新任务 |
| `TaskGet` | 按ID获取任务详情 |
| `TaskList` | 列出所有任务及状态 |
| `TaskUpdate` | 更新状态/字段/依赖 |

**状态流转**：`pending → in_progress → completed`（或任意状态→deleted）

**依赖表达**：`blocks` / `blocked_by` 双向边，TaskUpdate 自动维护一致性。

### 4.2 RAG（检索增强生成）

**模块化架构**：Parser → Chunker → Embedding Model → Vector Store → KnowledgeBase

**默认实现**：

| 模块 | 实现 |
|------|------|
| Parser | `TextParser` / `PDFParser` / `PPTParser` / `ImageParser` |
| Chunker | `ApproxTokenChunker` |
| Vector Store | `QdrantStore` / `MilvusLiteStore` |

**索引流程**：文件解析（Section数组） → 切块（Chunk数组） → 嵌入入库（KnowledgeBase.insert_document）

**KnowledgeBase 接口**：

| 方法 | 说明 |
|------|------|
| `insert_document(chunks, document_id, metadata)` | 批量嵌入并写入 |
| `search(queries, top_k, score_threshold)` | 向量检索，自动去重排序 |
| `delete_document(document_id)` | 按 document_id 删除 |
| `list_documents()` | 返回文档摘要列表 |

**集成方式**：通过 `RAGMiddleware` 接入，两种模式：

| 模式 | 触发时机 | 注入方式 |
|------|------|------|
| `static` | 每次reply首次推理前 | 检索结果包装为 HintBlock 注入上下文 |
| `agentic`（默认） | 模型自主调用 search_knowledge 工具 | 暴露 search_knowledge 工具 |

两种模式可叠加使用。agentic 模式需手动 `Toolkit(tools=await rag_mw.list_tools())`。

**多租户隔离**：`metadata_filter` 实现深度防御——search/list 强制过滤，insert 强制覆盖字段。

### 4.3 Long-term Memory（长期记忆）

基于中间件实现，跨会话保留信息。

**两种实现**：

| 实现 | 描述 |
|------|------|
| `AgenticMemoryMiddleware` | 基于 Markdown 文件的原生实现 |
| `Mem0Middleware` | 由 mem0 驱动的开箱即用后端 |

**Agentic Memory**：
- 文件结构：`MEMORY.md`（索引）+ 主题 `.md` 文件
- 每个 Markdown 文件遵循 frontmatter 规范（name、description、type）
- 两种检索：智能体自主检索 + 异步 LLM 选择相关文件
- 支持 Local / Docker / E2B 后端

**Mem0**：
- 三种模式：`static_control`（自动注入）/ `agent_control`（工具调用）/ `both`
- 三种构造：AgentScope模型驱动 / 模型+自定义config / 预构建client
- Agent 与 Mem0Middleware 必须使用不同的模型实例

### 4.4 Workspace（工作区）

智能体的执行环境，提供工具、skill 与上下文 offload。

**四种后端**：

| 实现 | 后端 |
|------|------|
| `LocalWorkspace` | 宿主文件系统 |
| `DockerWorkspace` | Docker 容器 |
| `E2BWorkspace` | E2B 云沙箱 |
| `OpenSandboxWorkspace` | OpenSandbox 沙箱 |

**统一目录结构**：

```
{workdir}/
├── .mcp           # 注册的 MCP client 配置
├── data/          # offload 的多模态负载
├── skills/        # skill 子目录，每个含 SKILL.md
│   └── .skills    # 名称/哈希索引
└── sessions/      # 每个 session 的 context.jsonl 与工具结果文件
```

**MCP Gateway**：Docker/E2B/OpenSandbox 内部运行轻量 FastAPI 进程，持有上游 MCP 会话，通过单一 HTTP 端点（Bearer token鉴权）对宿主暴露。宿主侧 `GatewayMCPClient` 把请求作为 curl 命令在沙箱内执行，避免对外开放端口。

**Workspace Manager**：TTL 缓存 + 隔离策略。内置四种 manager（按 agent 隔离），可继承 `WorkspaceManagerBase` 改写为按 user/session 隔离。

---

## 五、部署

### 5.1 Agent Service（智能体服务）

基于 FastAPI 的多租户、多会话 HTTP 服务。

**核心能力**：

| 能力 | 说明 |
|------|------|
| Agent Team | Leader 派生 worker 并协调 |
| Workspace 管理 | 可配置隔离粒度（per_agent/per_session/per_user） |
| RAG 知识库 | 文档摄取、切片、embedding、检索 |
| 后台任务卸载 | 长耗时工具切到后台执行 |
| Cron 调度 | 按时间触发智能体执行 |
| 会话回放 | 后接入客户端重放缓冲历史 |
| 中断 | POST /sessions/{id}/interrupt |
| 协议适配 | 中间件转换为 AG-UI、A2A 等协议 |

**资源模型**：User → Credential / Agent / Schedule → Session → Workspace / Messages

**核心 API**：

| 端点 | 说明 |
|------|------|
| `POST /chat` | 触发 chat 运行 |
| `GET /sessions/{id}/stream` | SSE 事件流 |
| `POST /sessions/{id}/interrupt` | 中断运行 |
| `GET /sessions/{id}/status` | 会话状态 |
| `GET/POST/PATCH/DELETE /agent` | 智能体 CRUD |
| `GET/POST/PATCH/DELETE /credential` | 凭证 CRUD |
| `GET/POST/PATCH/DELETE /sessions` | 会话 CRUD |
| `GET/POST/PATCH/DELETE /schedule` | 调度 CRUD |
| `POST /workspace/mcp` / `POST /workspace/skill` | 工作区扩展 |
| `GET/POST/PATCH/DELETE /knowledge_bases` | 知识库 CRUD |

**create_app 三要素**：`storage`（RedisStorage）+ `message_bus`（RedisMessageBus）+ `workspace_manager`

**自定义扩展点**：用户鉴权（覆盖 get_current_user_id）、工作区隔离策略、API凭证类型（CredentialBase子类）、存储后端、协议适配、Agent中间件/Tool工厂、子智能体模板。

**三个内置Agent中间件**：
- `InboxMiddleware` — hint 注入，清空收件箱
- `ToolOffloadMiddleware` — 工具调用超时后转入后台
- `StateChangeMiddleware` — 状态变更时发出 CustomEvent

### 5.2 Agent Team（智能体团队）

Leader 会话通过内置工具派生并协调 worker。

| 工具 | 用途 |
|------|------|
| `TeamCreate` | 创建团队 |
| `AgentCreate` | 派生 worker（名称、角色、首个任务） |
| `TeamSay` | 成员间消息（或广播） |
| `TeamDelete` | 解散团队 |
| `AgentInvite` | 邀请已有智能体 |

**协调模型**：基于消息总线（Redis）— leader 和 worker 可在不同进程/节点，通过 inbox + wakeup 原语通信。Worker 在自己的会话中并发运行。

**自定义子智能体模板** `SubAgentTemplate`：

```python
SubAgentTemplate(
    type="explorer",
    description="只读探索智能体",
    system_prompt_template="你是 {member_name}，团队 '{team_name}' 的 explorer...",
    permission_context=PermissionContext(mode=PermissionMode.EXPLORE),
)
```

占位符：`{team_name}` / `{team_description}` / `{member_name}` / `{member_description}` / `{leader_name}`

---

## 六、学习路径建议

1. **入门**：Quickstart → Agent 基础（call/streamEvents）→ Model 字符串配置 → Message/Event 体系
2. **核心**：Tool 注解式开发 → Context 与状态持久化 → RuntimeContext 使用
3. **进阶**：Permission System → Middleware 自定义 → 结构化输出 → HITL 交互
4. **高级**：Plan Mode → RAG 集成 → Long-term Memory → Workspace → MCP 集成
5. **生产**：Agent Service 部署 → Agent Team 编排 → 自定义扩展（鉴权/存储/凭证/协议）

---

## 附录：文档来源

| 文档 | URL |
|------|-----|
| 概览 | https://java.agentscope.io/v2/zh/intro.html |
| Quickstart | https://java.agentscope.io/v2/zh/docs/quickstart.html |
| Agent | https://java.agentscope.io/v2/zh/docs/building-blocks/agent.html |
| Model | https://java.agentscope.io/v2/zh/docs/building-blocks/model.html |
| Message & Event | https://java.agentscope.io/v2/zh/docs/building-blocks/message-and-event.html |
| Tool | https://java.agentscope.io/v2/zh/docs/building-blocks/tool.html |
| Context | https://java.agentscope.io/v2/zh/docs/building-blocks/context.html |
| Middleware | https://java.agentscope.io/v2/zh/docs/building-blocks/middleware.html |
| Permission System | https://java.agentscope.io/v2/zh/docs/building-blocks/permission-system.html |
| Plan | GitHub raw: agentscope-ai/docs |
| Workspace | GitHub raw: agentscope-ai/docs |
| RAG | GitHub raw: agentscope-ai/docs |
| Long-term Memory | GitHub raw: agentscope-ai/docs |
| Agent Service | GitHub raw: agentscope-ai/docs |
| Agent Team | GitHub raw: agentscope-ai/docs |
