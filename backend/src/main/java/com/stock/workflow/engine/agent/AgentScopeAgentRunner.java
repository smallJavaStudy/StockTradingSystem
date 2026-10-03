package com.stock.workflow.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.config.AgentModelConfig;
import com.stock.workflow.engine.tools.DataEnrichTool;
import com.stock.workflow.engine.tools.MarketLhbTool;
import com.stock.workflow.engine.tools.MarketZtTool;
import com.stock.workflow.engine.tools.StockFinanceTool;
import com.stock.workflow.engine.tools.StockFundFlowTool;
import com.stock.workflow.engine.tools.StockIndustryTool;
import com.stock.workflow.engine.tools.StockKlineTool;
import com.stock.workflow.engine.tools.WebSearchTool;
import com.stock.workflow.entity.AgentDef;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * AGENTSCOPE 型智能体执行器：多轮推理 + 工具调用（AgentScope Harness 2.0-RC2）。
 * <p>
 * 严格参照现有 {@code AgentFactory} / {@code AgentOrchestrationService} 中已验证的 API 用法：
 * 流式 OpenAIChatModel + HarnessAgent.builder() + streamEvents(...) 事件流。
 * <ul>
 *   <li><b>toolsJson</b>：JSON 字符串数组。支持两类工具：
 *       ① Harness 内置工具组（filesystem / shell / memory，HarnessAgent 构建时自动装配，
 *       未列出的通过 builder 的 disableXxx() 关闭）；
 *       ② 自定义股票数据工具（stock_kline / stock_finance / stock_fundflow / stock_industry /
 *       data_enrich，方法上标 @Tool 注解的 Spring Bean，通过 Toolkit.registerTool 选择性注册，
 *       2.0.0-RC2 已验证可行，参照 agent-scope 模块 WebSearchTool 用法）。
 *       未知工具名记警告并忽略。</li>
 *   <li><b>maxIterations</b>：映射到 HarnessAgent.Builder#maxIters(int)（与
 *       HarnessAgent#getMaxIters() 对应的框架原生迭代上限）。</li>
 *   <li><b>loopDepth</b>：2.0-RC2 框架无“循环嵌套深度”直接对应 API（仅有 maxIters），
 *       故作为提示词约束注入 sysPrompt。</li>
 *   <li><b>eventsJson</b>：JSON 字符串数组，事件订阅白名单，决定 streamEvents 中哪些事件
 *       转发为 SSE NODE_DELTA / 日志。支持的取值（对应 Harness 实际存在的 AgentEventType）：
 *       <ul>
 *         <li>TEXT_DELTA（别名 TEXT_BLOCK_DELTA）：正文增量 → NODE_DELTA；</li>
 *         <li>THINKING_DELTA（别名 THINKING_BLOCK_DELTA）：思考增量 → NODE_DELTA；</li>
 *         <li>TOOL_CALL（别名 TOOL_CALL_START）：工具调用开始 → 日志 + NODE_DELTA 标记行；</li>
 *         <li>TOOL_RESULT（别名 TOOL_RESULT_END）：工具执行结束 → 日志 + NODE_DELTA 标记行。</li>
 *       </ul>
 *       未知取值记警告并忽略；空/null/解析后为空 = 默认行为（仅转发 TEXT 增量，向后兼容）。
 *       无论是否订阅，最终输出始终完整累积 TEXT 增量，订阅仅影响转发。</li>
 *   <li>TEXT_DELTA 事件通过 onDelta 回调转发（由 Delegate 接线到 WorkflowSseService 的 NODE_DELTA）。</li>
 * </ul>
 */
