package com.stock.workflow.engine;

import com.stock.entity.*;
import com.stock.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 股票分析工作流数据预注入：从 DB 加载真实数据，拼装为可直接放进提示词的文本上下文。
 * <p>
 * 变量契约（与前端执行弹窗、工作流生成任务共享，不得更改）：预注入变量一律下划线开头
 * {@code _xxx_context}；用户输入占位符为 ${stockCode}（必填）、${stockName}（可选）、${goal}。
 * <ul>
 *   <li>{@code _kline_context}：近 120 日K线摘要（最新价、MA5/20/60、量价特征、近 30 日明细）</li>
 *   <li>{@code _finance_context}：最近多期财务指标（EPS/ROE/营收/净利及同比）</li>
 *   <li>{@code _fundflow_context}：近 20 日资金流（主力净流入序列与趋势）</li>
 *   <li>{@code _industry_context}：行业/产业链/竞品</li>
 *   <li>{@code _company_context}：公司基本信息</li>
 * </ul>
 * 拼装口径参照 {@link com.stock.service.DataContextBuilder}（MA 计算等），此处输出为面向
 * 提示词的紧凑文本而非 JSON。股票不存在或某类数据为空时对应变量填 {@link #NO_DATA}，
 * 任何异常均不抛出（不得中断流程），严禁 Mock 假数据。
 */
@Service
public class StockContextPreloader {

    public static final String NO_DATA = "（暂无该类数据）";

    /** 预注入变量名（契约） */
    public static final String VAR_KLINE = "_kline_context";
    public static final String VAR_FINANCE = "_finance_context";
    public static final String VAR_FUNDFLOW = "_fundflow_context";
    public static final String VAR_INDUSTRY = "_industry_context";
    public static final String VAR_COMPANY = "_company_context";

    private static final Logger log = LoggerFactory.getLogger(StockContextPreloader.class);

    private final StockBasicRepository stockBasicRepo;
    private final StockCompanyRepository stockCompanyRepo;
    private final StockKlineDailyRepository klineRepo;
    private final StockFinanceRepository financeRepo;
    private final StockFundFlowRepository fundFlowRepo;
    private final StockIndustryChainRepository industryChainRepo;
    private final StockCompetitorRepository competitorRepo;

    public StockContextPreloader(StockBasicRepository stockBasicRepo,
                                 StockCompanyRepository stockCompanyRepo,
                                 StockKlineDailyRepository klineRepo,
                                 StockFinanceRepository financeRepo,
                                 StockFundFlowRepository fundFlowRepo,
                                 StockIndustryChainRepository industryChainRepo,
                                 StockCompetitorRepository competitorRepo) {
        this.stockBasicRepo = stockBasicRepo;
        this.stockCompanyRepo = stockCompanyRepo;
        this.klineRepo = klineRepo;
        this.financeRepo = financeRepo;
        this.fundFlowRepo = fundFlowRepo;
        this.industryChainRepo = industryChainRepo;
        this.competitorRepo = competitorRepo;
    }

    /**
     * 构建全部预注入变量。每类数据独立兜底：单类失败/为空只影响该变量（填 NO_DATA），
     * 绝不抛异常。附带回填 stockName（用户未填且 DB 有时）。
     *
     * @param stockCode 6 位股票代码
     * @return 变量名 → 文本上下文（含 5 个 _xxx_context；stockName 仅在 DB 可查到时包含）
     */
    public Map<String, Object> buildContextVariables(String stockCode) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(VAR_KLINE, safeBuild("kline", () -> buildKlineContext(stockCode, 120)));
        vars.put(VAR_FINANCE, safeBuild("finance", () -> buildFinanceContext(stockCode)));
        vars.put(VAR_FUNDFLOW, safeBuild("fundflow", () -> buildFundflowContext(stockCode, 20)));
        vars.put(VAR_INDUSTRY, safeBuild("industry", () -> buildIndustryContext(stockCode)));
        vars.put(VAR_COMPANY, safeBuild("company", () -> buildCompanyContext(stockCode)));
        return vars;
    }

    /** DB 中该股票的名称；查不到返回 null（不抛异常） */
    public String resolveStockName(String stockCode) {
        try {
            return stockBasicRepo.findByCode(stockCode).map(StockBasic::getName).orElse(null);
        } catch (Exception e) {
            log.warn("stockName 回填失败: code={}, err={}", stockCode, e.getMessage());
            return null;
        }
    }

    // ────────── 各类上下文拼装（公开给轨道 B 工具复用） ──────────

    /** 近 N 日K线摘要：最新价、MA5/20/60、量价特征、近 30 日明细 */
    public String buildKlineContext(String stockCode, int days) {
        int n = clamp(days, 5, 250);
        List<StockKlineDaily> klines = klineRepo.findTop120ByCodeOrderByTradeDateDesc(stockCode);
        if (klines.size() > n) {
            klines = klines.subList(0, n);
        }
        if (klines.isEmpty()) {
            return NO_DATA;
        }
        StockKlineDaily latest = klines.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("【K线数据】").append(stockCode)
                .append("（近").append(klines.size()).append("个交易日，最新交易日 ")
                .append(latest.getTradeDate()).append("）\n");
        sb.append("最新收盘价: ").append(num(latest.getClose()));

        // 区间涨跌幅（最旧收盘 → 最新收盘）
        StockKlineDaily oldest = klines.get(klines.size() - 1);
        if (nonZero(oldest.getClose()) && latest.getClose() != null) {
            BigDecimal pct = latest.getClose().subtract(oldest.getClose())
                    .divide(oldest.getClose(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
            sb.append("，区间涨跌幅(").append(klines.size()).append("日): ")
                    .append(pct.signum() > 0 ? "+" : "").append(pct).append("%");
        }
        sb.append("\n均线: MA5=").append(fmt(ma(klines, 5)))
                .append("，MA20=").append(fmt(ma(klines, 20)))
                .append("，MA60=").append(fmt(ma(klines, 60))).append("\n");

        // 量价特征：近5日均量 vs 全区间均量
        double avgVolAll = avgVolume(klines, klines.size());
        double avgVol5 = avgVolume(klines, 5);
        sb.append("量价特征: 近5日均量 ").append(fmtLong(avgVol5))
                .append("，区间均量 ").append(fmtLong(avgVolAll));
        if (avgVolAll > 0) {
            double ratio = avgVol5 / avgVolAll;
            sb.append(ratio >= 1.2 ? "（近期放量）" : ratio <= 0.8 ? "（近期缩量）" : "（量能平稳）");
        }
        sb.append("，区间最高 ").append(num(maxHigh(klines)))
                .append("，最低 ").append(num(minLow(klines))).append("\n");

        // 近30日明细（旧→新）
        int detail = Math.min(30, klines.size());
        sb.append("近").append(detail).append("日明细(日期,开,高,低,收,量):\n");
        for (int i = detail - 1; i >= 0; i--) {
            StockKlineDaily k = klines.get(i);
            sb.append(k.getTradeDate()).append(",").append(num(k.getOpen())).append(",")
                    .append(num(k.getHigh())).append(",").append(num(k.getLow())).append(",")
                    .append(num(k.getClose())).append(",")
                    .append(k.getVolume() != null ? k.getVolume() : 0).append("\n");
        }
        return sb.toString().trim();
    }

    /** 最近多期财务指标（EPS/ROE/营收/净利及同比；毛利率/负债率/现金流当前 DB 未采集则不输出） */
    public String buildFinanceContext(String stockCode) {
        List<StockFinance> all = financeRepo.findByCodeOrderByReportDateDesc(stockCode);
        if (all.isEmpty()) {
            return NO_DATA;
        }
        List<StockFinance> recent = all.size() > 8 ? all.subList(0, 8) : all;
        StringBuilder sb = new StringBuilder();
        sb.append("【财务数据】").append(stockCode).append("（最近").append(recent.size()).append("期）\n");
        sb.append("报告期 | EPS(元) | ROE(%) | 营收(元) | 归母净利(元) | 营收同比 | 净利同比\n");
        for (StockFinance f : recent) {
            StockFinance yoy = findYoy(all, f);
            sb.append(f.getReportDate()).append(" | ")
                    .append(num(f.getBasicEps())).append(" | ")
                    .append(num(f.getWeightedRoe())).append(" | ")
                    .append(num(f.getTotalRevenue())).append(" | ")
                    .append(num(f.getNetProfit())).append(" | ")
                    .append(yoyPct(f.getTotalRevenue(), yoy != null ? yoy.getTotalRevenue() : null)).append(" | ")
                    .append(yoyPct(f.getNetProfit(), yoy != null ? yoy.getNetProfit() : null)).append("\n");
        }
        sb.append("（注：毛利率/负债率/现金流指标当前数据库未采集，暂无）");
        return sb.toString();
    }

    /** 近 N 日资金流：主力净流入序列与趋势 */
    public String buildFundflowContext(String stockCode, int days) {
        int n = clamp(days, 5, 60);
        List<StockFundFlow> flows = fundFlowRepo.findTop60ByCodeOrderByTradeDateDesc(stockCode);
        if (flows.size() > n) {
            flows = flows.subList(0, n);
        }
        if (flows.isEmpty()) {
            return NO_DATA;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【资金流数据】").append(stockCode).append("（近").append(flows.size()).append("个交易日）\n");
        sb.append("日期 | 主力净流入(元) | 主力净占比(%)\n");
        BigDecimal total = BigDecimal.ZERO;
        int inflowDays = 0;
        for (int i = flows.size() - 1; i >= 0; i--) {
            StockFundFlow ff = flows.get(i);
            sb.append(ff.getTradeDate()).append(" | ")
                    .append(num(ff.getMainNetInflow())).append(" | ")
                    .append(num(ff.getMainNetRatio())).append("\n");
            if (ff.getMainNetInflow() != null) {
                total = total.add(ff.getMainNetInflow());
                if (ff.getMainNetInflow().signum() > 0) inflowDays++;
            }
        }
        sb.append("趋势: 区间主力累计净流入 ").append(num(total))
                .append(" 元，净流入天数 ").append(inflowDays).append("/").append(flows.size())
                .append(total.signum() > 0 ? "（整体净流入）" : total.signum() < 0 ? "（整体净流出）" : "");
        return sb.toString();
    }

    /** 行业/产业链/竞品（IndustryChain + Competitor + Company 主营） */
    public String buildIndustryContext(String stockCode) {
        Optional<StockBasic> stockOpt = stockBasicRepo.findByCode(stockCode);
        if (stockOpt.isEmpty()) {
            return NO_DATA;
        }
        StockBasic stock = stockOpt.get();
        StringBuilder sb = new StringBuilder();
        boolean hasAny = false;

        sb.append("【行业与产业链】").append(stock.getName()).append("(").append(stockCode).append(")\n");
        if (notBlank(stock.getIndustry())) {
            sb.append("所属行业: ").append(stock.getIndustry()).append("\n");
            hasAny = true;
        }
        Optional<StockCompany> companyOpt = stockCompanyRepo.findByStockId(stock.getId());
        if (companyOpt.isPresent() && notBlank(companyOpt.get().getMainBusiness())) {
            sb.append("主营业务: ").append(companyOpt.get().getMainBusiness()).append("\n");
            hasAny = true;
        }
        Optional<StockIndustryChain> chainOpt = industryChainRepo.findByStockId(stock.getId());
        if (chainOpt.isPresent()) {
            StockIndustryChain ch = chainOpt.get();
            sb.append("产业链定位: 核心产品=").append(str(ch.getCoreProduct()))
                    .append("；位置=").append(str(ch.getIndustryPosition()))
                    .append("；上游=").append(str(ch.getUpstream()))
                    .append("；下游=").append(str(ch.getDownstream())).append("\n");
            sb.append("行业趋势: ").append(str(ch.getIndustryTrend()))
                    .append("；规模=").append(str(ch.getIndustrySize()))
                    .append("；增速=").append(str(ch.getIndustryGrowth()))
                    .append("；政策影响=").append(str(ch.getPolicyImpact())).append("\n");
            hasAny = true;
        }
        List<StockCompetitor> competitors = competitorRepo.findByStockId(stock.getId());
        if (!competitors.isEmpty()) {
            sb.append("主要竞品:\n");
            for (StockCompetitor cp : competitors) {
                sb.append("- ").append(str(cp.getCompetitorName()))
                        .append("(").append(str(cp.getCompetitorCode())).append(")")
                        .append(" 主营=").append(str(cp.getCompetitorMainProduct()))
                        .append("；毛利率=").append(num(cp.getCompetitorGrossMargin()))
                        .append("；ROE=").append(num(cp.getCompetitorRoe()))
                        .append("；优势=").append(str(cp.getAdvantage())).append("\n");
            }
            hasAny = true;
        }
        return hasAny ? sb.toString().trim() : NO_DATA;
    }

    /** 公司基本信息（StockBasic + StockCompany） */
    public String buildCompanyContext(String stockCode) {
        Optional<StockBasic> stockOpt = stockBasicRepo.findByCode(stockCode);
        if (stockOpt.isEmpty()) {
            return NO_DATA;
        }
        StockBasic stock = stockOpt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("【公司基本信息】\n");
        sb.append("名称: ").append(stock.getName()).append("，代码: ").append(stock.getCode())
                .append("，市场: ").append(str(stock.getMarket()))
                .append("，行业: ").append(str(stock.getIndustry())).append("\n");
        stockCompanyRepo.findByStockId(stock.getId()).ifPresent(c -> {
            sb.append("全称: ").append(str(c.getCompanyName()))
                    .append("，法人: ").append(str(c.getLegalRepresentative()))
                    .append("，上市日期: ").append(c.getListDate() != null ? c.getListDate() : "").append("\n");
            sb.append("主营业务: ").append(str(c.getMainBusiness())).append("\n");
            if (notBlank(c.getCompanyProfile())) {
                String profile = c.getCompanyProfile();
                sb.append("公司简介: ").append(profile.length() > 300
                        ? profile.substring(0, 300) + "..." : profile).append("\n");
            }
        });
        return sb.toString().trim();
    }

    // ────────── 内部工具 ──────────

    private String safeBuild(String kind, java.util.function.Supplier<String> supplier) {
        try {
            String text = supplier.get();
            return notBlank(text) ? text : NO_DATA;
        } catch (Exception e) {
            log.warn("预注入 [{}] 上下文构建失败，填充占位: {}", kind, e.getMessage());
            return NO_DATA;
        }
    }

    /** 同比期：去年同报告期 */
    private StockFinance findYoy(List<StockFinance> all, StockFinance current) {
        if (current.getReportDate() == null) return null;
        return all.stream()
                .filter(f -> current.getReportDate().minusYears(1).equals(f.getReportDate()))
                .findFirst().orElse(null);
    }

    private String yoyPct(BigDecimal now, BigDecimal prev) {
        if (now == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) return "-";
        BigDecimal pct = now.subtract(prev)
                .divide(prev.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP);
        return (pct.signum() > 0 ? "+" : "") + pct + "%";
    }

    /** klines 按日期降序，取最近 period 根收盘均值（不足返回 0，与 DataContextBuilder 口径一致） */
    private double ma(List<StockKlineDaily> klines, int period) {
        if (klines.size() < period) return 0;
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = 0; i < period; i++) {
            BigDecimal close = klines.get(i).getClose();
            if (close != null) {
                sum = sum.add(close);
                count++;
            }
        }
        return count == 0 ? 0
                : sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP).doubleValue();
    }

    private double avgVolume(List<StockKlineDaily> klines, int period) {
        int n = Math.min(period, klines.size());
        if (n == 0) return 0;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += klines.get(i).getVolume() != null ? klines.get(i).getVolume() : 0;
        }
        return (double) sum / n;
    }

    private BigDecimal maxHigh(List<StockKlineDaily> klines) {
        return klines.stream().map(StockKlineDaily::getHigh).filter(Objects::nonNull)
                .max(BigDecimal::compareTo).orElse(null);
    }

    private BigDecimal minLow(List<StockKlineDaily> klines) {
        return klines.stream().map(StockKlineDaily::getLow).filter(Objects::nonNull)
                .min(BigDecimal::compareTo).orElse(null);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean nonZero(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) != 0;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(String s) {
        return s != null ? s : "";
    }

    private static String num(BigDecimal v) {
        return v != null ? v.stripTrailingZeros().toPlainString() : "-";
    }

    private static String fmt(double v) {
        return v > 0 ? String.valueOf(Math.round(v * 100.0) / 100.0) : "-";
    }

    private static String fmtLong(double v) {
        return String.valueOf(Math.round(v));
    }
}
