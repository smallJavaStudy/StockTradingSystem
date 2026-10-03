package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.model")
public class AgentModelConfig {
    private ModelProps deepseekV4 = new ModelProps();
    private ModelProps deepseekV5 = new ModelProps();
    private ModelProps kimi = new ModelProps();

    public ModelProps getDeepseekV4() { return deepseekV4; }
    public void setDeepseekV4(ModelProps v) { this.deepseekV4 = v; }
    public ModelProps getDeepseekV5() { return deepseekV5; }
    public void setDeepseekV5(ModelProps v) { this.deepseekV5 = v; }
    public ModelProps getKimi() { return kimi; }
    public void setKimi(ModelProps v) { this.kimi = v; }

    public static class ModelProps {
        private String apiKey;
        private String baseUrl;
        private String modelName;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }
}
