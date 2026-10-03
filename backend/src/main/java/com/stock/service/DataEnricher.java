package com.stock.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stock.agent.AgentFactory;
import com.stock.agent.AnalysisDirection;
import com.stock.agent.ModelChoice;
import com.stock.entity.EnrichmentData;
import com.stock.repository.EnrichmentDataRepository;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 通用外部数据获取器 — Serper搜索（默认优先）→ DeepSeek V4 Flash → Kimi 级联查询。
 * <p>
 * 两级缓存策略：
 * 1. 内存缓存（ConcurrentHashMap）：同一 JVM 会话内秒级去重
 * 2. DB 持久化（enrichment_data 表）：跨会话复用，避免重复 LLM 调用
 * <p>
 * 每个分析方向通过 {@link AnalysisDirection.EnrichmentSpec} 定义要查询什么、如何压缩token。
 * 未指定 enrichment 的方向直接使用 DB 数据，不走 LLM 查询。
 * <p>
 * Serper 为系统默认优先数据源：命中时搜索结果作为真实外部数据注入 DeepSeek
 * 查询上下文供其提炼；搜索失败/未配置时静默降级为原 DeepSeek→Kimi 链路。
 */
@Service
public class DataEnricher {

    private static final Logger log = LoggerFactory.getLogger(DataEnricher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 默认过期时间：6小时
    private static final int DEFAULT_EXPIRE_HOURS = 6;

    private final AgentFactory agentFactory;
    private final EnrichmentDataRepository enrichmentRepo;
    private final SerperSearchService serperSearch;
    // 内存缓存：避免同一会话内重复调用
    private final ConcurrentHashMap<String, String> memoryCache = new ConcurrentHashMap<>();
    // 异步持久化线程池
    private final Executor saveExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "enrich-saver");
        t.setDaemon(true);
        return t;
    });

    public DataEnricher(AgentFactory agentFactory, EnrichmentDataRepository enrichmentRepo,
                        SerperSearchService serperSearch) {
        this.agentFactory = agentFactory;
        this.enrichmentRepo = enrichmentRepo;
        this.serperSearch = serperSearch;
    }

    /**
     * 通用数据增强入口：
     * Phase 0: 查内存缓存 → 查 DB 缓存
     * Phase 1: Serper 搜索（默认优先数据源，真实网络搜索结果）
     * Phase 2: DeepSeek V4 Flash 查询（携带 Serper 结果提炼；快速、便宜）
     * Phase 3: Kimi 补充查询（DeepSeek 无法获取的实时/专业数据）
     * Phase 4: 异步保存到 DB
     *
     * @param stockCode     股票代码
     * @param stockName     股票名称
     * @param dbContext     DataContextBuilder.buildDataContext() 的完整输出
     * @param directionKey  分析方向key（用于缓存key）
     * @param spec          该方向的获取规格（null 表示无需增强）
     * @return 增强后的数据上下文（合并了DB数据 + Serper搜索 + LLM获取的外部数据）
     */
    public String enrich(String stockCode, String stockName, String dbContext,
                         String directionKey, AnalysisDirection.EnrichmentSpec spec) {
        return enrich(stockCode, stockName, dbContext, directionKey, spec, null);
    }

    /**
     * 同上，额外指定 Serper 搜索主题（searchTopic 为空时从 directionKey 推导）。
     */
    public String enrich(String stockCode, String stockName, String dbContext,
                         String directionKey, AnalysisDirection.EnrichmentSpec spec,
                         String searchTopic) {
        if (spec == null) {
            // 无 LLM 增强需求，但仍异步保存数据上下文（K线、指标等关键数据）供复用
            saveContextOnlyAsync(stockCode, directionKey, dbContext);
            return dbContext;
        }

        String cacheKey = stockCode + "|" + directionKey;

        // ── L1: 内存缓存 ──
        String memCached = memoryCache.get(cacheKey);
        if (memCached != null) return memCached;

        // ── L2: DB 缓存 ──
        String dbCached = loadFromDb(stockCode, directionKey);
        if (dbCached != null) {
            memoryCache.put(cacheKey, dbCached);
            return dbCached;
        }

        // ── 需要调用 LLM ──
        // Token compression: truncate DB context
        String ctx = dbContext;
        int maxChars = spec.mc();
        if (dbContext.length() > maxChars) {
            ctx = dbContext.substring(0, maxChars) + "\n\n... (数据截断，保留前" + maxChars + "字符)";
        }

        StringBuilder enriched = new StringBuilder();
        enriched.append("=== 数据库数据 ===\n").append(ctx);

        String serperResult = null;
        String dsResult = null;
        String kimiResult = null;
        String modelSource = "none";

        // Phase 1: Serper 搜索（默认优先数据源）
        serperResult = trySerper(stockCode, stockName, directionKey, searchTopic);
        if (serperResult != null) {
            enriched.append("\n\n=== Serper搜索结果（优先数据源） ===\n").append(serperResult);
            modelSource = "serper";
            // 搜索结果并入 DeepSeek 查询上下文，供其提炼与交叉验证
            ctx = ctx + "\n\n【网络搜索结果（优先参考，来自 Google/Serper）】\n" + serperResult;
        }

        // Phase 2: DeepSeek V4 Flash
        String dsQuery = spec.deepseekQuery()
                .replace("{code}", stockCode)
                .replace("{name}", stockName)
                .replace("{context}", ctx);
        dsResult = callAgent(ModelChoice.DEEPSEEK_V4, spec.deepseekSystemPrompt(), dsQuery, spec.mt());

        if (dsResult != null && !dsResult.isBlank()) {
            enriched.append("\n\n=== DeepSeek Flash查询结果 ===\n").append(dsResult);
            modelSource = modelSource.equals("serper") ? "serper,deepseek-v4" : "deepseek-v4";

            // Phase 3: Kimi for gaps（Serper 已命中时搜索结果已补齐实时数据，不再回退 Kimi）
            if (serperResult == null && hasGaps(dsResult)) {
                log.info("DataEnricher[{}]: DeepSeek returned gaps, falling back to Kimi", directionKey);
                String kimiQuery = spec.kimiFallbackPrompt() + "\n\n" +
                        "股票: " + stockName + "(" + stockCode + ")\n" +
                        "DeepSeek已返回: " + dsResult + "\n" +
                        "请补充DeepSeek未能提供的数据。";
                kimiResult = callAgent(ModelChoice.KIMI, spec.kimiFallbackPrompt(), kimiQuery, spec.mt());

                if (kimiResult != null && !kimiResult.isBlank()) {
                    enriched.append("\n\n=== Kimi补充数据 ===\n").append(kimiResult);
                    modelSource = "deepseek-v4,kimi";
                }
            }
        } else {
            // DeepSeek failed, try Kimi directly
            log.warn("DataEnricher[{}]: DeepSeek returned empty, trying Kimi", directionKey);
            String kimiQuery = spec.kimiFallbackPrompt() + "\n\n" +
                    "股票: " + stockName + "(" + stockCode + ")\n" +
                    "DB数据: " + ctx;
            kimiResult = callAgent(ModelChoice.KIMI, spec.kimiFallbackPrompt(), kimiQuery, spec.mt());

            if (kimiResult != null && !kimiResult.isBlank()) {
                enriched.append("\n\n=== Kimi直接查询结果 ===\n").append(kimiResult);
                modelSource = modelSource.equals("serper") ? "serper,kimi" : "kimi";
            }
        }

        String result = enriched.toString();

        // ── 检查：如果有 enrichment spec 但 Serper 与 LLM 全部失败，直接报错中止 ──
        if (modelSource.equals("none")) {
            String errMsg = String.format(
                    "外部数据获取失败: %s(%s) 的方向 %s 在 Serper、DeepSeek 和 Kimi 均未返回有效数据",
                    stockName, stockCode, directionKey);
            log.error(errMsg);
            throw new RuntimeException(errMsg);
        }

        memoryCache.put(cacheKey, result);

        // ── 异步保存到 DB ──
        final String finalSerperResult = serperResult;
        final String finalDsResult = dsResult;
        final String finalKimiResult = kimiResult;
        final String finalModelSource = modelSource;
        CompletableFuture.runAsync(() ->
                saveToDb(stockCode, directionKey, dbContext,
                        finalSerperResult, finalDsResult, finalKimiResult, finalModelSource),
                saveExecutor);

        return result;
    }

    /**
     * Serper 搜索（默认优先数据源）：构造中文查询词，舆情/新闻类主题走 /news 端点。
     * 任何失败返回 null（降级不中断）。
     */
    private String trySerper(String stockCode, String stockName, String directionKey, String searchTopic) {
        if (!serperSearch.isAvailable()) {
            return null;
        }
        String topic = searchTopic != null && !searchTopic.isBlank()
                ? searchTopic.trim() : deriveTopic(directionKey);
        String query = stockName + " " + stockCode + " " + topic;
        boolean newsLike = topic.contains("舆情") || topic.contains("新闻") || topic.contains("情绪")
                || topic.contains("动态") || topic.contains("公告");
        String result = newsLike ? serperSearch.searchNews(query) : serperSearch.search(query);
        if (result == null && newsLike) {
            result = serperSearch.search(query);
        }
        return result;
    }

    /** 从 directionKey 推导搜索主题：WF_ENRICH_ 前缀取余部，枚举方向映射中文关键词 */
    private String deriveTopic(String directionKey) {
        if (directionKey == null) {
            return "最新动态";
        }
        if (directionKey.startsWith("WF_ENRICH_")) {
            return directionKey.substring("WF_ENRICH_".length());
        }
        return switch (directionKey) {
            case "SECTOR_ANALYSIS" -> "所属板块 行业动态 政策";
            case "SENTIMENT_ANALYSIS" -> "最新新闻 舆情";
            case "CAPITAL_FLOW" -> "主力资金 龙虎榜 北向资金";
            case "VALUATION_ANALYSIS" -> "市盈率 估值 同行对比";
            case "FUNDAMENTAL_ANALYSIS" -> "业绩 财报 基本面";
            case "CHIP_STRUCTURE" -> "股东户数 筹码 解禁";
            case "RISK_WARNING" -> "风险 监管 诉讼 减持";
            default -> "最新动态";
        };
    }

    /** Clear cache (call on session reset) */
    public void clearCache() {
        memoryCache.clear();
    }

    // ────────── DB 操作 ──────────

    /** 仅保存数据上下文（无 LLM 增强数据的场景，如 TECHNICAL_ANALYSIS 等） */
    private void saveContextOnlyAsync(String stockCode, String directionKey, String contextJson) {
        CompletableFuture.runAsync(() -> {
            try {
                var existing = enrichmentRepo.findTopByStockCodeAndDirectionKeyOrderByCreatedAtDesc(
                        stockCode, directionKey);
                if (existing.isPresent()) {
                    var data = existing.get();
                    if (data.getExpiresAt() != null && data.getExpiresAt().isAfter(LocalDateTime.now())) {
                        return; // 未过期，跳过
                    }
                }
                EnrichmentData entity = new EnrichmentData();
                entity.setStockCode(stockCode);
                entity.setDirectionKey(directionKey);
                entity.setPromptType(directionKey.toLowerCase() + "_v1");
                entity.setContextJson(contextJson);
                entity.setEnrichmentJson("{\"source\":\"db_only\"}");
                entity.setModelSource("db");
                entity.setExpiresAt(LocalDateTime.now().plusHours(DEFAULT_EXPIRE_HOURS));
                enrichmentRepo.save(entity);
            } catch (Exception e) {
                log.error("DataEnricher[{}]: failed to save context", directionKey, e);
            }
        }, saveExecutor);
    }

    /**
     * 从 DB 加载缓存（检查是否过期）。
     * 返回完整的 enriched text（数据库数据 + LLM增强数据 的组合）。
     */
    private String loadFromDb(String stockCode, String directionKey) {
        try {
            var opt = enrichmentRepo.findTopByStockCodeAndDirectionKeyOrderByCreatedAtDesc(
                    stockCode, directionKey);
            if (opt.isEmpty()) return null;

            EnrichmentData data = opt.get();
            // 检查过期
            if (data.getExpiresAt() != null && data.getExpiresAt().isBefore(LocalDateTime.now())) {
                log.info("DataEnricher[{}]: cached data expired at {}", directionKey, data.getExpiresAt());
                return null;
            }

            String combined = rebuildEnrichedText(data);
            if (combined != null && !combined.isBlank()) {
                log.info("DataEnricher[{}]: hit DB cache for {}, created at {}",
                        directionKey, stockCode, data.getCreatedAt());
                return combined;
            }
        } catch (Exception e) {
            log.warn("DataEnricher[{}]: failed to load from DB", directionKey, e);
        }
        return null;
    }

    /**
     * 从 EnrichmentData 实体重建 enriched text
     */
    private String rebuildEnrichedText(EnrichmentData data) {
        try {
            StringBuilder sb = new StringBuilder();
            // 上下文数据
            if (data.getContextJson() != null && !data.getContextJson().isBlank()) {
                sb.append("=== 数据库数据 ===\n").append(data.getContextJson());
            }
            // 增强数据
            if (data.getEnrichmentJson() != null && !data.getEnrichmentJson().isBlank()) {
                try {
                    ObjectNode enrichNode = (ObjectNode) MAPPER.readTree(data.getEnrichmentJson());
                    if (enrichNode.has("serper_result") && !enrichNode.get("serper_result").asText().isBlank()) {
                        sb.append("\n\n=== Serper搜索结果（优先数据源） ===\n")
                                .append(enrichNode.get("serper_result").asText());
                    }
                    if (enrichNode.has("deepseek_result") && !enrichNode.get("deepseek_result").asText().isBlank()) {
                        sb.append("\n\n=== DeepSeek Flash查询结果 ===\n")
                                .append(enrichNode.get("deepseek_result").asText());
                    }
                    if (enrichNode.has("kimi_result") && !enrichNode.get("kimi_result").asText().isBlank()) {
                        sb.append("\n\n=== Kimi补充数据 ===\n")
                                .append(enrichNode.get("kimi_result").asText());
                    }
                } catch (JsonProcessingException e) {
                    // fallback: use raw text
                    sb.append("\n\n=== 增强数据 ===\n").append(data.getEnrichmentJson());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 异步保存丰富数据到 DB */
    private void saveToDb(String stockCode, String directionKey,
                          String contextJson, String serperResult, String dsResult, String kimiResult,
                          String modelSource) {
        try {
            // 构建 enrichment JSON
            ObjectNode enrichNode = MAPPER.createObjectNode();
            enrichNode.put("serper_result", serperResult != null ? serperResult : "");
            enrichNode.put("deepseek_result", dsResult != null ? dsResult : "");
            enrichNode.put("kimi_result", kimiResult != null ? kimiResult : "");
            String enrichJson = MAPPER.writeValueAsString(enrichNode);

            EnrichmentData entity = new EnrichmentData();
            entity.setStockCode(stockCode);
            entity.setDirectionKey(directionKey);
            entity.setPromptType(directionKey.toLowerCase() + "_v1");
            entity.setContextJson(contextJson);
            entity.setEnrichmentJson(enrichJson);
            entity.setModelSource(modelSource);
            entity.setExpiresAt(LocalDateTime.now().plusHours(DEFAULT_EXPIRE_HOURS));

            enrichmentRepo.save(entity);
            log.debug("DataEnricher[{}]: saved enrichment for {} (model={})",
                    directionKey, stockCode, modelSource);
        } catch (Exception e) {
            log.error("DataEnricher[{}]: failed to save to DB", directionKey, e);
        }
    }

    // ────────── Internal ──────────

    private String callAgent(ModelChoice modelChoice, String systemPrompt, String query, int maxTokens) {
        try {
            HarnessAgent agent = agentFactory.createTempAgent(modelChoice, systemPrompt, maxTokens);
            Msg result = agent.call(List.of(new UserMessage(query))).block();
            if (result != null && result.getTextContent() != null) {
                return result.getTextContent().trim();
            }
        } catch (Exception e) {
            log.error("DataEnricher: agent call failed for {}", modelChoice, e);
        }
        return null;
    }

    /** Check if the result indicates incomplete data (heuristic) */
    private boolean hasGaps(String result) {
        if (result == null || result.isBlank()) return true;
        String lower = result.toLowerCase();
        return lower.contains("无法获取") || lower.contains("暂无数据") || lower.contains("不明确")
                || lower.contains("无法确定") || lower.contains("数据不足") || lower.contains("unknown")
                || lower.contains("n/a") || result.trim().length() < 30;
    }
}
