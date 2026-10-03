package com.stock.agent;

import com.stock.config.AgentModelConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;

@Component
public class AgentFactory {

    private final AgentModelConfig config;

    public AgentFactory(AgentModelConfig config) {
        this.config = config;
    }

    /**
     * 创建分析 Agent（支持用户画像注入）。
     *
     * @param profileContext 用户画像适配文本，追加到 sysPrompt 末尾。为空则使用默认风格。
     */
    public HarnessAgent createAnalysisAgent(AnalysisDirection direction, ModelChoice modelChoice,
                                             String stockCode, String dataContext,
                                             String profileContext) {
        OpenAIChatModel model = buildModel(modelChoice);

        // 基础 sysPrompt + 用户画像适配
        String sysPrompt = direction.getSystemPrompt();
        if (profileContext != null && !profileContext.isBlank()) {
            sysPrompt = sysPrompt + "\n\n" + profileContext;
        }

        String userPrompt = String.format("""
            请分析以下股票：%s
            以下是数据库中已采集的结构化数据：

            %s

            请严格按照分析方向的要求输出JSON。""", stockCode, dataContext);

        return HarnessAgent.builder()
                .name(direction.getDirectionKey())
                .sysPrompt(sysPrompt)
                .model(model)
                .build();
    }

    /** 兼容旧调用（无画像注入） */
    public HarnessAgent createAnalysisAgent(AnalysisDirection direction, ModelChoice modelChoice,
                                             String stockCode, String dataContext) {
        return createAnalysisAgent(direction, modelChoice, stockCode, dataContext, null);
    }

    public HarnessAgent createResolverAgent() {
        OpenAIChatModel model = buildKimiModel();

        return HarnessAgent.builder()
                .name("stock-resolver")
                .sysPrompt(AnalysisDirection.RESOLVER.getSystemPrompt())
                .model(model)
                .build();
    }

    private OpenAIChatModel buildModel(ModelChoice choice) {
        return switch (choice) {
            case DEEPSEEK_V4 -> buildDeepSeekV4();
            case DEEPSEEK_V5 -> buildDeepSeekV5();
            case KIMI -> buildKimiModel();
        };
    }

    private OpenAIChatModel buildDeepSeekV4() {
        AgentModelConfig.ModelProps props = config.getDeepseekV4();
        return OpenAIChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .baseUrl(props.getBaseUrl())
                .stream(true)
                .build();
    }

    private OpenAIChatModel buildDeepSeekV5() {
        AgentModelConfig.ModelProps props = config.getDeepseekV5();
        return OpenAIChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .baseUrl(props.getBaseUrl())
                .stream(true)
                .build();
    }

    private OpenAIChatModel buildKimiModel() {
        AgentModelConfig.ModelProps props = config.getKimi();
        return OpenAIChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .baseUrl(props.getBaseUrl())
                .stream(true)
                .build();
    }

    /**
     * 创建临时 Agent（非流式，用于数据获取/增强）
     */
    public HarnessAgent createTempAgent(ModelChoice modelChoice, String systemPrompt, int maxTokens) {
        OpenAIChatModel model = buildModelNonStreaming(modelChoice, maxTokens);
        return HarnessAgent.builder()
                .name("data-enricher-" + modelChoice.name())
                .sysPrompt(systemPrompt)
                .model(model)
                .build();
    }

    private OpenAIChatModel buildModelNonStreaming(ModelChoice choice, int maxTokens) {
        return switch (choice) {
            case DEEPSEEK_V4 -> buildNonStreaming("deepseek-v4", maxTokens);
            case DEEPSEEK_V5 -> buildNonStreaming("deepseek-v5", maxTokens);
            case KIMI -> buildNonStreaming("kimi", maxTokens);
        };
    }

    private OpenAIChatModel buildNonStreaming(String modelKey, int maxTokens) {
        AgentModelConfig.ModelProps props = switch (modelKey) {
            case "deepseek-v4" -> config.getDeepseekV4();
            case "deepseek-v5" -> config.getDeepseekV5();
            case "kimi" -> config.getKimi();
            default -> throw new IllegalArgumentException("Unknown model: " + modelKey);
        };
        return OpenAIChatModel.builder()
                .apiKey(props.getApiKey())
                .modelName(props.getModelName())
                .baseUrl(props.getBaseUrl())
                .generateOptions(GenerateOptions.builder()
                        .maxTokens(maxTokens > 0 ? maxTokens : 2560)
                        .build())
                .stream(false)
                .build();
    }
}
