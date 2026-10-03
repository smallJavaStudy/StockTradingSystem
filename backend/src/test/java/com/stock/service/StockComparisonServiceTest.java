package com.stock.service;

import com.stock.agent.AgentFactory;
import com.stock.entity.*;
import com.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link StockComparisonService} 单元测试：mock 全部 Repository 与 AgentFactory（不连数据库/LLM），
 * 覆盖对比数据组装（本股+竞品、缺数据置null、快照回退、市值/PE计算）与点评当日缓存命中。
 */
class StockComparisonServiceTest {

    private static final String CODE = "300364";

    private StockBasicRepository basicRepo;
    private StockCompanyRepository companyRepo;
    private StockCompetitorRepository competitorRepo;
    private StockFinanceRepository financeRepo;
    private StockQuoteRepository quoteRepo;
    private EnrichmentDataRepository enrichmentRepo;
    private AgentFactory agentFactory;
    private StockComparisonService service;

    @BeforeEach
    void setUp() {
        basicRepo = mock(StockBasicRepository.class);
        companyRepo = mock(StockCompanyRepository.class);
        competitorRepo = mock(StockCompetitorRepository.class);
        financeRepo = mock(StockFinanceRepository.class);
        quoteRepo = mock(StockQuoteRepository.class);
        enrichmentRepo = mock(EnrichmentDataRepository.class);
        agentFactory = mock(AgentFactory.class);
        service = new StockComparisonService(basicRepo, companyRepo, competitorRepo,
                financeRepo, quoteRepo, enrichmentRepo, agentFactory);

        // 缺省：任何 code 都查不到财务/行情/公司数据
        when(financeRepo.findByCodeOrderByReportDateDesc(anyString())).thenReturn(List.of());
        when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc(anyString())).thenReturn(Optional.empty());
        when(basicRepo.findByCode(anyString())).thenReturn(Optional.empty());
    }

    private StockBasic basic(long id, String code, String name) {
        StockBasic b = new StockBasic();
        b.setId(id);
        b.setCode(code);
        b.setName(name);
        b.setIndustry("文化传媒");
        return b;
    }

    private StockFinance finance(String code, LocalDate reportDate, String eps, String roe,
                                 String revenue, String netProfit) {
        StockFinance f = new StockFinance();
        f.setCode(code);
        f.setReportDate(reportDate);
        f.setBasicEps(eps == null ? null : new BigDecimal(eps));
        f.setWeightedRoe(roe == null ? null : new BigDecimal(roe));
        f.setTotalRevenue(revenue == null ? null : new BigDecimal(revenue));
        f.setNetProfit(netProfit == null ? null : new BigDecimal(netProfit));
        return f;
    }

    private StockQuote quote(String code, String price) {
        StockQuote q = new StockQuote();
        q.setCode(code);
        q.setPrice(new BigDecimal(price));
        return q;
    }

    // ─────────────────── 对比数据组装 ───────────────────

    @Nested
    @DisplayName("buildComparison 对比组装")
    class BuildComparison {

        @Test
        @DisplayName("股票不存在：抛 NoSuchElementException")
        void baseNotFound_throws() {
            assertThatThrownBy(() -> service.buildComparison("999999"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("股票不存在");
        }

        @Test
        @DisplayName("本股完整数据：财务+行情+市值+PE 全部就位")
        void base_fullData() {
            StockBasic b = basic(1L, CODE, "中文在线");
            when(basicRepo.findByCode(CODE)).thenReturn(Optional.of(b));
            when(competitorRepo.findByStockId(1L)).thenReturn(List.of());
            // 年报 EPS=0.5 → 年化0.5；现价20 → PE=40
            when(financeRepo.findByCodeOrderByReportDateDesc(CODE)).thenReturn(
                    List.of(finance(CODE, LocalDate.of(2025, 12, 31), "0.5", "8.5", "1500000000", "360000000")));
            when(quoteRepo.findTopByCodeOrderByUpdateTimeDesc(CODE))
                    .thenReturn(Optional.of(quote(CODE, "20.00")));
            StockCompany company = new StockCompany();
            company.setStockId(1L);
            company.setTotalShares(700_000_000L);
            when(companyRepo.findByStockId(1L)).thenReturn(Optional.of(company));

            Map<String, Object> result = service.buildComparison(CODE);

            @SuppressWarnings("unchecked")
            Map<String, Object> base = (Map<String, Object>) result.get("base");
            assertThat(base.get("name")).isEqualTo("中文在线");
            assertThat(base.get("industry")).isEqualTo("文化传媒");
            assertThat((BigDecimal) base.get("eps")).isEqualByComparingTo("0.5");
            // 市值 = 20 × 7亿股 = 140亿
            assertThat((BigDecimal) base.get("marketCap")).isEqualByComparingTo("14000000000");
            assertThat((BigDecimal) base.get("pe")).isEqualByComparingTo("40");
            assertThat(result.get("competitors")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("竞品DB无数据：回退 StockCompetitor 快照字段，其余置 null")
        void competitor_fallbackToSnapshot() {
            StockBasic b = basic(1L, CODE, "中文在线");
            when(basicRepo.findByCode(CODE)).thenReturn(Optional.of(b));

            StockCompetitor comp = new StockCompetitor();
            comp.setStockId(1L);
            comp.setCompetitorName("掌阅科技");
            comp.setCompetitorCode("603533");
            comp.setCompetitorRevenue(new BigDecimal("2600000000"));
            comp.setCompetitorNetProfit(new BigDecimal("100000000"));
            comp.setCompetitorMarketCap(9_000_000_000L);
            when(competitorRepo.findByStockId(1L)).thenReturn(List.of(comp));

            Map<String, Object> result = service.buildComparison(CODE);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> peers = (List<Map<String, Object>>) result.get("competitors");
            assertThat(peers).hasSize(1);
            Map<String, Object> peer = peers.get(0);
            assertThat(peer.get("name")).isEqualTo("掌阅科技");
            // 快照回退
            assertThat((BigDecimal) peer.get("revenue")).isEqualByComparingTo("2600000000");
            assertThat((BigDecimal) peer.get("netProfit")).isEqualByComparingTo("100000000");
            assertThat((BigDecimal) peer.get("marketCap")).isEqualByComparingTo("9000000000");
            // DB/快照均无的字段置 null
            assertThat(peer.get("eps")).isNull();
            assertThat(peer.get("price")).isNull();
            assertThat(peer.get("pe")).isNull();
        }

        @Test
        @DisplayName("未上市竞品（code为空）：全部指标为 null，不查库")
        void competitor_unlisted_allNull() {
            StockBasic b = basic(1L, CODE, "中文在线");
            when(basicRepo.findByCode(CODE)).thenReturn(Optional.of(b));
            StockCompetitor comp = new StockCompetitor();
            comp.setStockId(1L);
            comp.setCompetitorName("某未上市公司");
            comp.setCompetitorCode(null);
            when(competitorRepo.findByStockId(1L)).thenReturn(List.of(comp));

            Map<String, Object> result = service.buildComparison(CODE);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> peers = (List<Map<String, Object>>) result.get("competitors");
            assertThat(peers.get(0).get("eps")).isNull();
            assertThat(peers.get(0).get("marketCap")).isNull();
            verify(financeRepo, times(1)).findByCodeOrderByReportDateDesc(anyString()); // 仅本股查过
        }
    }

    // ─────────────────── PE 计算 ───────────────────

    @Nested
    @DisplayName("computePe 静态PE计算")
    class ComputePe {

        @Test
        @DisplayName("年报：PE = 价格/EPS")
        void annualReport() {
            StockFinance f = finance(CODE, LocalDate.of(2025, 12, 31), "0.5", null, null, null);
            assertThat(StockComparisonService.computePe(new BigDecimal("20"), f))
                    .isEqualByComparingTo("40");
        }

        @Test
        @DisplayName("一季报：EPS 年化×4 后计算")
        void quarterlyReport_annualized() {
            // Q1 EPS=0.25 → 年化 0.25×12/3=1.0 → PE=20
            StockFinance f = finance(CODE, LocalDate.of(2026, 3, 31), "0.25", null, null, null);
            assertThat(StockComparisonService.computePe(new BigDecimal("20"), f))
                    .isEqualByComparingTo("20");
        }

        @Test
        @DisplayName("亏损（EPS≤0）或缺数据：返回 null")
        void lossOrMissing_returnsNull() {
            StockFinance loss = finance(CODE, LocalDate.of(2025, 12, 31), "-0.3", null, null, null);
            assertThat(StockComparisonService.computePe(new BigDecimal("20"), loss)).isNull();
            assertThat(StockComparisonService.computePe(null, loss)).isNull();
            assertThat(StockComparisonService.computePe(new BigDecimal("20"), null)).isNull();
            StockFinance noEps = finance(CODE, LocalDate.of(2025, 12, 31), null, null, null, null);
            assertThat(StockComparisonService.computePe(new BigDecimal("20"), noEps)).isNull();
        }
    }

    // ─────────────────── 点评缓存 ───────────────────

    @Nested
    @DisplayName("getComment 点评缓存")
    class CommentCache {

        @Test
        @DisplayName("当日缓存命中：直接返回，不调 LLM")
        void sameDayCache_hit() {
            EnrichmentData cached = new EnrichmentData();
            cached.setStockCode(CODE);
            cached.setDirectionKey("COMPARISON_COMMENT");
            cached.setEnrichmentJson("竞争格局点评正文");
            cached.setCreatedAt(LocalDateTime.now());
            when(enrichmentRepo.findTopByStockCodeAndDirectionKeyOrderByCreatedAtDesc(
                    CODE, StockComparisonService.COMMENT_DIRECTION_KEY))
                    .thenReturn(Optional.of(cached));

            Map<String, Object> result = service.getComment(CODE);

            assertThat(result.get("comment")).isEqualTo("竞争格局点评正文");
            assertThat(result.get("fromCache")).isEqualTo(true);
            verifyNoInteractions(agentFactory);
        }

        @Test
        @DisplayName("缓存是昨天的：不复用（走重新生成路径）")
        void staleCache_notReused() {
            EnrichmentData stale = new EnrichmentData();
            stale.setStockCode(CODE);
            stale.setEnrichmentJson("昨日点评");
            stale.setCreatedAt(LocalDateTime.now().minusDays(1));
            when(enrichmentRepo.findTopByStockCodeAndDirectionKeyOrderByCreatedAtDesc(
                    CODE, StockComparisonService.COMMENT_DIRECTION_KEY))
                    .thenReturn(Optional.of(stale));
            // 本股不存在 → buildComparison 先抛出，证明未走缓存分支
            when(basicRepo.findByCode(CODE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getComment(CODE))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }
}
