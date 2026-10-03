package com.stock.service;

import com.stock.entity.StockKlineDaily;
import com.stock.repository.StockKlineDailyRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技术信号实时计算（不入库）：
 * 基于 StockKlineDaily 最近 60 根日K，计算 MA5/MA20 金叉死叉 + 放量信号。
 * <ul>
 *   <li>金叉：昨日 MA5 ≤ MA20 且今日 MA5 &gt; MA20</li>
 *   <li>死叉：昨日 MA5 ≥ MA20 且今日 MA5 &lt; MA20</li>
 *   <li>放量：最新成交量 &gt; 前5日均量的2倍</li>
 * </ul>
 */
@Service
public class TechnicalSignalService {

    /** 放量倍数阈值：量 > 5日均量 × 2 */
    static final double VOLUME_SURGE_RATIO = 2.0;

    private final StockKlineDailyRepository klineRepo;

    public TechnicalSignalService(StockKlineDailyRepository klineRepo) {
        this.klineRepo = klineRepo;
    }

    /** 查询某股票当前技术信号；K线不足 21 根时 available=false */
    public Map<String, Object> getSignals(String code) {
        List<StockKlineDaily> klines = klineRepo.findTop60ByCodeOrderByTradeDateDesc(code);
        return computeSignals(code, klines);
    }

    /**
     * 纯计算逻辑（便于单元测试）。
     *
     * @param klines 按 tradeDate 倒序的日K列表（最新在前）
     */
    public Map<String, Object> computeSignals(String code, List<StockKlineDaily> klines) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);

        // 今日/昨日 MA20 各需 20 根收盘价，故至少 21 根
        if (klines == null || klines.size() < 21) {
            result.put("available", false);
            result.put("message", "K线数据不足（需≥21根日K）");
            return result;
        }

        BigDecimal ma5 = avgClose(klines, 0, 5);
        BigDecimal ma20 = avgClose(klines, 0, 20);
        BigDecimal prevMa5 = avgClose(klines, 1, 5);
        BigDecimal prevMa20 = avgClose(klines, 1, 20);

        boolean goldenCross = prevMa5.compareTo(prevMa20) <= 0 && ma5.compareTo(ma20) > 0;
        boolean deathCross = prevMa5.compareTo(prevMa20) >= 0 && ma5.compareTo(ma20) < 0;

        // 放量：最新量 > 前5日（不含当日）均量 × 2
        Long latestVolume = klines.get(0).getVolume();
        Double avgVolume5 = avgVolume(klines, 1, 5);
        boolean volumeSurge = latestVolume != null && avgVolume5 != null && avgVolume5 > 0
                && latestVolume > avgVolume5 * VOLUME_SURGE_RATIO;

        result.put("available", true);
        result.put("tradeDate", klines.get(0).getTradeDate());
        result.put("close", klines.get(0).getClose());
        result.put("ma5", ma5);
        result.put("ma20", ma20);
        result.put("prevMa5", prevMa5);
        result.put("prevMa20", prevMa20);
        result.put("goldenCross", goldenCross);
        result.put("deathCross", deathCross);
        result.put("volume", latestVolume);
        result.put("avgVolume5", avgVolume5);
        result.put("volumeSurge", volumeSurge);
        // MA5 相对 MA20 的当前位置，供前端展示多空排列
        result.put("maTrend", ma5.compareTo(ma20) > 0 ? "BULLISH" : ma5.compareTo(ma20) < 0 ? "BEARISH" : "FLAT");
        return result;
    }

    /** 从 offset 开始取 n 根K线的收盘价均值（列表为倒序） */
    private BigDecimal avgClose(List<StockKlineDaily> klines, int offset, int n) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = offset; i < offset + n; i++) {
            BigDecimal close = klines.get(i).getClose();
            if (close == null) {
                close = BigDecimal.ZERO;
            }
            sum = sum.add(close);
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    /** 从 offset 开始取 n 根K线的成交量均值；量全为 null 时返回 null */
    private Double avgVolume(List<StockKlineDaily> klines, int offset, int n) {
        long sum = 0;
        int count = 0;
        for (int i = offset; i < offset + n && i < klines.size(); i++) {
            Long v = klines.get(i).getVolume();
            if (v != null) {
                sum += v;
                count++;
            }
        }
        return count > 0 ? (double) sum / count : null;
    }
}
