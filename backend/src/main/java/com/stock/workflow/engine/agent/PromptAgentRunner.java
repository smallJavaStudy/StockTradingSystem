package com.stock.workflow.engine.agent;

import com.stock.config.AgentModelConfig;
import com.stock.workflow.entity.AgentDef;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * PROMPT 型智能体执行器：一次性非流式 LLM 调用。
 * <p>
 * 调用方式严格参照现有 {@code DataEnricher.callAgent} / {@code AgentFactory.createTempAgent}
 * 的已验证范式：非流式 OpenAIChatModel + HarnessAgent.call(...).block()。
 * 模型三组配置（deepseek-v4 / deepseek-v5 / kimi）从 application.yml 的 agent.model.* 读取。
 */
@Component
public class PromptAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptAgentRunner.class);
    private static final int DEFAULT_MAX_TOKENS = 2560;
    private static final String DEFAULT_SYS_PROMPT = "你是一个专业、严谨的智能助手，请直接完成用户交给你的任务。";

    private final AgentModelConfig modelConfig;

    public PromptAgentRunner(AgentModelConfig modelConfig) {
        this.modelConfig = modelConfig;
    }

    /**
     * 执行一次性 LLM 调用。
     *
     * @param agentDef       智能体定义（systemPrompt / modelChoice / temperature / maxTokens）
     * @param input          插值后的完整输入
     * @param timeoutSeconds 超时（秒），超时抛出异常
     * @return 模型输出文本
     */
    public String run(AgentDef agentDef, String input, int timeoutSeconds) {
        OpenAIChatModel model = buildNonStreamingModel(agentDef);
        String sysPrompt = agentDef.getSystemPrompt() != null && !agentDef.getSystemPrompt().isBlank()
                ? agentDef.getSystemPrompt() : DEFAULT_SYS_PROMPT;

        // PROMPT 型为一次性纯 LLM 调用：必须禁用 Harness 内置工具组（filesystem/shell/memory），
        // 否则模型会进入多轮工具探索，最终 turn 的文本可能只剩工具规划前言而非任务产出。
        HarnessAgent agent = HarnessAgent.builder()
                .name("wf-prompt-agent-" + agentDef.getId())
                .sysPrompt(sysPrompt)
                .model(model)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .build();

        log.info("PromptAgentRunner: agent={}, model={}, timeout={}s, inputLen={}",
                agentDef.getName(), agentDef.getModelChoice(), timeoutSeconds, input.length());

        Msg result;
        try {
            result = agent.call(List.of(new UserMessage(input)))
                    .block(Duration.ofSeconds(timeoutSeconds));
        } catch (RuntimeException e) {
            // Reactor block 超时抛 IllegalStateException("Timeout on blocking read ...")
            if (e.getMessage() != null && e.getMessage().contains("Timeout")) {
                throw new IllegalStateException(
                        "PROMPT 节点执行超时（" + timeoutSeconds + "秒）: " + agentDef.getName(), e);
            }
            throw e;
        }

        if (result == null || result.getTextContent() == null || result.getTextContent().isBlank()) {
            throw new IllegalStateException("PROMPT 节点 [" + agentDef.getName() + "] 模型返回为空");
        }
        return result.getTextContent().trim();
    }

    private OpenAIChatModel buildNonStreamingModel(AgentDef agentDef) {
        AgentModelConfig.ModelProps props = resolveProps(agentDef.getModelChoice());

        GenerateOptions.Builder options = GenerateOptions.builder()
                .maxTokens(agentDef.getMaxTokens() != null && agentDef.getMaxTokens() > 0
                        ? agentDef.getMaxTokens() : DEFAULT_MAX_TOKENS);
        if (agentDef.getTemperature() != null) {
            options.temperature(agentDef.getTemperature());
        }

        return OpenAIChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .baseUrl(props.getBaseUrl())
                .generateOptions(options.build())
                .stream(false)
                .build();
    }

    /** 兼容 "deepseek-v4" / "DEEPSEEK_V4" 两种写法 */
    AgentModelConfig.ModelProps resolveProps(String modelChoice) {
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
