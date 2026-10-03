package com.stock.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * ProfileClient — Agent项目画像API客户端。
 *
 * 调用 Agent 项目 (8081端口) 的用户画像 API，
 * 获取 prompt 适配上下文，用于个性化投资分析智能体。
 *
 * 调用链路：
 *   AgentOrchestrationService → ProfileClient → Agent项目 ProfileController
 *
 * 容错策略：
 *   - 超时 3 秒，失败返回空上下文（降级为千人一面）
 *   - 画像不可用时不影响主流程
 */
@Component
public class ProfileClient {

    private static final Logger log = LoggerFactory.getLogger(ProfileClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String agentBaseUrl;

    public ProfileClient(@Value("${agent-os.base-url:http://localhost:8081}") String agentBaseUrl,
                          ObjectMapper objectMapper) {
        this.agentBaseUrl = agentBaseUrl;
        this.objectMapper = objectMapper;
        this.restTemplate = new RestTemplate();
        // 设置连接/读取超时，避免阻塞主流程
        this.restTemplate.setRequestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
            setConnectTimeout(3000);
            setReadTimeout(3000);
        }});
    }

    /**
     * 获取用户的 prompt context 文本。
     * 可直接追加到智能体的 system prompt 末尾。
     *
     * @param userId 用户 ID
     * @return prompt 注入文本，失败时返回空字符串
     */
    public String fetchPromptContext(String userId) {
        try {
            String url = agentBaseUrl + "/api/v1/profile/" + userId + "/prompt-context";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getBody() != null && response.getBody().containsKey("promptContext")) {
                String context = (String) response.getBody().get("promptContext");
                log.info("✅ 获取用户画像成功: userId={}, completeness={}",
                        userId, response.getBody().get("completeness"));
                return context;
            }
        } catch (Exception e) {
            log.warn("⚠️ 获取用户画像失败 (Agent服务可能未启动): userId={}, error={}",
                    userId, e.getMessage());
        }
        return "";
    }

    /**
     * 检查画像是否就绪（数据充足可启用个性化）。
     */
    public boolean isProfileReady(String userId) {
        try {
            String url = agentBaseUrl + "/api/v1/profile/" + userId + "/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getBody() != null && response.getBody().containsKey("ready")) {
                return (boolean) response.getBody().get("ready");
            }
        } catch (Exception e) {
            log.debug("画像健康检查失败: {}", e.getMessage());
        }
        return false;
    }
}
