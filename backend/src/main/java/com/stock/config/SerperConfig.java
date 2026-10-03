package com.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Serper.dev（Google 搜索 API）配置：系统默认优先的外部数据获取通道。
 * application.yml 前缀 serper.*，api-key 支持环境变量 SERPER_API_KEY 覆盖。
 */
@Component
@ConfigurationProperties(prefix = "serper")
public class SerperConfig {

    private String apiKey;
    private String baseUrl = "https://google.serper.dev";
    /** 单次搜索返回条数（Serper num 参数） */
    private int num = 10;
    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 5000;
    /** 读超时（毫秒） */
    private int readTimeoutMs = 10000;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getNum() { return num; }
    public void setNum(int num) { this.num = num; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    /** api-key 配置非空才视为可用 */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }
}
