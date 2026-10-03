package com.stock.service;

import com.stock.agent.AgentFactory;
import com.stock.agent.ModelChoice;
import com.stock.entity.*;
import com.stock.repository.*;
import com.stock.workflow.engine.WorkflowTextUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 财务对标/竞品对比：
 * <ol>
 *   <li>buildComparison：基于 StockCompetitor 的竞品清单，取各家 StockFinance 最新期
 *       EPS/ROE/营收/净利 + 最新行情/市值/PE 并排返回（缺数据字段置 null）</li>
 *   <li>getComment：AgentFactory 生成"竞争格局点评"（300-500字），
 *       结果按 code+日期缓存到 EnrichmentData（directionKey=COMPARISON_COMMENT），
 *       入库前 stripNonBmp 防 MySQL utf8 死信</li>
 * </ol>
 */
@Service
public class StockComparisonService {

    private static final Logger log = LoggerFactory.getLogger(StockComparisonService.class);

    /** EnrichmentData 缓存方向标识 */
    static final String COMMENT_DIRECTION_KEY = "COMPARISON_COMMENT";

    private final StockBasicRepository basicRepo;
    private final StockCompanyRepository companyRepo;
    private final StockCompetitorRepository competitorRepo;
    private final StockFinanceRepository financeRepo;
    private final StockQuoteRepository quoteRepo;
    private final EnrichmentDataRepository enrichmentRepo;
    private final AgentFactory agentFactory;

    public StockComparisonService(StockBasicRepository basicRepo,
                                  StockCompanyRepository companyRepo,
                                  StockCompetitorRepository competitorRepo,
                                  StockFinanceRepository financeRepo,
                                  StockQuoteRepository quoteRepo,
                                  EnrichmentDataRepository enrichmentRepo,
                                  AgentFactory agentFactory) {
        this.basicRepo = basicRepo;
        this.companyRepo = companyRepo;
        this.competitorRepo = competitorRepo;
        this.financeRepo = financeRepo;
        this.quoteRepo = quoteRepo;
        this.enrichmentRepo = enrichmentRepo;
        this.agentFactory = agentFactory;
    }

    // ==================== 对比数据组装 ====================

    /** 本股 + 竞品的指标并排对比。本股不存在时抛 NoSuchElementException。 */
    public Map<String, Object> buildComparison(String code) {
        StockBasic basic = basicRepo.findByCode(code)
                .orElseThrow(() -> new NoSuchElementException("股票不存在: " + code));

        Map<String, Object> base = buildRow(basic.getName(), code, true);
        base.put("industry", basic.getIndustry());

        List<StockCompetitor> competitors = competitorRepo.findByStockId(basic.getId());
        List<Map<String, Object>> peers = new ArrayList<>();
        for (StockCompetitor comp : competitors) {
            Map<String, Object> row = buildRow(comp.getCompetitorName(), comp.getCompetitorCode(), false);
            // DB 无该竞品财务/行情时，回退到 StockCompetitor 表内快照字段
            if (row.get("revenue") == null && comp.getCompetitorRevenue() != null) {
                row.put("revenue", comp.getCompetitorRevenue());
            }
            if (row.get("netProfit") == null && comp.getCompetitorNetProfit() != null) {
                row.put("netProfit", comp.getCompetitorNetProfit());
            }
            if (row.get("roe") == null && comp.getCompetitorRoe() != null) {
                row.put("roe", comp.getCompetitorRoe());
            }
            if (row.get("marketCap") == null && comp.getCompetitorMarketCap() != null) {
                row.put("marketCap", BigDecimal.valueOf(comp.getCompetitorMarketCap()));
            }
            row.put("mainProduct", comp.getCompetitorMainProduct());
            row.put("isListed", comp.getCompetitorIsListed());
            peers.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base", base);
        result.put("competitors", peers);
        return result;
    }

    /**
     * 单只股票的对比行：最新期财务 + 最新行情推算市值/PE。
     * code 为空（未上市竞品）或查不到数据时对应字段为 null。
     */
    private Map<String, Object> buildRow(String name, String code, boolean isBase) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("code", code);
        row.put("reportDate", null);
        row.put("eps", null);
        row.put("roe", null);
        row.put("revenue", null);
        row.put("netProfit", null);
        row.put("price", null);
        row.put("marketCap", null);
        row.put("pe", null);
        if (code == null || code.isBlank()) {
            return row;
        }

        List<StockFinance> finances = financeRepo.findByCodeOrderByReportDateDesc(code);
        StockFinance latest = finances.isEmpty() ? null : finances.get(0);
        if (latest != null) {
            row.put("reportDate", latest.getReportDate());
            row.put("eps", latest.getBasicEps());
            row.put("roe", latest.getWeightedRoe());
            row.put("revenue", latest.getTotalRevenue());
            row.put("netProfit", latest.getNetProfit());
        }

        Optional<StockQuote> quoteOpt = quoteRepo.findTopByCodeOrderByUpdateTimeDesc(code);
        BigDecimal price = quoteOpt.map(StockQuote::getPrice).orElse(null);
        row.put("price", price);

        // 市值 = 最新价 × 总股本（StockCompany），本股/竞品皆按此口径，缺数据置 null
        BigDecimal marketCap = null;
        Optional<StockBasic> basicOpt = basicRepo.findByCode(code);
        if (price != null && basicOpt.isPresent()) {
            Long totalShares = companyRepo.findByStockId(basicOpt.get().getId())
                    .map(StockCompany::getTotalShares).orElse(null);
            if (totalShares != null && totalShares > 0) {
                marketCap = price.multiply(BigDecimal.valueOf(totalShares));
            }
        }
        row.put("marketCap", marketCap);
        row.put("pe", computePe(price, latest));
        return row;
    }

