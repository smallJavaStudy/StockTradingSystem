package com.stock.service;

import com.stock.entity.StockKlineDaily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KimiKlineService 真实联通性测试（依赖外网，默认跳过）。
 * <p>手动运行：mvn test "-Dtest=KimiKlineServiceIT" -Dkimi-kline.it=true
 */
@EnabledIfSystemProperty(named = "kimi-kline.it", matches = "true")
class KimiKlineServiceIT {

    private final KimiKlineService svc = new KimiKlineService("", "", "kimi-for-coding");

    @Test
    void tencentKlineMatchesExpected() {
        List<StockKlineDaily> rows = svc.fetchFromTencent("600036", 22);
        System.out.println("=== 腾讯K线 " + rows.size() + " 条 ===");
        rows.forEach(k -> System.out.println(k.getTradeDate() + " O=" + k.getOpen()
                + " C=" + k.getClose() + " V=" + k.getVolume()));
        assertTrue(rows.size() >= 20, "至少20个交易日，实际 " + rows.size());
        StockKlineDaily last = rows.get(rows.size() - 1);
        // 2026-08-07 招商银行收盘 38.80（东财/Serper 交叉验证过）
        assertEquals(0, new BigDecimal("38.80").compareTo(last.getClose()), "最后一日收盘应为 38.80");
        assertTrue(last.getVolume() > 0);
    }

    @Test
    void sinaKlineMatchesExpected() {
        List<StockKlineDaily> rows = svc.fetchFromSina("600036", 22);
        System.out.println("=== 新浪K线 " + rows.size() + " 条 ===");
        assertTrue(rows.size() >= 20, "至少20个交易日，实际 " + rows.size());
        StockKlineDaily last = rows.get(rows.size() - 1);
        assertEquals(0, new BigDecimal("38.80").compareTo(last.getClose()), "最后一日收盘应为 38.80");
        // 新浪 volume 单位为股，归一后应与腾讯（手）同量级
        assertTrue(last.getVolume() > 100000, "volume 归一为手后应 >10万，实际 " + last.getVolume());
    }
}
