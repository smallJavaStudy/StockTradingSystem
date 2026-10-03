package com.stock.service;

import com.stock.agent.AnalysisDirection;
import com.stock.entity.StockZtPool;
import com.stock.repository.StockZtPoolRepository;
import com.stock.workflow.engine.WorkflowTextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 题材库服务：当日涨停池行业分布 + DataEnricher LLM 级联（DeepSeek→Kimi）挖掘热点题材。
 * <p>
 * 缓存复用 {@link DataEnricher} 的内存/DB 两级缓存（6 小时过期）：
 * 缓存 key 用 stockCode="MARKET"（恰好 6 字符，满足 enrichment_data.stock_code 列长限制）+
 * directionKey="TOPIC_yyyy-MM-dd"。LLM 级联全部失败时降级为仅行业分布。
 */
@Service
public class TopicService {

    private static final Logger log = LoggerFactory.getLogger(TopicService.class);

    static final String MARKET_CODE = "MARKET";
    static final int INDUSTRY_LIMIT = 15;
    static final int REP_STOCK_LIMIT = 5;

    private final StockZtPoolRepository ztPoolRepo;
    private final DataEnricher dataEnricher;

    public TopicService(StockZtPoolRepository ztPoolRepo, DataEnricher dataEnricher) {
        this.ztPoolRepo = ztPoolRepo;
        this.dataEnricher = dataEnricher;
    }

    /** 行业桶：涨停池行业分布 + 代表股 */
    public record IndustryBucket(String industry, int count, int maxLimitUpDays, List<String> topStocks) {
    }

    /** 今日热点题材：行业分布 + LLM 题材分析（缓存 6h，LLM 失败降级） */
    public Map<String, Object> getTopics() {
        Map<String, Object> resp = new LinkedHashMap<>();
        LocalDate tradeDate = ztPoolRepo.findMaxTradeDate().orElse(null);
        if (tradeDate == null) {
            resp.put("tradeDate", null);
            resp.put("industries", List.of());
            resp.put("llmAnalysis", "数据库中暂无涨停池数据，请先执行市场数据抓取");
            resp.put("source", "none");
            return resp;
        }
        List<IndustryBucket> industries = buildIndustryBuckets(tradeDate);
        resp.put("tradeDate", tradeDate);
        resp.put("industries", industries);

        String context = buildContext(tradeDate, industries);
        try {
            String enriched = dataEnricher.enrich(MARKET_CODE, "A股市场", context,
                    "TOPIC_" + tradeDate, topicSpec());
            resp.put("llmAnalysis", WorkflowTextUtils.stripNonBmp(extractLlmPart(enriched)));
            resp.put("source", "llm");
        } catch (Exception e) {
            log.warn("题材 LLM 级联查询失败，降级为仅行业分布: {}", e.getMessage());
            resp.put("llmAnalysis", "LLM 题材分析暂不可用（" + e.getMessage() + "），以下为当日涨停池行业分布。");
            resp.put("source", "fallback");
        }
        return resp;
    }

    /** 当日 TODAY 涨停池按行业聚合（家数降序，Top15），代表股按连板数降序取 5 只 */
    List<IndustryBucket> buildIndustryBuckets(LocalDate tradeDate) {
        List<StockZtPool> pool =
                ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(tradeDate, "TODAY");
        Map<String, List<StockZtPool>> byIndustry = new LinkedHashMap<>();
        for (StockZtPool z : pool) {
            String ind = z.getIndustry() == null || z.getIndustry().isBlank() ? "未知" : z.getIndustry();
            byIndustry.computeIfAbsent(ind, k -> new ArrayList<>()).add(z);
        }
        return byIndustry.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(INDUSTRY_LIMIT)
                .map(e -> {
                    int maxDays = e.getValue().stream()
                            .mapToInt(z -> z.getLimitUpDays() == null ? 1 : z.getLimitUpDays())
                            .max().orElse(1);
                    List<String> tops = e.getValue().stream()
                            .limit(REP_STOCK_LIMIT)
                            .map(z -> z.getName() + "(" + z.getCode() + ","
                                    + (z.getLimitUpDays() == null ? 1 : z.getLimitUpDays()) + "板"
                                    + (z.getZtStat() == null || z.getZtStat().isBlank()
                                            ? "" : ",统计" + z.getZtStat()) + ")")
                            .collect(Collectors.toList());
                    return new IndustryBucket(e.getKey(), e.getValue().size(), maxDays, tops);
                })
                .collect(Collectors.toList());
    }

    private String buildContext(LocalDate tradeDate, List<IndustryBucket> industries) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(tradeDate).append(" 涨停池行业分布（家数降序）】\n");
        sb.append("行业 | 涨停家数 | 最高连板 | 代表股\n");
        for (IndustryBucket b : industries) {
            sb.append(b.industry()).append(" | ").append(b.count()).append(" | ")
              .append(b.maxLimitUpDays()).append("板 | ")
              .append(String.join("、", b.topStocks())).append("\n");
        }
        return sb.toString();
    }

    private AnalysisDirection.EnrichmentSpec topicSpec() {
        return new AnalysisDirection.EnrichmentSpec(
                "你是A股热点题材分析专家，擅长从涨停池行业分布中识别市场主线题材、梳理题材逻辑与关联个股。",
                "以下是{name}今日涨停池行业分布数据：\n{context}\n\n"
                        + "请基于上述真实盘面数据并结合你掌握的公开市场知识，输出今日A股热点题材分析：\n"
                        + "1. 识别 3-6 个热点题材（题材名+驱动逻辑+持续性判断）\n"
                        + "2. 每个题材列出关联个股（优先引用上面涨停池中的代表股，注明连板高度）\n"
                        + "3. 指出题材间的强弱排序与主线判断\n"
                        + "输出用 Markdown 分节，总字数 600-1000 字。",
                "你是A股热点题材分析专家。请根据提供的涨停池行业分布数据，补充今日A股热点题材、"
                        + "驱动逻辑与关联个股分析。",
                2048, 4000);
    }

    /** 剥离 enriched 文本中的数据库上下文段，仅保留 LLM 分析部分（无 LLM 段则原样返回） */
    static String extractLlmPart(String enriched) {
        if (enriched == null) return "";
        int idx = enriched.indexOf("=== DeepSeek");
        if (idx < 0) idx = enriched.indexOf("=== Kimi");
        return idx >= 0 ? enriched.substring(idx) : enriched;
    }
}
