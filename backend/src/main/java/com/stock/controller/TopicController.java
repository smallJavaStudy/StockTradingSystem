package com.stock.controller;

import com.stock.service.TopicService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 题材库 API：当日涨停池行业分布 + LLM 热点题材分析。
 */
@RestController
@RequestMapping("/api/topics")
public class TopicController {

    private final TopicService topicService;

    public TopicController(TopicService topicService) {
        this.topicService = topicService;
    }

    /** 今日热点题材（行业分布实时计算，LLM 分析复用 DataEnricher 6h 缓存） */
    @GetMapping
    public Map<String, Object> topics() {
        return topicService.getTopics();
    }
}
