package com.stock.service;

import com.stock.agent.AgentFactory;
import com.stock.agent.ModelChoice;
import com.stock.entity.StockMarginDaily;
import com.stock.entity.StockZtPool;
import com.stock.repository.StockMarginDailyRepository;
import com.stock.repository.StockZtPoolRepository;
import com.stock.workflow.engine.WorkflowTextUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市场情绪周期仪：基于传统盘面指标计算 0-100 情绪分。
 * <p>
 * 指标构成（权重）：涨停家数 30% + 连板高度 25% + 封板率(炸板率反向) 25% + 两融余额环比 20%。
 * 北向资金因交易所 2024-08 起停止披露实时数据，不参与计算（降级策略）。
 * 两融数据缺失当日时按剩余权重归一化。
 * <p>
 * interpret：LLM 生成周期定性（冰点/启动/主升/退潮），按自然日缓存；LLM 失败降级为规则法。
 */
@Service
public class MarketSentimentService {

    private static final Logger log = LoggerFactory.getLogger(MarketSentimentService.class);

    static final int DEFAULT_DAYS = 10;
    static final int MAX_DAYS = 30;
    // 权重：涨停家数 / 连板高度 / 封板率 / 两融环比
    static final double W_ZT = 0.30;
    static final double W_HEIGHT = 0.25;
    static final double W_SEAL = 0.25;
    static final double W_MARGIN = 0.20;
    // 归一化基准：涨停 120 家满分、连板 7 板满分、两融环比 ±1% 打满区间
    static final int ZT_FULL = 120;
    static final int HEIGHT_FULL = 7;
    static final double MARGIN_FULL_PCT = 1.0;

    private final StockZtPoolRepository ztPoolRepo;
    private final StockMarginDailyRepository marginRepo;
    private final AgentFactory agentFactory;
    /** 周期定性缓存：自然日 → interpret 结果 */
    private final Map<LocalDate, Map<String, Object>> interpretCache = new ConcurrentHashMap<>();

    public MarketSentimentService(StockZtPoolRepository ztPoolRepo,
                                  StockMarginDailyRepository marginRepo,
                                  AgentFactory agentFactory) {
        this.ztPoolRepo = ztPoolRepo;
        this.marginRepo = marginRepo;
        this.agentFactory = agentFactory;
    }

    /** 单日指标明细 */
    public record DailyIndicator(
            LocalDate tradeDate,
            int ztCount,
            int maxLimitUpDays,
            int brokenCount,
            double brokenRate,
            double sealRate,
            BigDecimal marginTotal,
            Double marginChangePct,
            double score) {
    }

    /** 情绪快照：最新情绪分 + 每日指标序列（日期倒序） */
    public record SentimentSnapshot(
            LocalDate latestDate,
            double latestScore,
            String phaseHint,
            String note,
            List<DailyIndicator> dailyIndicators) {
    }

    /** 计算最近 N 个有数据交易日的情绪指标（days 限制在 1-30） */
    public SentimentSnapshot getSentiment(int days) {
        int n = Math.max(1, Math.min(MAX_DAYS, days <= 0 ? DEFAULT_DAYS : days));
        List<LocalDate> dates = ztPoolRepo.findDistinctTradeDates(PageRequest.of(0, n));
        if (dates.isEmpty()) {
            return new SentimentSnapshot(null, 0, "无数据",
                    "数据库中暂无涨停池数据，请先执行市场数据抓取", List.of());
        }
        // 两融按日期建映射（SH+SZ 汇总），多取一天用于最早一日的环比
        Map<LocalDate, BigDecimal> marginByDate = loadMarginTotals(n + 5);

        List<DailyIndicator> indicators = new ArrayList<>();
        for (LocalDate date : dates) {
            indicators.add(buildDaily(date, marginByDate));
        }
        DailyIndicator latest = indicators.get(0);
        return new SentimentSnapshot(latest.tradeDate(), latest.score(),
                phaseByRule(indicators),
                "北向资金自2024-08起交易所停止披露，已从情绪指标中剔除",
                indicators);
    }

