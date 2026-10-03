package com.stock.controller;

import com.stock.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实数据闭环校验：直连本地 MySQL(stock_db) 只读校验 /api/market 各端点的真实返回。
 * <p>
 * 依赖 data-fetcher/fetch_market.py 已抓取入库，故默认跳过；按需运行：
 * <pre>mvn test -Dtest=MarketDataRealDbVerifyTest -Dmarket.realdb=true</pre>
 * 只读查询（@DataJpaTest 事务结束回滚），不写入、不删除任何数据。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "market.realdb", matches = "true")
class MarketDataRealDbVerifyTest {

    @Autowired private StockZtPoolRepository ztPoolRepo;
    @Autowired private StockLhbDetailRepository lhbRepo;
    @Autowired private StockNorthFlowRepository northFlowRepo;
    @Autowired private StockMarginDailyRepository marginRepo;
    @Autowired private StockBlockTradeRepository blockTradeRepo;

    private MarketDataController controller() {
        return new MarketDataController(ztPoolRepo, lhbRepo, northFlowRepo, marginRepo, blockTradeRepo);
    }

    @Test
    @DisplayName("涨停池：真实数据非空，连板数降序，关键字段有值")
    void ztPool() {
        var body = controller().ztPool(null, MarketDataController.POOL_TODAY).getBody();
        assertThat(body).isNotEmpty();
        var first = body.get(0);
        System.out.println("[zt-pool] rows=" + body.size() + " sample=" + first);
        assertThat(first.code()).hasSize(6);
        assertThat(first.tradeDate()).isNotNull();
        assertThat(first.changePct()).isNotNull();
        assertThat(first.limitUpDays()).isNotNull();
        assertThat(body).extracting(MarketDataController.ZtPoolItem::limitUpDays)
                .isSortedAccordingTo((a, b) -> Integer.compare(b, a));
    }

    @Test
    @DisplayName("龙虎榜：真实数据非空，净买额降序")
    void lhb() {
        var body = controller().lhb(null).getBody();
        assertThat(body).isNotEmpty();
        System.out.println("[lhb] rows=" + body.size() + " sample=" + body.get(0));
        assertThat(body.get(0).rankReason()).isNotBlank();
        assertThat(body).extracting(MarketDataController.LhbItem::netAmount)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("北向资金：真实数据非空，日期升序，单位亿元量级合理")
    void northFlow() {
        var body = controller().northFlow(30).getBody();
        assertThat(body).isNotEmpty();
        System.out.println("[north-flow] rows=" + body.size() + " first=" + body.get(0)
                + " last=" + body.get(body.size() - 1));
        assertThat(body).extracting(MarketDataController.NorthFlowItem::tradeDate)
                .isSortedAccordingTo(LocalDate::compareTo);
        assertThat(body.get(0).netFlow().abs().doubleValue()).isLessThan(1000d); // 亿元级
    }

    @Test
    @DisplayName("两融：沪深两市成对出现，余额为万亿元量级(元)")
    void margin() {
        var body = controller().margin(30).getBody();
        assertThat(body).isNotEmpty();
        System.out.println("[margin] rows=" + body.size() + " last2="
                + body.subList(Math.max(0, body.size() - 2), body.size()));
        assertThat(body).extracting(MarketDataController.MarginItem::market).contains("SH", "SZ");
        assertThat(body.get(body.size() - 1).financingBalance().doubleValue()).isGreaterThan(1e11);
    }

    @Test
    @DisplayName("大宗交易：真实数据非空，成交额降序")
    void blockTrade() {
        var body = controller().blockTrade(null).getBody();
        assertThat(body).isNotEmpty();
        System.out.println("[block-trade] rows=" + body.size() + " sample=" + body.get(0));
        assertThat(body).extracting(MarketDataController.BlockTradeItem::amount)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("交易日列表：各 type 均返回倒序日期，且不超过 30 条")
    void dates() {
        for (String type : List.of("zt", "lhb", "block", "margin")) {
            List<LocalDate> dates = controller().dates(type).getBody();
            System.out.println("[dates] type=" + type + " -> " + dates);
            assertThat(dates).isNotEmpty().hasSizeLessThanOrEqualTo(30)
                    .isSortedAccordingTo((a, b) -> b.compareTo(a));
        }
    }
}
