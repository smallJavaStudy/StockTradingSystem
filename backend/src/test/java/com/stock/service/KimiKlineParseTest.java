package com.stock.service;

import com.stock.entity.StockKlineDaily;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link KimiKlineService} 纯解析逻辑单测：Kimi 兜底返回的固定 CSV 解析 + http_get 白名单。
 * 用例数据取自 2026-08-08 Kimi agent loop 实测产出（600036 招商银行）。
 */
class KimiKlineParseTest {

    @Test
    void parsesKimiFinalCsv() {
        String csv = """
                2026-07-09,36.70,36.55,36.81,36.37,765646
                2026-07-10,36.61,36.88,36.92,36.33,793772
                2026-08-07,38.90,38.80,39.10,38.48,779647
                """;
        List<StockKlineDaily> rows = KimiKlineService.parseCsv(csv);
        assertEquals(3, rows.size());
        StockKlineDaily last = rows.get(2);
        assertEquals("2026-08-07", last.getTradeDate().toString());
        assertEquals(new BigDecimal("38.90"), last.getOpen());
        assertEquals(new BigDecimal("38.80"), last.getClose());
        assertEquals(new BigDecimal("39.10"), last.getHigh());
        assertEquals(new BigDecimal("38.48"), last.getLow());
        assertEquals(779647L, last.getVolume());
    }

    @Test
    void toleratesCodeFenceAndHeaderText() {
        String text = """
                以下是数据：
                ```csv
                date,open,close,high,low,volume
                2026-08-07,38.90,38.80,39.10,38.48,779647
                ```
                """;
        List<StockKlineDaily> rows = KimiKlineService.parseCsv(text);
        // 表头/说明行被逐行校验丢弃，仅数据行入库
        assertEquals(1, rows.size());
        assertEquals("2026-08-07", rows.get(0).getTradeDate().toString());
    }

    @Test
    void returnsEmptyForBlankOrGarbage() {
        assertTrue(KimiKlineService.parseCsv(null).isEmpty());
        assertTrue(KimiKlineService.parseCsv("").isEmpty());
        assertTrue(KimiKlineService.parseCsv("我无法获取数据").isEmpty());
        assertTrue(KimiKlineService.parseCsv("2026-13-99,bad,row,x,y,z").isEmpty());
    }

    @Test
    void hostWhitelistAllowsQuoteDomainsOnly() {
        assertTrue(KimiKlineService.allowedHost(
                "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sh600036,day,,,22,qfq"));
        assertTrue(KimiKlineService.allowedHost(
                "https://quotes.sina.cn/cn/api/jsonp_v2.php/var/CN_MarketDataService.getKLineData?symbol=sh600036"));
        assertTrue(KimiKlineService.allowedHost(
                "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=1.600036"));
        assertFalse(KimiKlineService.allowedHost("https://evil.example.com/x"));
        assertFalse(KimiKlineService.allowedHost("http://web.ifzq.gtimg.cn/x"));   // 非 https
        assertFalse(KimiKlineService.allowedHost("https://gtimg.cn.evil.example.com/x")); // 后缀伪装
        assertFalse(KimiKlineService.allowedHost(null));
    }
}
