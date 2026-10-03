package com.stock.workflow.engine.tools;

import com.stock.entity.StockLhbDetail;
import com.stock.entity.StockZtPool;
import com.stock.repository.StockLhbDetailRepository;
import com.stock.repository.StockZtPoolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 市场工具参数校验与降级文案单元测试：{@link ToolParams#parseDate}、
 * {@link MarketZtTool}、{@link MarketLhbTool}（mock Repository，不连数据库）。
 */
class MarketToolsParamTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);

    private StockZtPoolRepository ztPoolRepo;
    private StockLhbDetailRepository lhbRepo;
    private MarketZtTool ztTool;
    private MarketLhbTool lhbTool;

    @BeforeEach
    void setUp() {
        ztPoolRepo = mock(StockZtPoolRepository.class);
        lhbRepo = mock(StockLhbDetailRepository.class);
        ztTool = new MarketZtTool(ztPoolRepo);
        lhbTool = new MarketLhbTool(lhbRepo);
    }

    // ════════════════ ToolParams.parseDate ════════════════

    @Nested
    @DisplayName("ToolParams.parseDate：宽松日期解析")
    class ParseDate {

        @Test
        @DisplayName("yyyy-MM-dd 与 yyyyMMdd 均可解析")
        void parsesBothFormats() {
            assertThat(ToolParams.parseDate("2026-07-28")).isEqualTo(DATE);
            assertThat(ToolParams.parseDate("20260728")).isEqualTo(DATE);
            assertThat(ToolParams.parseDate(" 2026-07-28 ")).isEqualTo(DATE);
        }

        @Test
        @DisplayName("null / 空串 / 非法格式 → null")
        void invalidReturnsNull() {
            assertThat(ToolParams.parseDate(null)).isNull();
            assertThat(ToolParams.parseDate("  ")).isNull();
            assertThat(ToolParams.parseDate("abc")).isNull();
            assertThat(ToolParams.parseDate("2026/07/28")).isNull();
            assertThat(ToolParams.parseDate("2026-13-40")).isNull();
        }
    }

    // ════════════════ market_zt ════════════════

    @Nested
    @DisplayName("market_zt：涨停池查询")
    class MarketZt {

        @Test
        @DisplayName("date 非法 → 返回参数错误文案")
        void invalidDate() {
            assertThat(ztTool.marketZt("not-a-date", null))
                    .contains("[market_zt] 参数错误").contains("not-a-date");
        }

        @Test
        @DisplayName("库中无任何数据且未指定日期 → 提示暂无数据")
        void emptyDb() {
            when(ztPoolRepo.findMaxTradeDate()).thenReturn(Optional.empty());
            assertThat(ztTool.marketZt(null, null)).contains("暂无涨停池数据");
        }

        @Test
        @DisplayName("指定日期无数据 → 提示并回报最新有数据日期")
        void noDataForDate() {
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(DATE, "TODAY"))
                    .thenReturn(List.of());
            when(ztPoolRepo.findMaxTradeDate()).thenReturn(Optional.of(DATE.minusDays(1)));

            assertThat(ztTool.marketZt("2026-07-28", null))
                    .contains("无 TODAY 涨停池数据")
                    .contains(DATE.minusDays(1).toString());
        }

        @Test
        @DisplayName("正常查询 → 汇总含涨停家数/连板梯队/炸板率/ztStat 明细")
        void happyPath() {
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(DATE, "TODAY"))
                    .thenReturn(List.of(zt("600001", 7, "7/7", 0), zt("600002", 1, "1/1", 2)));

            String out = ztTool.marketZt("20260728", "today");

            assertThat(out).contains("涨停家数: 2")
                    .contains("最高连板: 7板")
                    .contains("连板梯队")
                    .contains("炸板率约 50.0%")
                    .contains("7/7")
                    .contains("600001");
        }

        @Test
        @DisplayName("poolType 宽松解析：P 开头 → PREVIOUS 昨日池")
        void previousPool() {
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(DATE, "PREVIOUS"))
                    .thenReturn(List.of(zt("600003", 2, "2/2", 0)));

            assertThat(ztTool.marketZt("2026-07-28", "previous")).contains("昨日涨停池今日表现");
        }

        private StockZtPool zt(String code, Integer limitUpDays, String ztStat, int openTimes) {
            StockZtPool z = new StockZtPool();
            z.setCode(code);
            z.setName("股" + code);
            z.setTradeDate(DATE);
            z.setPoolType("TODAY");
            z.setLimitUpDays(limitUpDays);
            z.setZtStat(ztStat);
            z.setOpenTimes(openTimes);
            z.setIndustry("行业A");
            return z;
        }
    }

    // ════════════════ market_lhb ════════════════

    @Nested
    @DisplayName("market_lhb：龙虎榜查询")
    class MarketLhb {

        @Test
        @DisplayName("stockCode 非法 → 返回参数错误文案")
        void invalidStockCode() {
            assertThat(lhbTool.marketLhb(null, "茅台"))
                    .contains("[market_lhb] 参数错误").contains("茅台");
        }

        @Test
        @DisplayName("date 非法 → 返回参数错误文案")
        void invalidDate() {
            assertThat(lhbTool.marketLhb("2026年7月", null)).contains("[market_lhb] 参数错误");
        }

        @Test
        @DisplayName("库中无任何数据且未指定日期 → 提示暂无数据")
        void emptyDb() {
            when(lhbRepo.findMaxTradeDate()).thenReturn(Optional.empty());
            assertThat(lhbTool.marketLhb(null, null)).contains("暂无龙虎榜数据");
        }

        @Test
        @DisplayName("按代码查询无记录 → 提示无上榜记录")
        void noRecordForCode() {
            when(lhbRepo.findByCodeOrderByTradeDateDesc("600519")).thenReturn(List.of());
            assertThat(lhbTool.marketLhb(null, "600519")).contains("近期无龙虎榜上榜记录");
        }

        @Test
        @DisplayName("按日期查询 → 净买额降序明细，金额折万元/亿元")
        void byDateHappyPath() {
            when(lhbRepo.findByTradeDateOrderByNetAmountDesc(DATE))
                    .thenReturn(List.of(row("600001", "150000000"), row("600002", "-5000000")));

            String out = lhbTool.marketLhb("2026-07-28", null);

            assertThat(out).contains("上榜记录 2 条")
                    .contains("净买入记录 1 条")
                    .contains("1.50亿")
                    .contains("-500万");
        }

        @Test
        @DisplayName("按代码查询 → 按日期倒序输出该股记录")
        void byCodeHappyPath() {
            when(lhbRepo.findByCodeOrderByTradeDateDesc("600001"))
                    .thenReturn(List.of(row("600001", "80000000")));

            String out = lhbTool.marketLhb("参数被忽略", "600001");

            assertThat(out).contains("个股 600001").contains(DATE.toString()).contains("8000万");
        }

        private StockLhbDetail row(String code, String netAmount) {
            StockLhbDetail r = new StockLhbDetail();
            r.setCode(code);
            r.setName("股" + code);
            r.setTradeDate(DATE);
            r.setNetAmount(new BigDecimal(netAmount));
            r.setBuyAmount(new BigDecimal("200000000"));
            r.setSellAmount(new BigDecimal("50000000"));
            r.setRankReason("日涨幅偏离值达7%");
            r.setInterpretation("游资净买");
            return r;
        }
    }
}
