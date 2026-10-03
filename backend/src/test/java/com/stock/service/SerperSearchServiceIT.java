package com.stock.service;

import com.stock.config.SerperConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serper.dev 真实联通性测试（依赖外网与有效 api-key，默认跳过）。
 * <p>手动运行：mvn test -Dtest=SerperSearchServiceIT -Dserper.it=true -Dserper.api-key=xxx
 */
@EnabledIfSystemProperty(named = "serper.it", matches = "true")
class SerperSearchServiceIT {

    private SerperSearchService newService() {
        SerperConfig config = new SerperConfig();
        config.setApiKey(System.getProperty("serper.api-key",
                System.getenv().getOrDefault("SERPER_API_KEY", "")));
        config.setNum(5);
        return new SerperSearchService(config);
    }

    @Test
    void searchReturnsFormattedText() {
        SerperSearchService svc = newService();
        assertTrue(svc.isAvailable(), "api-key 未配置");

        String result = svc.search("中文在线 300364 最新公告");
        System.out.println("=== /search 结果 ===\n" + result);
        assertNotNull(result, "search 应返回结果");
        assertTrue(result.contains("【Serper搜索】"));
        assertTrue(result.contains("搜索结果:"));
    }

    @Test
    void searchNewsReturnsFormattedText() {
        SerperSearchService svc = newService();
        assertTrue(svc.isAvailable(), "api-key 未配置");

        String result = svc.searchNews("中文在线 AI 应用");
        System.out.println("=== /news 结果 ===\n" + result);
        assertNotNull(result, "searchNews 应返回结果");
    }
}
