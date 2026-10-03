package com.stock.service;

import com.stock.entity.StockKlineDaily;
import com.stock.repository.StockKlineDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link TechnicalSignalService} 单元测试：直接调用 computeSignals 纯函数（无需 mock 数据库），
 * 覆盖数据不足、金叉、死叉、放量、多空排列判定。
 */
class TechnicalSignalServiceTest {

    private static final String CODE = "300364";

    private TechnicalSignalService service;

    @BeforeEach
    void setUp() {
        service = new TechnicalSignalService(mock(StockKlineDailyRepository.class));
    }

    /** 构造一根日K（列表约定倒序：index 0 = 最新交易日） */
    private StockKlineDaily kline(int daysAgo, double close, long volume) {
        StockKlineDaily k = new StockKlineDaily();
        k.setCode(CODE);
        k.setTradeDate(LocalDate.of(2026, 7, 28).minusDays(daysAgo));
        k.setClose(BigDecimal.valueOf(close));
        k.setVolume(volume);
        return k;
    }

    /** 生成倒序K线：closes[0] 为最新收盘价，成交量统一 */
    private List<StockKlineDaily> klines(double[] closes, long volume) {
        List<StockKlineDaily> list = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            list.add(kline(i, closes[i], volume));
        }
        return list;
    }

    // ─────────────────── 数据不足 ───────────────────

    @Test
    @DisplayName("K线不足21根：available=false 并给出提示")
    void insufficientData_unavailable() {
        Map<String, Object> r1 = service.computeSignals(CODE, Collections.emptyList());
        assertThat(r1.get("available")).isEqualTo(false);

        double[] closes = new double[20];
        java.util.Arrays.fill(closes, 10.0);
        Map<String, Object> r2 = service.computeSignals(CODE, klines(closes, 100000));
        assertThat(r2.get("available")).isEqualTo(false);
        assertThat((String) r2.get("message")).contains("21");
    }

    // ─────────────────── 金叉 / 死叉 ───────────────────

    @Test
    @DisplayName("金叉：昨日 MA5≤MA20 且今日 MA5>MA20")
    void goldenCross_detected() {
        // 30根倒序：前段长期横盘10元，最新一日大涨到16 → MA5 上穿 MA20
        double[] closes = new double[30];
        java.util.Arrays.fill(closes, 10.0);
        closes[0] = 16.0;
        Map<String, Object> r = service.computeSignals(CODE, klines(closes, 100000));

        assertThat(r.get("available")).isEqualTo(true);
        assertThat(r.get("goldenCross")).isEqualTo(true);
        assertThat(r.get("deathCross")).isEqualTo(false);
        assertThat(r.get("maTrend")).isEqualTo("BULLISH");
        // MA5=(16+10*4)/5=11.2, MA20=(16+10*19)/20=10.3
        assertThat((BigDecimal) r.get("ma5")).isEqualByComparingTo("11.2");
        assertThat((BigDecimal) r.get("ma20")).isEqualByComparingTo("10.3");
    }

    @Test
    @DisplayName("死叉：昨日 MA5≥MA20 且今日 MA5<MA20")
    void deathCross_detected() {
        // 横盘10元，最新一日暴跌到4 → MA5 下穿 MA20
        double[] closes = new double[30];
        java.util.Arrays.fill(closes, 10.0);
        closes[0] = 4.0;
        Map<String, Object> r = service.computeSignals(CODE, klines(closes, 100000));

        assertThat(r.get("goldenCross")).isEqualTo(false);
        assertThat(r.get("deathCross")).isEqualTo(true);
        assertThat(r.get("maTrend")).isEqualTo("BEARISH");
    }

    @Test
    @DisplayName("持续横盘：无金叉无死叉，均线粘合 FLAT")
    void flat_noCross() {
        double[] closes = new double[30];
        java.util.Arrays.fill(closes, 10.0);
        Map<String, Object> r = service.computeSignals(CODE, klines(closes, 100000));

        assertThat(r.get("goldenCross")).isEqualTo(false);
        assertThat(r.get("deathCross")).isEqualTo(false);
        assertThat(r.get("maTrend")).isEqualTo("FLAT");
    }

    @Test
    @DisplayName("已在多头排列中继续上涨：不再重复报金叉")
    void alreadyBullish_noRepeatedGoldenCross() {
        // 线性上涨序列：MA5 一直在 MA20 上方（昨日已 >，不满足金叉条件）
        double[] closes = new double[30];
        for (int i = 0; i < 30; i++) {
            closes[i] = 20.0 - i * 0.2; // 倒序：最新价最高
        }
        Map<String, Object> r = service.computeSignals(CODE, klines(closes, 100000));

        assertThat(r.get("goldenCross")).isEqualTo(false);
        assertThat(r.get("maTrend")).isEqualTo("BULLISH");
    }

    // ─────────────────── 放量 ───────────────────

    @Test
    @DisplayName("放量：最新量 > 前5日均量2倍")
    void volumeSurge_detected() {
        double[] closes = new double[30];
        java.util.Arrays.fill(closes, 10.0);
        List<StockKlineDaily> list = klines(closes, 100000);
        list.get(0).setVolume(200001L); // 前5日均量 100000，2倍界=200000，200001 触发

        Map<String, Object> r = service.computeSignals(CODE, list);

        assertThat(r.get("volumeSurge")).isEqualTo(true);
        assertThat((Double) r.get("avgVolume5")).isEqualTo(100000.0);
    }

    @Test
    @DisplayName("恰好2倍均量：不算放量（严格大于）")
    void volumeExactlyDouble_notSurge() {
        double[] closes = new double[30];
        java.util.Arrays.fill(closes, 10.0);
        List<StockKlineDaily> list = klines(closes, 100000);
        list.get(0).setVolume(200000L);

        Map<String, Object> r = service.computeSignals(CODE, list);

        assertThat(r.get("volumeSurge")).isEqualTo(false);
    }

    @Test
    @DisplayName("成交量缺失：volumeSurge=false 不抛异常")
    void nullVolume_noSurge() {
        double[] closes = new double[30];
        java.util.Arrays.fill(closes, 10.0);
        List<StockKlineDaily> list = klines(closes, 100000);
        list.forEach(k -> k.setVolume(null));

        Map<String, Object> r = service.computeSignals(CODE, list);

        assertThat(r.get("available")).isEqualTo(true);
        assertThat(r.get("volumeSurge")).isEqualTo(false);
    }
}