    private DailyIndicator buildDaily(LocalDate date, Map<LocalDate, BigDecimal> marginByDate) {
        List<StockZtPool> pool =
                ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(date, "TODAY");
        int ztCount = pool.size();
        int maxDays = 0;
        int broken = 0;
        for (StockZtPool z : pool) {
            maxDays = Math.max(maxDays, z.getLimitUpDays() == null ? 1 : z.getLimitUpDays());
            if (z.getOpenTimes() != null && z.getOpenTimes() > 0) broken++;
        }
        double brokenRate = ztCount == 0 ? 0 : round1(broken * 100.0 / ztCount);
        double sealRate = round1(100.0 - brokenRate);

        BigDecimal marginTotal = marginByDate.get(date);
        Double marginChangePct = marginChangePct(date, marginByDate);

        double score = computeScore(ztCount, maxDays, brokenRate, marginChangePct);
        return new DailyIndicator(date, ztCount, maxDays, broken, brokenRate, sealRate,
                marginTotal, marginChangePct, score);
    }

    /** 0-100 情绪分；两融环比缺失时剩余三项权重归一化 */
    double computeScore(int ztCount, int maxLimitUpDays, double brokenRate, Double marginChangePct) {
        double ztScore = Math.min(ztCount, ZT_FULL) * 100.0 / ZT_FULL;
        double heightScore = Math.min(maxLimitUpDays, HEIGHT_FULL) * 100.0 / HEIGHT_FULL;
        double sealScore = Math.max(0, 100.0 - brokenRate);

        double weighted = ztScore * W_ZT + heightScore * W_HEIGHT + sealScore * W_SEAL;
        double weightSum = W_ZT + W_HEIGHT + W_SEAL;
        if (marginChangePct != null) {
            double clamped = Math.max(-MARGIN_FULL_PCT, Math.min(MARGIN_FULL_PCT, marginChangePct));
            double marginScore = 50 + clamped / MARGIN_FULL_PCT * 50;
            weighted += marginScore * W_MARGIN;
            weightSum += W_MARGIN;
        }
        return round1(Math.max(0, Math.min(100, weighted / weightSum)));
    }

    /** SH+SZ 两融余额合计按日汇总 */
    private Map<LocalDate, BigDecimal> loadMarginTotals(int days) {
        Map<LocalDate, BigDecimal> byDate = new LinkedHashMap<>();
        try {
            List<LocalDate> dates = marginRepo.findDistinctTradeDates(PageRequest.of(0, days));
            if (dates.isEmpty()) return byDate;
            for (StockMarginDaily m : marginRepo.findByTradeDateInOrderByTradeDateDescMarketAsc(dates)) {
                if (m.getTotalBalance() != null) {
                    byDate.merge(m.getTradeDate(), m.getTotalBalance(), BigDecimal::add);
                }
            }
        } catch (Exception e) {
            log.warn("两融数据加载失败（情绪分按剩余权重归一化）: {}", e.getMessage());
        }
        return byDate;
    }

