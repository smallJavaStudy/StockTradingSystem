package com.stock.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stock.entity.*;
import com.stock.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class DataContextBuilder {

    @Autowired private StockCompanyRepository stockCompanyRepo;
    @Autowired private StockFinanceRepository financeRepo;
    @Autowired private StockIndustryChainRepository industryChainRepo;
    @Autowired private StockProductBreakdownRepository productBreakdownRepo;
    @Autowired private StockCompetitorRepository competitorRepo;
    @Autowired private StockKlineDailyRepository klineRepo;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String buildDataContext(StockBasic stock) {
        return buildDataContextJson(stock).toPrettyString();
    }

    public ObjectNode buildDataContextJson(StockBasic stock) {
        Long stockId = stock.getId();
        String code = stock.getCode();
        ObjectNode ctx = MAPPER.createObjectNode();

        // 1. 公司基本信息
        ObjectNode companyNode = ctx.putObject("company_basic");
        companyNode.put("name", Objects.toString(stock.getName(), ""));
        companyNode.put("code", stock.getCode());
        companyNode.put("market", Objects.toString(stock.getMarket(), ""));
        companyNode.put("industry", Objects.toString(stock.getIndustry(), ""));

        Optional<StockCompany> companyOpt = stockCompanyRepo.findByStockId(stockId);
        if (companyOpt.isPresent()) {
            StockCompany c = companyOpt.get();
            companyNode.put("full_name", Objects.toString(c.getCompanyName(), ""));
            companyNode.put("main_business", Objects.toString(c.getMainBusiness(), ""));
            companyNode.put("company_profile", abbreviate(c.getCompanyProfile(), 300));
            companyNode.put("legal_representative", Objects.toString(c.getLegalRepresentative(), ""));
            companyNode.put("website", Objects.toString(c.getWebsite(), ""));
            companyNode.put("list_date", c.getListDate() != null ? c.getListDate().toString() : "");
        }

        // 2. 产业链定位
        Optional<StockIndustryChain> chainOpt = industryChainRepo.findByStockId(stockId);
        if (chainOpt.isPresent()) {
            StockIndustryChain ch = chainOpt.get();
            ObjectNode chainNode = ctx.putObject("industry_chain");
            chainNode.put("core_product", Objects.toString(ch.getCoreProduct(), ""));
            chainNode.put("industry_position", Objects.toString(ch.getIndustryPosition(), ""));
            chainNode.put("upstream", Objects.toString(ch.getUpstream(), ""));
            chainNode.put("downstream", Objects.toString(ch.getDownstream(), ""));
            chainNode.put("key_customers", Objects.toString(ch.getKeyCustomers(), ""));
            chainNode.put("key_suppliers", Objects.toString(ch.getKeySuppliers(), ""));
            chainNode.put("lifecycle_stage", Objects.toString(ch.getLifecycleStage(), ""));
            chainNode.put("lifecycle_note", Objects.toString(ch.getLifecycleNote(), ""));
            chainNode.put("tech_route", Objects.toString(ch.getTechRoute(), ""));
            chainNode.put("industry_trend", Objects.toString(ch.getIndustryTrend(), ""));
            chainNode.put("industry_size", Objects.toString(ch.getIndustrySize(), ""));
            chainNode.put("industry_growth", Objects.toString(ch.getIndustryGrowth(), ""));
            chainNode.put("policy_impact", Objects.toString(ch.getPolicyImpact(), ""));
        }

        // 3. 主营产品构成
        List<StockProductBreakdown> products = productBreakdownRepo.findByStockIdOrderByReportDateDesc(stockId);
        if (!products.isEmpty()) {
            ArrayNode prodArr = ctx.putArray("products");
            for (StockProductBreakdown p : products) {
                ObjectNode pn = prodArr.addObject();
                pn.put("name", Objects.toString(p.getProductName(), ""));
                pn.put("category", Objects.toString(p.getProductCategory(), ""));
                if (p.getRevenueRatio() != null) pn.put("revenue_ratio", p.getRevenueRatio().setScale(1, RoundingMode.HALF_UP).doubleValue());
                if (p.getGrossMargin() != null) pn.put("gross_margin", p.getGrossMargin().setScale(1, RoundingMode.HALF_UP).doubleValue());
                if (p.getRevenueYoy() != null) pn.put("revenue_yoy", p.getRevenueYoy().setScale(1, RoundingMode.HALF_UP).doubleValue());
                pn.put("competitiveness", Objects.toString(p.getCompetitiveness(), ""));
                pn.put("report_date", p.getReportDate() != null ? p.getReportDate().toString() : "");
            }
        }

        // 4. 竞品对比
        List<StockCompetitor> competitors = competitorRepo.findByStockId(stockId);
        if (!competitors.isEmpty()) {
            ArrayNode compArr = ctx.putArray("competitors");
            for (StockCompetitor cp : competitors) {
                ObjectNode cn = compArr.addObject();
                cn.put("name", Objects.toString(cp.getCompetitorName(), ""));
                cn.put("code", Objects.toString(cp.getCompetitorCode(), ""));
                cn.put("exchange", Objects.toString(cp.getCompetitorExchange(), ""));
                cn.put("main_product", Objects.toString(cp.getCompetitorMainProduct(), ""));
                if (cp.getCompetitorMarketCap() != null) cn.put("market_cap_yuan", cp.getCompetitorMarketCap());
                if (cp.getCompetitorRevenue() != null) cn.put("revenue_yuan", cp.getCompetitorRevenue().doubleValue());
                if (cp.getCompetitorNetProfit() != null) cn.put("net_profit_yuan", cp.getCompetitorNetProfit().doubleValue());
                if (cp.getCompetitorGrossMargin() != null) cn.put("gross_margin", cp.getCompetitorGrossMargin().setScale(1, RoundingMode.HALF_UP).doubleValue());
                if (cp.getCompetitorRoe() != null) cn.put("roe", cp.getCompetitorRoe().setScale(1, RoundingMode.HALF_UP).doubleValue());
                cn.put("scarcity", Objects.toString(cp.getScarcity(), ""));
                cn.put("moat", Objects.toString(cp.getMoat(), ""));
                cn.put("advantage", Objects.toString(cp.getAdvantage(), ""));
                cn.put("disadvantage", Objects.toString(cp.getDisadvantage(), ""));
            }
        }

        // 5. 财务数据（最近4个季度）
        List<StockFinance> finances = financeRepo.findTop4ByCodeOrderByReportDateDesc(code);
        if (!finances.isEmpty()) {
            ArrayNode finArr = ctx.putArray("finance_quarters");
            for (StockFinance f : finances) {
                ObjectNode fn = finArr.addObject();
                fn.put("report_date", f.getReportDate() != null ? f.getReportDate().toString() : "");
                if (f.getTotalRevenue() != null) fn.put("revenue_yuan", f.getTotalRevenue().doubleValue());
                if (f.getNetProfit() != null) fn.put("net_profit_yuan", f.getNetProfit().doubleValue());
                if (f.getBasicEps() != null) fn.put("eps", f.getBasicEps().doubleValue());
                if (f.getWeightedRoe() != null) fn.put("roe", f.getWeightedRoe().doubleValue());
            }
        }

        // 6. K线明细（最近60根日K，含MA计算）
        List<StockKlineDaily> klines = klineRepo.findTop60ByCodeOrderByTradeDateDesc(code);
        if (!klines.isEmpty()) {
            // Market summary
            ObjectNode mktNode = ctx.putObject("market_summary");
            mktNode.put("data_days", klines.size());
            StockKlineDaily latest = klines.get(0);
            mktNode.put("latest_price", latest.getClose() != null ? latest.getClose().doubleValue() : 0);
            mktNode.put("latest_date", latest.getTradeDate() != null ? latest.getTradeDate().toString() : "");
            if (klines.size() >= 2) {
                StockKlineDaily first = klines.get(klines.size() - 1);
                if (first.getClose() != null && latest.getClose() != null && first.getClose().compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal change = latest.getClose().subtract(first.getClose())
                            .divide(first.getClose(), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    mktNode.put("change_60d_pct", change.setScale(1, RoundingMode.HALF_UP).doubleValue());
                }
            }
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal high = BigDecimal.ZERO;
            BigDecimal low = new BigDecimal("999999999");
            BigDecimal volSum = BigDecimal.ZERO;
            for (StockKlineDaily k : klines) {
                if (k.getClose() != null) {
                    sum = sum.add(k.getClose());
                    if (k.getClose().compareTo(high) > 0) high = k.getClose();
                    if (k.getClose().compareTo(low) < 0) low = k.getClose();
                }
                if (k.getVolume() != null) volSum = volSum.add(BigDecimal.valueOf(k.getVolume()));
            }
            mktNode.put("avg_60d", sum.divide(BigDecimal.valueOf(klines.size()), 2, RoundingMode.HALF_UP).doubleValue());
            mktNode.put("high_60d", high.doubleValue());
            mktNode.put("low_60d", low.doubleValue());
            mktNode.put("avg_volume_60d", volSum.divide(BigDecimal.valueOf(klines.size()), 0, RoundingMode.HALF_UP).longValue());

            // Individual K-line bars (most recent 60, oldest→newest)
            ArrayNode barArr = ctx.putArray("kline_bars");
            for (int i = klines.size() - 1; i >= 0; i--) {
                StockKlineDaily k = klines.get(i);
                ObjectNode bar = barArr.addObject();
                bar.put("date", k.getTradeDate() != null ? k.getTradeDate().toString() : "");
                bar.put("open", k.getOpen() != null ? k.getOpen().doubleValue() : 0);
                bar.put("high", k.getHigh() != null ? k.getHigh().doubleValue() : 0);
                bar.put("low", k.getLow() != null ? k.getLow().doubleValue() : 0);
                bar.put("close", k.getClose() != null ? k.getClose().doubleValue() : 0);
                bar.put("volume", k.getVolume() != null ? k.getVolume().doubleValue() : 0);
            }

            // Pre-computed MAs for the most recent bars
            ArrayNode maArr = ctx.putArray("ma_values");
            int count = Math.min(klines.size(), 60);
            for (int i = 0; i < count; i++) {
                ObjectNode maNode = maArr.addObject();
                int idx = klines.size() - 1 - i;
                if (idx < 0) break;
                StockKlineDaily k = klines.get(idx);
                maNode.put("date", k.getTradeDate() != null ? k.getTradeDate().toString() : "");
                if (k.getClose() != null) {
                    maNode.put("close", k.getClose().doubleValue());
                    maNode.put("ma5", calcMA(klines, idx, 5));
                    maNode.put("ma10", calcMA(klines, idx, 10));
                    maNode.put("ma20", calcMA(klines, idx, 20));
                    maNode.put("ma60", calcMA(klines, idx, 60));
                }
            }

            // Pre-computed indicators: MACD(12,26,9), KDJ(9,3,3), RSI(14)
            ArrayNode indArr = ctx.putArray("indicators_daily");
            computeIndicators(klines, indArr);
        }

        return ctx;
    }

    private static String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /** Calculate Simple Moving Average from {@code idx} backwards over {@code period} bars */
    private static double calcMA(List<StockKlineDaily> klines, int fromIdx, int period) {
        if (fromIdx < period - 1) return 0;
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (int i = fromIdx; i > fromIdx - period && i >= 0; i--) {
            StockKlineDaily k = klines.get(i);
            if (k.getClose() != null) {
                sum = sum.add(k.getClose());
                count++;
            }
        }
        if (count == 0) return 0;
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Compute EMA */
    private static double ema(List<Double> closes, int period) {
        if (closes.size() < period) return closes.get(closes.size() - 1);
        double multiplier = 2.0 / (period + 1);
        double ema = closes.get(0);
        for (int i = 1; i < period; i++) {
            ema = closes.get(i);
        }
        for (int i = period; i < closes.size(); i++) {
            ema = (closes.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }

    /** Compute MACD, KDJ, RSI for all bars and write to the array */
    private void computeIndicators(List<StockKlineDaily> klines, ArrayNode out) {
        int n = klines.size();
        if (n < 26) return;

        // Build close price array (oldest→newest)
        List<Double> closes = new ArrayList<>(n);
        List<Double> highs = new ArrayList<>(n);
        List<Double> lows = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            StockKlineDaily k = klines.get(i);
            closes.add(k.getClose() != null ? k.getClose().doubleValue() : 0);
            highs.add(k.getHigh() != null ? k.getHigh().doubleValue() : 0);
            lows.add(k.getLow() != null ? k.getLow().doubleValue() : 0);
        }

        // MACD(12,26,9)
        double[] ema12 = new double[n];
        double[] ema26 = new double[n];
        double[] dif = new double[n];
        double[] dea = new double[n];
        double[] macd = new double[n];
        double mul12 = 2.0 / 13, mul26 = 2.0 / 27, mul9 = 2.0 / 10;

        for (int i = 0; i < n; i++) {
            if (i == 0) {
                ema12[i] = closes.get(i);
                ema26[i] = closes.get(i);
            } else {
                ema12[i] = (closes.get(i) - ema12[i - 1]) * mul12 + ema12[i - 1];
                ema26[i] = (closes.get(i) - ema26[i - 1]) * mul26 + ema26[i - 1];
            }
            dif[i] = ema12[i] - ema26[i];
            if (i == 0) {
                dea[i] = dif[i];
            } else {
                dea[i] = (dif[i] - dea[i - 1]) * mul9 + dea[i - 1];
            }
            macd[i] = (dif[i] - dea[i]) * 2;
        }

        // KDJ(9,3,3) — simplified
        double[] kVal = new double[n];
        double[] dVal = new double[n];
        double[] jVal = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < 8) {
                kVal[i] = 50; dVal[i] = 50; jVal[i] = 50;
            } else {
                double hh = highs.subList(i - 8, i + 1).stream().mapToDouble(v -> v).max().orElse(0);
                double ll = lows.subList(i - 8, i + 1).stream().mapToDouble(v -> v).min().orElse(0);
                double rsv = hh == ll ? 50 : (closes.get(i) - ll) / (hh - ll) * 100;
                if (i == 8) {
                    kVal[i] = rsv * 1.0 / 3 + 50 * 2.0 / 3;
                    dVal[i] = kVal[i] * 1.0 / 3 + 50 * 2.0 / 3;
                } else {
                    kVal[i] = rsv * 1.0 / 3 + kVal[i - 1] * 2.0 / 3;
                    dVal[i] = kVal[i] * 1.0 / 3 + dVal[i - 1] * 2.0 / 3;
                }
                jVal[i] = 3 * kVal[i] - 2 * dVal[i];
            }
        }

        // RSI(14)
        double[] rsi = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < 14) {
                rsi[i] = 50;
            } else {
                double gain = 0, loss = 0;
                for (int j = i - 13; j <= i; j++) {
                    double chg = closes.get(j) - closes.get(j - 1);
                    if (chg > 0) gain += chg; else loss -= chg;
                }
                double avgGain = gain / 14, avgLoss = loss / 14;
                rsi[i] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));
            }
        }

        // Write results (most recent first)
        for (int i = n - 1; i >= Math.max(0, n - 60); i--) {
            ObjectNode node = out.addObject();
            StockKlineDaily k = klines.get(i);
            node.put("date", k.getTradeDate() != null ? k.getTradeDate().toString() : "");
            node.put("macd_dif", round2(dif[i]));
            node.put("macd_dea", round2(dea[i]));
            node.put("macd_bar", round2(macd[i]));
            node.put("kdj_k", round1(kVal[i]));
            node.put("kdj_d", round1(dVal[i]));
            node.put("kdj_j", round1(jVal[i]));
            node.put("rsi_14", round1(rsi[i]));
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