    /**
     * 静态 PE ≈ 现价 / 年化EPS。年化：EPS ÷ 报告期月份 × 12（如中报 ×2）。
     * EPS ≤ 0（亏损）或缺数据时返回 null。
     */
    static BigDecimal computePe(BigDecimal price, StockFinance latest) {
        if (price == null || latest == null || latest.getBasicEps() == null
                || latest.getReportDate() == null
                || latest.getBasicEps().compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        int month = latest.getReportDate().getMonthValue();
        BigDecimal annualEps = latest.getBasicEps()
                .multiply(BigDecimal.valueOf(12))
                .divide(BigDecimal.valueOf(month), 6, RoundingMode.HALF_UP);
        if (annualEps.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return price.divide(annualEps, 2, RoundingMode.HALF_UP);
    }

    // ==================== 竞争格局点评（LLM） ====================

    /**
     * 竞争格局点评：按 code+当日 缓存在 EnrichmentData；当日已有则直接复用。
     */
    public Map<String, Object> getComment(String code) {
        // 当日缓存命中直接返回
        Optional<EnrichmentData> cached = enrichmentRepo
                .findTopByStockCodeAndDirectionKeyOrderByCreatedAtDesc(code, COMMENT_DIRECTION_KEY);
        if (cached.isPresent() && cached.get().getCreatedAt() != null
                && cached.get().getCreatedAt().toLocalDate().equals(LocalDate.now())
                && cached.get().getEnrichmentJson() != null) {
            return commentResult(code, cached.get().getEnrichmentJson(), true,
                    cached.get().getCreatedAt());
        }

        Map<String, Object> comparison = buildComparison(code);
        String prompt = buildCommentPrompt(comparison);
        String sysPrompt = """
                你是一名A股行业研究员。基于给定的公司与竞品财务对比数据，输出一段"竞争格局点评"。
                要求：300-500字中文；覆盖相对规模、盈利能力、估值水平、竞争地位四个角度；
                数据缺失的公司如实说明"数据不足"；不要输出表格/标题/emoji，只输出正文段落。""";

        HarnessAgent agent = agentFactory.createTempAgent(ModelChoice.DEEPSEEK_V4, sysPrompt, 1024);
        Msg reply = agent.call(List.of(new UserMessage(prompt))).block();
        String comment = reply != null && reply.getTextContent() != null
                ? reply.getTextContent().trim() : null;
        if (comment == null || comment.isBlank()) {
            throw new IllegalStateException("竞争格局点评生成失败：LLM 未返回内容");
        }

        // 入库前剔除非 BMP 字符（emoji 4字节会导致 MySQL utf8 死信）
        String safeComment = WorkflowTextUtils.stripNonBmp(comment);
        EnrichmentData entity = new EnrichmentData();
        entity.setStockCode(code);
        entity.setDirectionKey(COMMENT_DIRECTION_KEY);
        entity.setPromptType("comparison_comment_v1");
        entity.setContextJson(WorkflowTextUtils.stripNonBmp(prompt));
        entity.setEnrichmentJson(safeComment);
        entity.setModelSource("deepseek-v4");
        entity.setExpiresAt(LocalDateTime.now().plusDays(1));
        try {
            enrichmentRepo.save(entity);
        } catch (Exception e) {
            log.error("StockComparisonService: 点评缓存入库失败 code={}", code, e);
        }
        return commentResult(code, safeComment, false, LocalDateTime.now());
    }

    private Map<String, Object> commentResult(String code, String comment, boolean fromCache,
                                              LocalDateTime generatedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("comment", comment);
        result.put("fromCache", fromCache);
        result.put("generatedAt", generatedAt);
        return result;
    }

    /** 把对比数据压成 LLM 可读的紧凑文本 */
    @SuppressWarnings("unchecked")
    private String buildCommentPrompt(Map<String, Object> comparison) {
        StringBuilder sb = new StringBuilder("以下是公司及竞品最新期财务对比数据：\n");
        appendRow(sb, (Map<String, Object>) comparison.get("base"), "【本公司】");
        List<Map<String, Object>> peers = (List<Map<String, Object>>) comparison.get("competitors");
        if (peers == null || peers.isEmpty()) {
            sb.append("（暂无竞品数据，请基于公司自身数据和行业常识点评）\n");
        } else {
            for (Map<String, Object> peer : peers) {
                appendRow(sb, peer, "【竞品】");
            }
        }
        sb.append("\n请输出300-500字的竞争格局点评。");
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, Map<String, Object> row, String label) {
        if (row == null) {
            return;
        }
        sb.append(label).append(row.get("name")).append("(").append(row.get("code")).append(") ")
          .append("报告期=").append(orNa(row.get("reportDate")))
          .append(" EPS=").append(orNa(row.get("eps")))
          .append(" ROE=").append(orNa(row.get("roe"))).append("%")
          .append(" 营收=").append(toYi(row.get("revenue")))
          .append(" 净利=").append(toYi(row.get("netProfit")))
          .append(" 市值=").append(toYi(row.get("marketCap")))
          .append(" PE=").append(orNa(row.get("pe")))
          .append("\n");
    }

    private String orNa(Object v) {
        return v == null ? "N/A" : String.valueOf(v);
    }

    private String toYi(Object v) {
        if (v instanceof BigDecimal bd) {
            return bd.divide(BigDecimal.valueOf(1e8), 2, RoundingMode.HALF_UP) + "亿";
        }
        return "N/A";
    }
}
