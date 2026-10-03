package com.stock.controller;

import com.stock.service.MarketSentimentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 市场情绪周期仪 API：情绪分快照 + LLM 周期定性。
 */
@RestController
@RequestMapping("/api/market/sentiment")
public class MarketSentimentController {

    private final MarketSentimentService sentimentService;

    public MarketSentimentController(MarketSentimentService sentimentService) {
        this.sentimentService = sentimentService;
    }

    /** 情绪分快照：最近 N 个交易日的 0-100 情绪分与指标明细 */
    @GetMapping
    public MarketSentimentService.SentimentSnapshot sentiment(
            @RequestParam(defaultValue = "10") int days) {
        return sentimentService.getSentiment(days);
    }

    /** 周期定性（冰点/启动/主升/退潮），LLM 生成、当日缓存，失败降级规则法 */
    @GetMapping("/interpret")
    public Map<String, Object> interpret() {
        return sentimentService.interpret();
    }
}