    /** 两融余额相对上一有数据交易日的环比（%），任一端缺数返回 null */
    private Double marginChangePct(LocalDate date, Map<LocalDate, BigDecimal> marginByDate) {
        BigDecimal cur = marginByDate.get(date);
        if (cur == null || cur.signum() == 0) return null;
        LocalDate prev = marginByDate.keySet().stream()
                .filter(d -> d.isBefore(date))
                .max(LocalDate::compareTo)
                .orElse(null);
        if (prev == null) return null;
        BigDecimal prevVal = marginByDate.get(prev);
        if (prevVal == null || prevVal.signum() == 0) return null;
        return cur.subtract(prevVal)
                .multiply(BigDecimal.valueOf(100))
                .divide(prevVal, 3, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // ────────── 周期定性 interpret ──────────

    /** LLM 周期定性（冰点/启动/主升/退潮），当日缓存；LLM 失败降级规则法 */
    public Map<String, Object> interpret() {
        return interpretCache.computeIfAbsent(LocalDate.now(), d -> doInterpret());
    }

    private Map<String, Object> doInterpret() {
        SentimentSnapshot snapshot = getSentiment(DEFAULT_DAYS);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (snapshot.latestDate() == null) {
            resp.put("phase", "无数据");
            resp.put("analysis", snapshot.note());
            resp.put("source", "rule");
            return resp;
        }
        String context = buildSentimentContext(snapshot);
        try {
            HarnessAgent agent = agentFactory.createTempAgent(ModelChoice.DEEPSEEK_V4,
                    "你是A股市场情绪周期分析专家，擅长依据涨停家数、连板高度、炸板率、两融余额变化"
                            + "判断市场所处的情绪周期阶段。周期阶段只能从【冰点/启动/主升/退潮】四个词中选一个。",
                    1024);
            String query = context + "\n\n请完成两件事：\n"
                    + "1. 第一行输出：周期阶段=X（X 只能是 冰点/启动/主升/退潮 之一）\n"
                    + "2. 随后用 200 字以内说明判断依据（引用上面的具体指标数值）与操作层面的含义。";
            Msg result = agent.call(List.of(new UserMessage(query))).block();
            String text = result != null && result.getTextContent() != null
                    ? WorkflowTextUtils.stripNonBmp(result.getTextContent().trim()) : "";
            if (!text.isBlank()) {
                resp.put("phase", extractPhase(text, snapshot));
                resp.put("analysis", text);
                resp.put("source", "llm");
                resp.put("score", snapshot.latestScore());
                resp.put("tradeDate", snapshot.latestDate());
                return resp;
            }
        } catch (Exception e) {
            log.warn("情绪周期 LLM 定性失败，降级为规则法: {}", e.getMessage());
        }
        resp.put("phase", phaseByRule(snapshot.dailyIndicators()));
        resp.put("analysis", "LLM 定性暂不可用，按规则法判定：最新情绪分 " + snapshot.latestScore()
                + "（≥70 主升 / <30 冰点 / 其余按与近期均值比较判定启动或退潮）");
        resp.put("source", "rule");
        resp.put("score", snapshot.latestScore());
        resp.put("tradeDate", snapshot.latestDate());
        return resp;
    }

    /** 供 LLM / 工作流 PROMPT 节点使用的情绪上下文文本 */
    public String buildSentimentContext(int days) {
        return buildSentimentContext(getSentiment(days));
    }

    private String buildSentimentContext(SentimentSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("【市场情绪指标（最近 ").append(snapshot.dailyIndicators().size())
          .append(" 个交易日，日期倒序）】\n");
        sb.append("说明：情绪分=涨停家数30%+连板高度25%+封板率25%+两融余额环比20%（两融缺数时权重归一化）；")
          .append(snapshot.note()).append("\n");
        sb.append("日期 | 情绪分 | 涨停家数 | 最高连板 | 炸板数 | 炸板率% | 两融余额(亿) | 两融环比%\n");
        for (DailyIndicator d : snapshot.dailyIndicators()) {
            sb.append(d.tradeDate()).append(" | ")
              .append(d.score()).append(" | ")
              .append(d.ztCount()).append(" | ")
              .append(d.maxLimitUpDays()).append("板 | ")
              .append(d.brokenCount()).append(" | ")
              .append(d.brokenRate()).append(" | ")
              .append(d.marginTotal() == null ? "-"
                      : d.marginTotal().divide(BigDecimal.valueOf(100_000_000), 0, RoundingMode.HALF_UP))
              .append(" | ")
              .append(d.marginChangePct() == null ? "-" : d.marginChangePct())
              .append("\n");
        }
        return sb.toString();
    }

    /** 规则法周期判定：≥70 主升 / <30 冰点 / 其余对比近期均值 → 启动/退潮 */
    String phaseByRule(List<DailyIndicator> indicators) {
        if (indicators.isEmpty()) return "无数据";
        double latest = indicators.get(0).score();
        if (latest >= 70) return "主升";
        if (latest < 30) return "冰点";
        double avg = indicators.stream().mapToDouble(DailyIndicator::score).average().orElse(latest);
        return latest >= avg ? "启动" : "退潮";
    }

    /** 从 LLM 首行 “周期阶段=X” 提取阶段，失败回退规则法 */
    private String extractPhase(String text, SentimentSnapshot snapshot) {
        for (String phase : List.of("冰点", "启动", "主升", "退潮")) {
            if (text.length() >= 30 ? text.substring(0, 30).contains(phase) : text.contains(phase)) {
                return phase;
            }
        }
        return phaseByRule(snapshot.dailyIndicators());
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