@Component
public class AgentScopeAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeAgentRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_TOKENS = 2560;
    private static final String DEFAULT_SYS_PROMPT = "你是一个专业、严谨的智能助手，请直接完成用户交给你的任务。";
    /** Harness 内置可用工具组名（现有可用工具集） */
    private static final Set<String> BUILTIN_TOOL_GROUPS = Set.of("filesystem", "shell", "memory");
    /** 自定义股票数据工具名（com.stock.workflow.engine.tools） */
    public static final Set<String> CUSTOM_STOCK_TOOLS = Set.of(
            "stock_kline", "stock_finance", "stock_fundflow", "stock_industry", "data_enrich",
            "market_zt", "market_lhb", "web_search");
    /** eventsJson 订阅事件归一化名 */
    private static final String EVENT_TEXT_DELTA = "TEXT_DELTA";
    private static final String EVENT_THINKING_DELTA = "THINKING_DELTA";
    private static final String EVENT_TOOL_CALL = "TOOL_CALL";
    private static final String EVENT_TOOL_RESULT = "TOOL_RESULT";

    private final AgentModelConfig modelConfig;
    /** 工具名 → 工具实例（Spring 注入，按 toolsJson 选择性注册到 Toolkit） */
    private final Map<String, Object> customToolInstances;

    public AgentScopeAgentRunner(AgentModelConfig modelConfig,
                                 StockKlineTool stockKlineTool,
                                 StockFinanceTool stockFinanceTool,
                                 StockFundFlowTool stockFundFlowTool,
                                 StockIndustryTool stockIndustryTool,
                                 DataEnrichTool dataEnrichTool,
                                 MarketZtTool marketZtTool,
                                 MarketLhbTool marketLhbTool,
                                 WebSearchTool webSearchTool) {
        this.modelConfig = modelConfig;
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("stock_kline", stockKlineTool);
        tools.put("stock_finance", stockFinanceTool);
        tools.put("stock_fundflow", stockFundFlowTool);
        tools.put("stock_industry", stockIndustryTool);
        tools.put("data_enrich", dataEnrichTool);
        tools.put("market_zt", marketZtTool);
        tools.put("market_lhb", marketLhbTool);
        tools.put("web_search", webSearchTool);
        this.customToolInstances = tools;
    }

    /**
     * 执行 AgentScope 智能体。
     *
     * @param agentDef       智能体定义
     * @param input          插值后的完整输入
     * @param timeoutSeconds 超时（秒），超时抛出异常
     * @param onDelta        TEXT_DELTA 回调（可为 null）
     * @return 最终输出全文
     */
    public String run(AgentDef agentDef, String input, int timeoutSeconds, Consumer<String> onDelta) {
        HarnessAgent agent = buildAgent(agentDef);
        // eventsJson 订阅集合；为空 = 默认行为（仅转发 TEXT 增量，向后兼容，种子数据不受影响）
        Set<String> subscribed = parseEvents(agentDef.getEventsJson());
        boolean defaultMode = subscribed.isEmpty();

        log.info("AgentScopeAgentRunner: agent={}, model={}, maxIters={}, timeout={}s, subscribedEvents={}",
                agentDef.getName(), agentDef.getModelChoice(), agentDef.getMaxIterations(), timeoutSeconds,
                defaultMode ? "(default)" : subscribed);

        StringBuilder fullText = new StringBuilder();
        try {
            agent.streamEvents(List.of(new UserMessage(input)))
                    .doOnNext(event -> {
                        switch (event.getType()) {
                            case TEXT_BLOCK_DELTA -> {
                                if (event instanceof TextBlockDeltaEvent t) {
                                    // 最终输出始终完整累积，订阅仅影响是否转发 NODE_DELTA
                                    fullText.append(t.getDelta());
                                    if (defaultMode || subscribed.contains(EVENT_TEXT_DELTA)) {
                                        forwardDelta(onDelta, t.getDelta());
                                    }
                                }
                            }
                            case THINKING_BLOCK_DELTA -> {
                                if (!defaultMode && subscribed.contains(EVENT_THINKING_DELTA)
                                        && event instanceof ThinkingBlockDeltaEvent t) {
                                    forwardDelta(onDelta, t.getDelta());
                                }
                            }
                            case TOOL_CALL_START -> {
                                if (!defaultMode && subscribed.contains(EVENT_TOOL_CALL)
                                        && event instanceof ToolCallStartEvent t) {
                                    log.info("AGENTSCOPE 节点 [{}] 工具调用开始: {}",
                                            agentDef.getName(), t.getToolCallName());
                                    forwardDelta(onDelta, "\n[工具调用] " + t.getToolCallName() + "\n");
                                }
                            }
                            case TOOL_RESULT_END -> {
                                if (!defaultMode && subscribed.contains(EVENT_TOOL_RESULT)
                                        && event instanceof ToolResultEndEvent t) {
                                    log.info("AGENTSCOPE 节点 [{}] 工具执行结束: state={}",
                                            agentDef.getName(), t.getState());
                                    forwardDelta(onDelta, "\n[工具结果] " + t.getState().name() + "\n");
                                }
                            }
                            default -> { /* 其他事件不在订阅语义范围内 */ }
                        }
                    })
                    .blockLast(Duration.ofSeconds(timeoutSeconds));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
                throw new IllegalStateException(
                        "AGENTSCOPE 节点执行超时（" + timeoutSeconds + "秒）: " + agentDef.getName(), e);
            }
            throw e;
        }

        String output = fullText.toString().trim();
        if (output.isEmpty()) {
            throw new IllegalStateException("AGENTSCOPE 节点 [" + agentDef.getName() + "] 输出为空");
        }
        return output;
    }

    /** 转发增量到 onDelta 回调，回调异常不影响执行主流程 */
    private void forwardDelta(Consumer<String> onDelta, String delta) {
        if (onDelta == null) {
            return;
        }
        try {
            onDelta.accept(delta);
        } catch (Exception e) {
            log.warn("NODE_DELTA 回调异常（不影响执行）: {}", e.getMessage());
        }
    }

    /**
     * 解析 eventsJson（JSON 字符串数组）为订阅事件归一化名集合。
     * 支持取值（不区分大小写，'-' 视为 '_'）：
     * TEXT_DELTA / TEXT_BLOCK_DELTA、THINKING_DELTA / THINKING_BLOCK_DELTA、
     * TOOL_CALL / TOOL_CALL_START、TOOL_RESULT / TOOL_RESULT_END。
     * 未知取值记警告并忽略；空/null/解析失败返回空集（= 默认行为）。
     */
    private Set<String> parseEvents(String eventsJson) {
        Set<String> events = new HashSet<>();
        if (eventsJson == null || eventsJson.isBlank()) {
            return events;
        }
        try {
            JsonNode arr = MAPPER.readTree(eventsJson);
            if (!arr.isArray()) {
                log.warn("eventsJson 不是 JSON 数组，已忽略（保持默认行为）: {}", eventsJson);
                return events;
            }
            for (JsonNode node : arr) {
                String name = node.asText().trim().toUpperCase().replace('-', '_');
                switch (name) {
                    case "TEXT_DELTA", "TEXT_BLOCK_DELTA" -> events.add(EVENT_TEXT_DELTA);
                    case "THINKING_DELTA", "THINKING_BLOCK_DELTA" -> events.add(EVENT_THINKING_DELTA);
                    case "TOOL_CALL", "TOOL_CALL_START" -> events.add(EVENT_TOOL_CALL);
                    case "TOOL_RESULT", "TOOL_RESULT_END" -> events.add(EVENT_TOOL_RESULT);
                    case "" -> { /* 空项忽略 */ }
                    default -> log.warn("eventsJson 中的事件 [{}] 不在支持集 "
                            + "[TEXT_DELTA, THINKING_DELTA, TOOL_CALL, TOOL_RESULT] 中，已忽略", name);
                }
            }
        } catch (Exception e) {
            log.warn("eventsJson 解析失败，已忽略（保持默认行为）: {} ({})", eventsJson, e.getMessage());
            events.clear();
        }
        return events;
    }

    // ────────── Agent 构建 ──────────

    private HarnessAgent buildAgent(AgentDef agentDef) {
        AgentModelConfig.ModelProps props = resolveProps(agentDef.getModelChoice());

        GenerateOptions.Builder options = GenerateOptions.builder()
                .maxTokens(agentDef.getMaxTokens() != null && agentDef.getMaxTokens() > 0
                        ? agentDef.getMaxTokens() : DEFAULT_MAX_TOKENS);
        if (agentDef.getTemperature() != null) {
            options.temperature(agentDef.getTemperature());
        }

        // 参照 AgentFactory.buildDeepSeekV4/buildKimiModel：流式模型
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .baseUrl(props.getBaseUrl())
                .generateOptions(options.build())
                .stream(true)
                .build();

        // loopDepth：框架无直接对应 API（HarnessAgent 仅提供 maxIters 迭代上限），
        // 作为提示词约束注入并在此注释说明。
        String sysPrompt = agentDef.getSystemPrompt() != null && !agentDef.getSystemPrompt().isBlank()
                ? agentDef.getSystemPrompt() : DEFAULT_SYS_PROMPT;
        if (agentDef.getLoopDepth() != null && agentDef.getLoopDepth() > 0) {
            sysPrompt = sysPrompt + "\n\n【执行约束】处理任务时，任何自我修正/重试/子任务分解的嵌套层级不得超过 "
                    + agentDef.getLoopDepth() + " 层；达到上限后必须基于当前信息直接给出最终结论。";
        }

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("wf-agentscope-" + agentDef.getId())
                .sysPrompt(sysPrompt)
                .model(model);

        // maxIterations → 框架原生迭代上限
        if (agentDef.getMaxIterations() != null && agentDef.getMaxIterations() > 0) {
            builder.maxIters(agentDef.getMaxIterations());
        }

        // toolsJson 白名单裁剪内置工具组
        Set<String> enabledTools = parseTools(agentDef.getToolsJson());
        if (!enabledTools.contains("filesystem")) {
            builder.disableFilesystemTools();
        }
        if (!enabledTools.contains("shell")) {
            builder.disableShellTool();
        }
        if (!enabledTools.contains("memory")) {
            builder.disableMemoryTools();
        }

        // 自定义股票数据工具：选择性 registerTool 到 Toolkit（内置工具组仍由 Harness 自动装配）
        Set<String> enabledCustom = new HashSet<>(enabledTools);
        enabledCustom.retainAll(CUSTOM_STOCK_TOOLS);
        if (!enabledCustom.isEmpty()) {
            Toolkit toolkit = new Toolkit();
            for (String toolName : enabledCustom) {
                try {
                    toolkit.registerTool(customToolInstances.get(toolName));
                    log.info("AGENTSCOPE 智能体 [{}] 注册自定义工具: {}", agentDef.getName(), toolName);
                } catch (Exception e) {
                    log.warn("自定义工具 [{}] 注册失败，已跳过: {}", toolName, e.getMessage());
                }
            }
            builder.toolkit(toolkit);
        }

        return builder.build();
    }

    /** 解析 toolsJson（JSON 字符串数组）：内置工具组 + 自定义股票工具，未知工具名记警告并忽略 */
    Set<String> parseTools(String toolsJson) {
        Set<String> tools = new HashSet<>();
        if (toolsJson == null || toolsJson.isBlank()) {
            return tools;
        }
        try {
            JsonNode arr = MAPPER.readTree(toolsJson);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    String name = node.asText().trim().toLowerCase();
                    if (BUILTIN_TOOL_GROUPS.contains(name) || CUSTOM_STOCK_TOOLS.contains(name)) {
                        tools.add(name);
                    } else if (!name.isEmpty()) {
                        log.warn("toolsJson 中的工具 [{}] 不在现有可用工具集 {}+{} 中，已忽略",
                                name, BUILTIN_TOOL_GROUPS, CUSTOM_STOCK_TOOLS);
                    }
                }
            } else {
                log.warn("toolsJson 不是 JSON 数组，已忽略: {}", toolsJson);
            }
        } catch (Exception e) {
            log.warn("toolsJson 解析失败，已忽略: {} ({})", toolsJson, e.getMessage());
        }
        return tools;
    }

    /** 兼容 "deepseek-v4" / "DEEPSEEK_V4" 两种写法 */
    private AgentModelConfig.ModelProps resolveProps(String modelChoice) {
        String key = modelChoice == null ? "" : modelChoice.trim().toLowerCase().replace('_', '-');
        return switch (key) {
            case "deepseek-v4" -> modelConfig.getDeepseekV4();
            case "deepseek-v5" -> modelConfig.getDeepseekV5();
            case "kimi" -> modelConfig.getKimi();
            default -> throw new IllegalArgumentException("未知的 modelChoice: " + modelChoice
                    + "（支持 deepseek-v4 / deepseek-v5 / kimi）");
        };
    }
}
