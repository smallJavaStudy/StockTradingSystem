package com.stock.controller;

import com.stock.entity.*;
import com.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link MarketDataController} 单元测试：mock Repository（单测允许 mock，运行时数据禁 Mock），
 * 覆盖缺省日期回退、空数据、字段映射契约、排序与 days 归一化。
 */
class MarketDataControllerTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 27);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 28);

    private StockZtPoolRepository ztPoolRepo;
    private StockLhbDetailRepository lhbRepo;
    private StockNorthFlowRepository northFlowRepo;
    private StockMarginDailyRepository marginRepo;
    private StockBlockTradeRepository blockTradeRepo;

    private MarketDataController controller;

    @BeforeEach
    void setUp() {
        ztPoolRepo = mock(StockZtPoolRepository.class);
        lhbRepo = mock(StockLhbDetailRepository.class);
        northFlowRepo = mock(StockNorthFlowRepository.class);
        marginRepo = mock(StockMarginDailyRepository.class);
        blockTradeRepo = mock(StockBlockTradeRepository.class);
        controller = new MarketDataController(ztPoolRepo, lhbRepo, northFlowRepo, marginRepo, blockTradeRepo);
    }

    // ─────────────────── 涨停股池 ───────────────────

    @Nested
    @DisplayName("GET /api/market/zt-pool")
    class ZtPool {

        @Test
        @DisplayName("缺省 date 时回退到最新有数交易日，缺省 poolType=TODAY")
        void defaultsToLatestDate() {
            when(ztPoolRepo.findMaxTradeDate()).thenReturn(Optional.of(D2));
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D2, MarketDataController.POOL_TODAY))
                    .thenReturn(List.of(ztPool("002338", 3)));

            var body = controller.ztPool(null, MarketDataController.POOL_TODAY).getBody();

            assertThat(body).hasSize(1);
            verify(ztPoolRepo).findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D2, "TODAY");
        }

        @Test
        @DisplayName("字段映射契约：13 个字段逐项对齐实体，可空字段返回 null")
        void fieldContract() {
            StockZtPool z = ztPool("002338", 2);
            z.setReason(null);
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D2, "TODAY"))
                    .thenReturn(List.of(z));

            var item = controller.ztPool(D2, "TODAY").getBody().get(0);

            assertThat(item.code()).isEqualTo("002338");
            assertThat(item.name()).isEqualTo("奥普光电");
            assertThat(item.tradeDate()).isEqualTo(D2);
            assertThat(item.closePrice()).isEqualByComparingTo("43.22");
            assertThat(item.changePct()).isEqualByComparingTo("10.00");
            assertThat(item.limitUpDays()).isEqualTo(2);
            assertThat(item.firstTime()).isEqualTo("09:25:00");
            assertThat(item.lastTime()).isEqualTo("09:25:00");
            assertThat(item.openTimes()).isZero();
            assertThat(item.amount()).isEqualByComparingTo("151352939.00");
            assertThat(item.turnoverRate()).isEqualByComparingTo("1.46");
            assertThat(item.industry()).isEqualTo("军工电子");
            assertThat(item.reason()).isNull();
        }

        @Test
        @DisplayName("库中完全无数据：返回空数组而非 404")
        void emptyTable() {
            when(ztPoolRepo.findMaxTradeDate()).thenReturn(Optional.empty());

            var resp = controller.ztPool(null, "TODAY");

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            assertThat(resp.getBody()).isEmpty();
            verify(ztPoolRepo, never()).findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(any(), any());
        }

        @Test
        @DisplayName("poolType=PREVIOUS 透传到仓库查询")
        void previousPool() {
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D2, "PREVIOUS"))
                    .thenReturn(List.of());

            controller.ztPool(D2, MarketDataController.POOL_PREVIOUS);

            verify(ztPoolRepo).findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D2, "PREVIOUS");
        }
    }

    // ─────────────────── 龙虎榜 ───────────────────

    @Nested
    @DisplayName("GET /api/market/lhb")
    class Lhb {

        @Test
        @DisplayName("字段映射契约 + 缺省日期回退")
        void fieldContract() {
            when(lhbRepo.findMaxTradeDate()).thenReturn(Optional.of(D2));
            when(lhbRepo.findByTradeDateOrderByNetAmountDesc(D2)).thenReturn(List.of(lhb("300308")));

            var item = controller.lhb(null).getBody().get(0);

            assertThat(item.code()).isEqualTo("300308");
            assertThat(item.name()).isEqualTo("中际旭创");
            assertThat(item.tradeDate()).isEqualTo(D2);
            assertThat(item.rankReason()).isEqualTo("日跌幅达到15%的前5只证券");
            assertThat(item.buyAmount()).isEqualByComparingTo("10601818000.00");
            assertThat(item.sellAmount()).isEqualByComparingTo("7762920000.00");
            assertThat(item.netAmount()).isEqualByComparingTo("2838898000.00");
            assertThat(item.totalAmount()).isEqualByComparingTo("18364738000.00");
            assertThat(item.changePct()).isEqualByComparingTo("-15.69");
        }

        @Test
        @DisplayName("无数据：返回空数组")
        void empty() {
            when(lhbRepo.findMaxTradeDate()).thenReturn(Optional.empty());
            assertThat(controller.lhb(null).getBody()).isEmpty();
        }
    }

    // ─────────────────── 北向资金 ───────────────────

    @Nested
    @DisplayName("GET /api/market/north-flow")
    class NorthFlow {

        @Test
        @DisplayName("按日期升序返回，单位亿元；days 传入 Pageable 限制条数")
        void ascendingOrder() {
            when(northFlowRepo.findByOrderByTradeDateDesc(any(Pageable.class)))
                    .thenReturn(List.of(northFlow(D2, "-67.7499", "17615.2249"),
                            northFlow(D1, "122.0584", "17682.9748")));

            var body = controller.northFlow(2).getBody();

            assertThat(body).hasSize(2);
            assertThat(body.get(0).tradeDate()).isEqualTo(D1);
            assertThat(body.get(1).tradeDate()).isEqualTo(D2);
            assertThat(body.get(0).netFlow()).isEqualByComparingTo("122.0584");
            assertThat(body.get(1).accumFlow()).isEqualByComparingTo("17615.2249");
            verify(northFlowRepo).findByOrderByTradeDateDesc(eq(PageRequest.of(0, 2)));
        }

        @Test
        @DisplayName("days<=0 归一化为 30，days 过大截断到 500")
        void daysNormalized() {
            when(northFlowRepo.findByOrderByTradeDateDesc(any(Pageable.class))).thenReturn(List.of());

            controller.northFlow(0);
            controller.northFlow(9999);

            verify(northFlowRepo).findByOrderByTradeDateDesc(eq(PageRequest.of(0, 30)));
            verify(northFlowRepo).findByOrderByTradeDateDesc(eq(PageRequest.of(0, 500)));
        }
    }

    // ─────────────────── 融资融券 ───────────────────

    @Nested
    @DisplayName("GET /api/market/margin")
    class Margin {

        @Test
        @DisplayName("按最近 N 个交易日取沪深两市，日期升序、同日 SH 在前")
        void twoMarketsSorted() {
            when(marginRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D2, D1));
            when(marginRepo.findByTradeDateInOrderByTradeDateDescMarketAsc(List.of(D2, D1)))
                    .thenReturn(List.of(margin(D2, "SZ"), margin(D2, "SH"), margin(D1, "SH"), margin(D1, "SZ")));

            var body = controller.margin(2).getBody();

            assertThat(body).extracting(MarketDataController.MarginItem::tradeDate,
                            MarketDataController.MarginItem::market)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(D1, "SH"),
                            org.assertj.core.groups.Tuple.tuple(D1, "SZ"),
                            org.assertj.core.groups.Tuple.tuple(D2, "SH"),
                            org.assertj.core.groups.Tuple.tuple(D2, "SZ"));
            assertThat(body.get(0).financingBalance()).isEqualByComparingTo("1349012000000.00");
            assertThat(body.get(0).totalBalance()).isEqualByComparingTo("1363335000000.00");
        }

        @Test
        @DisplayName("无交易日数据：返回空数组且不再查明细")
        void empty() {
            when(marginRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of());

            assertThat(controller.margin(30).getBody()).isEmpty();
            verify(marginRepo, never()).findByTradeDateInOrderByTradeDateDescMarketAsc(any());
        }
    }

    // ─────────────────── 大宗交易 ───────────────────

    @Nested
    @DisplayName("GET /api/market/block-trade")
    class BlockTrade {

        @Test
        @DisplayName("字段映射契约：折价为负 premiumRate，volume 单位股")
        void fieldContract() {
            when(blockTradeRepo.findByTradeDateOrderByAmountDesc(D2)).thenReturn(List.of(blockTrade("300700")));

            var item = controller.blockTrade(D2).getBody().get(0);

            assertThat(item.code()).isEqualTo("300700");
            assertThat(item.name()).isEqualTo("岱勒新材");
            assertThat(item.tradeDate()).isEqualTo(D2);
            assertThat(item.price()).isEqualByComparingTo("14.20");
            assertThat(item.volume()).isEqualTo(3893700L);
            assertThat(item.amount()).isEqualByComparingTo("55290540.00");
            assertThat(item.premiumRate()).isEqualByComparingTo("-0.28");
            assertThat(item.buyerBranch()).isEqualTo("东方证券股份有限公司上海浦东新区北蔡证券营业部");
            assertThat(item.sellerBranch()).isNull();
        }

        @Test
        @DisplayName("缺省日期回退到最新有数交易日")
        void defaultDate() {
            when(blockTradeRepo.findMaxTradeDate()).thenReturn(Optional.of(D1));
            when(blockTradeRepo.findByTradeDateOrderByAmountDesc(D1)).thenReturn(List.of());

            controller.blockTrade(null);

            verify(blockTradeRepo).findByTradeDateOrderByAmountDesc(D1);
        }
    }

    // ─────────────────── 交易日列表 ───────────────────

    @Nested
    @DisplayName("GET /api/market/dates")
    class Dates {

        @Test
        @DisplayName("type 路由到对应仓库，限制 30 条，倒序原样返回")
        void routing() {
            when(ztPoolRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D2, D1));
            when(lhbRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D2));
            when(blockTradeRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D1));
            when(marginRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D2, D1));

            assertThat(controller.dates("zt").getBody()).containsExactly(D2, D1);
            assertThat(controller.dates("lhb").getBody()).containsExactly(D2);
            assertThat(controller.dates("block").getBody()).containsExactly(D1);
            assertThat(controller.dates("margin").getBody()).containsExactly(D2, D1);
            verify(ztPoolRepo).findDistinctTradeDates(eq(PageRequest.of(0, 30)));
        }

        @Test
        @DisplayName("type 缺省/未知：回退到涨停池")
        void defaultType() {
            when(ztPoolRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D2));

            assertThat(controller.dates(null).getBody()).containsExactly(D2);
            assertThat(controller.dates("unknown").getBody()).containsExactly(D2);
        }
    }

    // ─────────────────── 测试数据构造 ───────────────────

    private static StockZtPool ztPool(String code, int limitUpDays) {
        StockZtPool z = new StockZtPool();
        z.setCode(code);
        z.setName("奥普光电");
        z.setTradeDate(D2);
        z.setPoolType(MarketDataController.POOL_TODAY);
        z.setClosePrice(new BigDecimal("43.22"));
        z.setChangePct(new BigDecimal("10.00"));
        z.setLimitUpDays(limitUpDays);
        z.setFirstTime("09:25:00");
        z.setLastTime("09:25:00");
        z.setOpenTimes(0);
        z.setAmount(new BigDecimal("151352939.00"));
        z.setTurnoverRate(new BigDecimal("1.46"));
        z.setIndustry("军工电子");
        z.setZtStat("1/1");
        return z;
    }

    private static StockLhbDetail lhb(String code) {
        StockLhbDetail l = new StockLhbDetail();
        l.setCode(code);
        l.setName("中际旭创");
        l.setTradeDate(D2);
        l.setRankReason("日跌幅达到15%的前5只证券");
        l.setBuyAmount(new BigDecimal("10601818000.00"));
        l.setSellAmount(new BigDecimal("7762920000.00"));
        l.setNetAmount(new BigDecimal("2838898000.00"));
        l.setTotalAmount(new BigDecimal("18364738000.00"));
        l.setChangePct(new BigDecimal("-15.69"));
        return l;
    }

    private static StockNorthFlow northFlow(LocalDate d, String net, String accum) {
        StockNorthFlow n = new StockNorthFlow();
        n.setTradeDate(d);
        n.setNetFlow(new BigDecimal(net));
        n.setAccumFlow(new BigDecimal(accum));
        return n;
    }

    private static StockMarginDaily margin(LocalDate d, String market) {
        StockMarginDaily m = new StockMarginDaily();
        m.setTradeDate(d);
        m.setMarket(market);
        m.setFinancingBalance(new BigDecimal("1349012000000.00"));
        m.setSecuritiesBalance(new BigDecimal("14323000000.00"));
        m.setTotalBalance(new BigDecimal("1363335000000.00"));
        return m;
    }

    private static StockBlockTrade blockTrade(String code) {
        StockBlockTrade b = new StockBlockTrade();
        b.setCode(code);
        b.setName("岱勒新材");
        b.setTradeDate(D2);
        b.setPrice(new BigDecimal("14.20"));
        b.setClosePrice(new BigDecimal("14.24"));
        b.setVolume(3893700L);
        b.setAmount(new BigDecimal("55290540.00"));
        b.setPremiumRate(new BigDecimal("-0.28"));
        b.setBuyerBranch("东方证券股份有限公司上海浦东新区北蔡证券营业部");
        return b;
    }
}
