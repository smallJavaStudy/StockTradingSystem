package com.stock.repository;

import com.stock.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 市场数据 5 张表的 Repository 测试：在内存 H2 上校验实体映射、派生查询方法与排序契约
 * （生产库为 MySQL，此处仅替换数据源，不触碰真实数据）。
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "flowable.database-schema-update=false",
        "flowable.async-executor-activate=false"
})
class MarketDataRepositoryTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 27);
    private static final LocalDate D2 = LocalDate.of(2026, 7, 28);

    @Autowired private StockZtPoolRepository ztPoolRepo;
    @Autowired private StockLhbDetailRepository lhbRepo;
    @Autowired private StockNorthFlowRepository northFlowRepo;
    @Autowired private StockMarginDailyRepository marginRepo;
    @Autowired private StockBlockTradeRepository blockTradeRepo;

    @BeforeEach
    void seed() {
        ztPoolRepo.saveAll(List.of(
                ztPool("000001", D2, "TODAY", 1, "5.00"),
                ztPool("000002", D2, "TODAY", 3, "10.00"),
                ztPool("000003", D2, "PREVIOUS", 2, "-3.00"),
                ztPool("000001", D1, "TODAY", 1, "9.98")));
        lhbRepo.saveAll(List.of(
                lhb("300308", D2, "500", "跌幅榜"),
                lhb("300502", D2, "1500", "涨幅榜"),
                lhb("300308", D2, "800", "换手率榜"),
                lhb("600000", D1, "100", "涨幅榜")));
        northFlowRepo.saveAll(List.of(
                northFlow(D1, "122.0584"), northFlow(D2, "-67.7499")));
        marginRepo.saveAll(List.of(
                margin(D1, "SH"), margin(D1, "SZ"), margin(D2, "SZ"), margin(D2, "SH")));
        blockTradeRepo.saveAll(List.of(
                blockTrade("601985", D2, "35400"), blockTrade("600406", D2, "32472"),
                blockTrade("601985", D1, "1000")));
    }

    @Test
    @DisplayName("涨停池：按日期+类型查询，连板数降序、涨跌幅降序")
    void ztPoolQueries() {
        List<StockZtPool> today = ztPoolRepo
                .findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D2, "TODAY");
        assertThat(today).extracting(StockZtPool::getCode).containsExactly("000002", "000001");

        assertThat(ztPoolRepo.findByTradeDateOrderByLimitUpDaysDescChangePctDesc(D2)).hasSize(3);
        assertThat(ztPoolRepo.findByCodeOrderByTradeDateDesc("000001"))
                .extracting(StockZtPool::getTradeDate).containsExactly(D2, D1);
        assertThat(ztPoolRepo.findByCodeAndTradeDateAndPoolType("000003", D2, "PREVIOUS")).isPresent();
        assertThat(ztPoolRepo.findByCodeAndTradeDateAndPoolType("000003", D2, "TODAY")).isEmpty();
        assertThat(ztPoolRepo.findMaxTradeDate()).contains(D2);
        assertThat(ztPoolRepo.findDistinctTradeDates(PageRequest.of(0, 30))).containsExactly(D2, D1);
        assertThat(ztPoolRepo.findDistinctTradeDates(PageRequest.of(0, 1))).containsExactly(D2);
    }

    @Test
    @DisplayName("龙虎榜：同股同日多次上榜均保留，按净买额降序")
    void lhbQueries() {
        List<StockLhbDetail> rows = lhbRepo.findByTradeDateOrderByNetAmountDesc(D2);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(StockLhbDetail::getNetAmount)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(lhbRepo.findByCodeOrderByTradeDateDesc("300308")).hasSize(2);
        assertThat(lhbRepo.findMaxTradeDate()).contains(D2);
        assertThat(lhbRepo.findDistinctTradeDates(PageRequest.of(0, 30))).containsExactly(D2, D1);
    }

    @Test
    @DisplayName("北向资金：唯一日期 + 分页倒序")
    void northFlowQueries() {
        assertThat(northFlowRepo.findByOrderByTradeDateDesc(PageRequest.of(0, 30)))
                .extracting(StockNorthFlow::getTradeDate).containsExactly(D2, D1);
        assertThat(northFlowRepo.findByOrderByTradeDateDesc(PageRequest.of(0, 1)))
                .extracting(StockNorthFlow::getTradeDate).containsExactly(D2);
        assertThat(northFlowRepo.findByTradeDate(D1)).isPresent();
        assertThat(northFlowRepo.findByTradeDate(LocalDate.of(2020, 1, 1))).isEmpty();
    }

    @Test
    @DisplayName("两融：日期倒序+市场升序，按日期集合批量查，交易日去重分页")
    void marginQueries() {
        assertThat(marginRepo.findByOrderByTradeDateDescMarketAsc(PageRequest.of(0, 10)))
                .extracting(StockMarginDaily::getTradeDate, StockMarginDaily::getMarket)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(D2, "SH"),
                        org.assertj.core.groups.Tuple.tuple(D2, "SZ"),
                        org.assertj.core.groups.Tuple.tuple(D1, "SH"),
                        org.assertj.core.groups.Tuple.tuple(D1, "SZ"));
        assertThat(marginRepo.findByMarketOrderByTradeDateDesc("SH", PageRequest.of(0, 10))).hasSize(2);
        assertThat(marginRepo.findDistinctTradeDates(PageRequest.of(0, 30))).containsExactly(D2, D1);
        assertThat(marginRepo.findByTradeDateInOrderByTradeDateDescMarketAsc(List.of(D2))).hasSize(2);
        assertThat(marginRepo.findByTradeDateAndMarket(D2, "SH")).isPresent();
    }

    @Test
    @DisplayName("大宗交易：同日多笔按成交额降序，最新日期与交易日列表")
    void blockTradeQueries() {
        List<StockBlockTrade> rows = blockTradeRepo.findByTradeDateOrderByAmountDesc(D2);
        assertThat(rows).extracting(StockBlockTrade::getCode).containsExactly("601985", "600406");
        assertThat(blockTradeRepo.findByCodeOrderByTradeDateDesc("601985"))
                .extracting(StockBlockTrade::getTradeDate).containsExactly(D2, D1);
        assertThat(blockTradeRepo.findMaxTradeDate()).contains(D2);
        assertThat(blockTradeRepo.findDistinctTradeDates(PageRequest.of(0, 30))).containsExactly(D2, D1);
    }

    @Test
    @DisplayName("可空字段保持 null，不做兜底填充")
    void nullableFieldsStayNull() {
        StockZtPool z = ztPoolRepo.findByCodeAndTradeDateAndPoolType("000001", D2, "TODAY").orElseThrow();
        assertThat(z.getReason()).isNull();
        assertThat(z.getUpdateTime()).isNotNull();
    }

    // ─────────────────── 测试数据构造 ───────────────────

    private static StockZtPool ztPool(String code, LocalDate d, String poolType, int days, String changePct) {
        StockZtPool z = new StockZtPool();
        z.setCode(code);
        z.setName("测试股" + code);
        z.setTradeDate(d);
        z.setPoolType(poolType);
        z.setClosePrice(new BigDecimal("10.00"));
        z.setChangePct(new BigDecimal(changePct));
        z.setLimitUpDays(days);
        z.setFirstTime("09:25:00");
        z.setLastTime("14:55:00");
        z.setOpenTimes(0);
        z.setAmount(new BigDecimal("100000000.00"));
        z.setTurnoverRate(new BigDecimal("5.50"));
        z.setIndustry("测试行业");
        z.setZtStat("1/1");
        return z;
    }

    private static StockLhbDetail lhb(String code, LocalDate d, String netWan, String reason) {
        StockLhbDetail l = new StockLhbDetail();
        l.setCode(code);
        l.setName("测试股" + code);
        l.setTradeDate(d);
        l.setRankReason(reason);
        l.setNetAmount(new BigDecimal(netWan).multiply(new BigDecimal("10000")));
        l.setBuyAmount(new BigDecimal("20000000.00"));
        l.setSellAmount(new BigDecimal("10000000.00"));
        l.setTotalAmount(new BigDecimal("30000000.00"));
        l.setChangePct(new BigDecimal("3.00"));
        return l;
    }

    private static StockNorthFlow northFlow(LocalDate d, String net) {
        StockNorthFlow n = new StockNorthFlow();
        n.setTradeDate(d);
        n.setNetFlow(new BigDecimal(net));
        n.setAccumFlow(new BigDecimal("17615.2249"));
        return n;
    }

    private static StockMarginDaily margin(LocalDate d, String market) {
        StockMarginDaily m = new StockMarginDaily();
        m.setTradeDate(d);
        m.setMarket(market);
        m.setFinancingBalance(new BigDecimal("1349011533515.00"));
        m.setFinancingBuyAmount(new BigDecimal("88529674067.00"));
        m.setSecuritiesBalance(new BigDecimal("14323815729.00"));
        m.setTotalBalance(new BigDecimal("1363335349244.00"));
        return m;
    }

    private static StockBlockTrade blockTrade(String code, LocalDate d, String amountWan) {
        StockBlockTrade b = new StockBlockTrade();
        b.setCode(code);
        b.setName("测试股" + code);
        b.setTradeDate(d);
        b.setPrice(new BigDecimal("8.85"));
        b.setClosePrice(new BigDecimal("8.85"));
        b.setVolume(40000000L);
        b.setAmount(new BigDecimal(amountWan).multiply(new BigDecimal("10000")));
        b.setPremiumRate(new BigDecimal("0.00"));
        b.setBuyerBranch("测试营业部A");
        b.setSellerBranch("测试营业部B");
        return b;
    }
}
