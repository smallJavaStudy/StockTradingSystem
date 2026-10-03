package com.stock.service;

import com.stock.agent.AgentFactory;
import com.stock.entity.StockMarginDaily;
import com.stock.entity.StockZtPool;
import com.stock.repository.StockMarginDailyRepository;
import com.stock.repository.StockZtPoolRepository;
import com.stock.service.MarketSentimentService.DailyIndicator;
import com.stock.service.MarketSentimentService.SentimentSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MarketSentimentService} 情绪指标计算单元测试：纯 Mockito，不连 LLM——
 * computeScore 权重公式 / 两融缺数归一化 / phaseByRule 阶段边界 / getSentiment 空库与真实聚合。
 */
class MarketSentimentServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 7, 28);
    private static final LocalDate D0 = LocalDate.of(2026, 7, 27);

    private StockZtPoolRepository ztPoolRepo;
    private StockMarginDailyRepository marginRepo;
    private MarketSentimentService service;

    @BeforeEach
    void setUp() {
        ztPoolRepo = mock(StockZtPoolRepository.class);
        marginRepo = mock(StockMarginDailyRepository.class);
        service = new MarketSentimentService(ztPoolRepo, marginRepo, mock(AgentFactory.class));
    }

    // ════════════════ computeScore 权重公式 ════════════════

    @Nested
    @DisplayName("computeScore：0-100 加权情绪分")
    class ComputeScore {

        @Test
        @DisplayName("极端多头（涨停≥120家、7板、零炸板、两融+1%）→ 满分 100")
        void fullBull() {
            assertThat(service.computeScore(150, 9, 0.0, 1.5)).isEqualTo(100.0);
        }

        @Test
        @DisplayName("极端冰点（零涨停、零连板、100%炸板、两融-1%）→ 0 分")
        void fullBear() {
            assertThat(service.computeScore(0, 0, 100.0, -2.0)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("四项齐全按 30/25/25/20 加权：60家+7板+炸板率20+两融0% → 70.0")
        void weightedWithMargin() {
            // ztScore=50*0.30 + heightScore=100*0.25 + sealScore=80*0.25 + marginScore=50*0.20
            assertThat(service.computeScore(60, 7, 20.0, 0.0)).isEqualTo(70.0);
        }

        @Test
        @DisplayName("两融缺数 → 剩余三项权重归一化（除以0.8）：60家+7板+炸板率20 → 75.0")
        void normalizesWhenMarginMissing() {
            assertThat(service.computeScore(60, 7, 20.0, null)).isEqualTo(75.0);
        }

        @Test
        @DisplayName("两融环比超出 ±1% 区间被截断，不产生越界分")
        void clampsMarginChange() {
            double capped = service.computeScore(60, 7, 20.0, 1.0);
            double overflow = service.computeScore(60, 7, 20.0, 99.0);
            assertThat(overflow).isEqualTo(capped);
        }
    }

    // ════════════════ phaseByRule 阶段边界 ════════════════

    @Nested
    @DisplayName("phaseByRule：规则法周期判定")
    class PhaseByRule {

        private DailyIndicator ind(LocalDate date, double score) {
            return new DailyIndicator(date, 50, 3, 5, 10.0, 90.0, null, null, score);
        }

        @Test
        @DisplayName("最新分 ≥70 → 主升")
        void mainRise() {
            assertThat(service.phaseByRule(List.of(ind(D1, 70.0), ind(D0, 40.0)))).isEqualTo("主升");
        }

        @Test
        @DisplayName("最新分 <30 → 冰点")
        void freezing() {
            assertThat(service.phaseByRule(List.of(ind(D1, 29.9), ind(D0, 80.0)))).isEqualTo("冰点");
        }

        @Test
        @DisplayName("中间区且 ≥近期均值 → 启动")
        void starting() {
            assertThat(service.phaseByRule(List.of(ind(D1, 55.0), ind(D0, 35.0)))).isEqualTo("启动");
        }

        @Test
        @DisplayName("中间区且 <近期均值 → 退潮")
        void ebbing() {
            assertThat(service.phaseByRule(List.of(ind(D1, 40.0), ind(D0, 69.0)))).isEqualTo("退潮");
        }

        @Test
        @DisplayName("空序列 → 无数据")
        void empty() {
            assertThat(service.phaseByRule(List.of())).isEqualTo("无数据");
        }
    }

    // ════════════════ getSentiment 聚合 ════════════════

    @Nested
    @DisplayName("getSentiment：涨停池 + 两融聚合")
    class GetSentiment {

        @Test
        @DisplayName("空库 → latestDate 为 null 且提示先抓取数据")
        void emptyDb() {
            when(ztPoolRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of());

            SentimentSnapshot snapshot = service.getSentiment(10);

            assertThat(snapshot.latestDate()).isNull();
            assertThat(snapshot.phaseHint()).isEqualTo("无数据");
            assertThat(snapshot.note()).contains("暂无涨停池数据");
            assertThat(snapshot.dailyIndicators()).isEmpty();
        }

        @Test
        @DisplayName("两日数据：炸板率按 openTimes>0 派生、两融 SH+SZ 汇总并算环比")
        void aggregatesIndicators() {
            when(ztPoolRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D1, D0));
            // D1：4 只涨停，其中 1 只开过板，最高 3 板
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D1, "TODAY"))
                    .thenReturn(List.of(zt(3, 0), zt(2, 2), zt(1, 0), zt(null, 0)));
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D0, "TODAY"))
                    .thenReturn(List.of(zt(1, 0)));
            // 两融：D1 = 1.01万亿（SH+SZ），D0 = 1.00万亿 → 环比 +1%
            when(marginRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D1, D0));
            when(marginRepo.findByTradeDateInOrderByTradeDateDescMarketAsc(anyList()))
                    .thenReturn(List.of(margin(D1, "5050000000000"), margin(D1, "5050000000000"),
                            margin(D0, "5000000000000"), margin(D0, "5000000000000")));

            SentimentSnapshot snapshot = service.getSentiment(2);

            assertThat(snapshot.latestDate()).isEqualTo(D1);
            assertThat(snapshot.dailyIndicators()).hasSize(2);
            DailyIndicator latest = snapshot.dailyIndicators().get(0);
            assertThat(latest.ztCount()).isEqualTo(4);
            assertThat(latest.maxLimitUpDays()).isEqualTo(3);
            assertThat(latest.brokenCount()).isEqualTo(1);
            assertThat(latest.brokenRate()).isEqualTo(25.0);
            assertThat(latest.sealRate()).isEqualTo(75.0);
            assertThat(latest.marginTotal()).isEqualByComparingTo("10100000000000");
            assertThat(latest.marginChangePct()).isCloseTo(1.0, within(0.01));
            assertThat(latest.score()).isEqualTo(service.computeScore(4, 3, 25.0, latest.marginChangePct()));
            // 北向降级声明
            assertThat(snapshot.note()).contains("北向");
        }

        @Test
        @DisplayName("buildSentimentContext：输出含权重说明与逐日明细表头")
        void buildsContextText() {
            when(ztPoolRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of(D1));
            when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(D1, "TODAY"))
                    .thenReturn(List.of(zt(2, 0)));
            when(marginRepo.findDistinctTradeDates(any(Pageable.class))).thenReturn(List.of());

            String context = service.buildSentimentContext(5);

            assertThat(context).contains("市场情绪指标")
                    .contains("涨停家数30%")
                    .contains("日期 | 情绪分 | 涨停家数")
                    .contains(D1.toString());
        }

        private StockZtPool zt(Integer limitUpDays, int openTimes) {
            StockZtPool z = new StockZtPool();
            z.setCode("600000");
            z.setName("测试股");
            z.setTradeDate(D1);
            z.setPoolType("TODAY");
            z.setLimitUpDays(limitUpDays);
            z.setOpenTimes(openTimes);
            z.setIndustry("测试行业");
            return z;
        }

        private StockMarginDaily margin(LocalDate date, String totalBalance) {
            StockMarginDaily m = new StockMarginDaily();
            m.setTradeDate(date);
            m.setTotalBalance(new BigDecimal(totalBalance));
            return m;
        }
    }
}
