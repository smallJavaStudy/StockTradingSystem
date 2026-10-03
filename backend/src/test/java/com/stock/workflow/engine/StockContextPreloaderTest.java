package com.stock.workflow.engine;

import com.stock.entity.*;
import com.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link StockContextPreloader} 单元测试：mock Repository（单测允许 mock，运行时禁 Mock），
 * 覆盖无数据 / 有数据 / 仓库异常三类分支与变量契约。
 */
class StockContextPreloaderTest {

    private static final String CODE = "600519";

    private StockBasicRepository stockBasicRepo;
    private StockCompanyRepository stockCompanyRepo;
    private StockKlineDailyRepository klineRepo;
    private StockFinanceRepository financeRepo;
    private StockFundFlowRepository fundFlowRepo;
    private StockIndustryChainRepository industryChainRepo;
    private StockCompetitorRepository competitorRepo;

    private StockContextPreloader preloader;

    @BeforeEach
    void setUp() {
        stockBasicRepo = mock(StockBasicRepository.class);
        stockCompanyRepo = mock(StockCompanyRepository.class);
        klineRepo = mock(StockKlineDailyRepository.class);
        financeRepo = mock(StockFinanceRepository.class);
        fundFlowRepo = mock(StockFundFlowRepository.class);
        industryChainRepo = mock(StockIndustryChainRepository.class);
        competitorRepo = mock(StockCompetitorRepository.class);
        preloader = new StockContextPreloader(stockBasicRepo, stockCompanyRepo, klineRepo,
                financeRepo, fundFlowRepo, industryChainRepo, competitorRepo);
    }

    // ─────────────────── 无数据分支 ───────────────────

    @Nested
    @DisplayName("无数据分支")
    class NoDataBranch {

        @Test
        @DisplayName("股票不存在/各表为空：5 个变量全部为占位文本，不抛异常")
        void allEmpty() {
            when(klineRepo.findTop120ByCodeOrderByTradeDateDesc(CODE)).thenReturn(List.of());
            when(financeRepo.findByCodeOrderByReportDateDesc(CODE)).thenReturn(List.of());
            when(fundFlowRepo.findTop60ByCodeOrderByTradeDateDesc(CODE)).thenReturn(List.of());
            when(stockBasicRepo.findByCode(CODE)).thenReturn(Optional.empty());

            Map<String, Object> vars = preloader.buildContextVariables(CODE);

            assertThat(vars).containsOnlyKeys(
                    StockContextPreloader.VAR_KLINE, StockContextPreloader.VAR_FINANCE,
                    StockContextPreloader.VAR_FUNDFLOW, StockContextPreloader.VAR_INDUSTRY,
                    StockContextPreloader.VAR_COMPANY);
            assertThat(vars.values()).allMatch(StockContextPreloader.NO_DATA::equals);
        }

        @Test
        @DisplayName("仓库抛异常：对应变量降级为占位文本，不向上抛")
        void repoThrows() {
            when(klineRepo.findTop120ByCodeOrderByTradeDateDesc(anyString()))
                    .thenThrow(new RuntimeException("db down"));
            when(financeRepo.findByCodeOrderByReportDateDesc(anyString()))
                    .thenThrow(new RuntimeException("db down"));
            when(fundFlowRepo.findTop60ByCodeOrderByTradeDateDesc(anyString()))
                    .thenThrow(new RuntimeException("db down"));
            when(stockBasicRepo.findByCode(anyString()))
                    .thenThrow(new RuntimeException("db down"));

            Map<String, Object> vars = preloader.buildContextVariables(CODE);

            assertThat(vars.values()).allMatch(StockContextPreloader.NO_DATA::equals);
        }

        @Test
        @DisplayName("resolveStockName：查不到返回 null")
        void stockNameMissing() {
            when(stockBasicRepo.findByCode(CODE)).thenReturn(Optional.empty());
            assertThat(preloader.resolveStockName(CODE)).isNull();
        }
    }

    // ─────────────────── 有数据分支 ───────────────────

    @Nested
    @DisplayName("有数据分支")
    class WithDataBranch {

        @Test
        @DisplayName("K线：含最新价、MA5/20/60、量价特征与近30日明细")
        void klineContext() {
            when(klineRepo.findTop120ByCodeOrderByTradeDateDesc(CODE)).thenReturn(klines(120));

            String ctx = preloader.buildKlineContext(CODE, 120);

            assertThat(ctx).contains("最新收盘价")
                    .contains("MA5=").contains("MA20=").contains("MA60=")
                    .contains("量价特征").contains("近30日明细");
            // 明细恰好 30 行（旧→新）
            assertThat(ctx.lines().filter(l -> l.matches("\\d{4}-.*,.*")).count()).isEqualTo(30);
            assertThat(ctx).doesNotContain(StockContextPreloader.NO_DATA);
        }

        @Test
        @DisplayName("财务：含各期指标与去年同期同比")
        void financeContext() {
            StockFinance q1 = finance(LocalDate.of(2026, 3, 31), "1.20", "220");
            StockFinance q1LastYear = finance(LocalDate.of(2025, 3, 31), "1.00", "200");
            when(financeRepo.findByCodeOrderByReportDateDesc(CODE))
                    .thenReturn(List.of(q1, q1LastYear));

            String ctx = preloader.buildFinanceContext(CODE);

            assertThat(ctx).contains("2026-03-31").contains("2025-03-31")
                    .contains("+10.0%"); // 营收 200→220 同比 +10.0%
        }

        @Test
        @DisplayName("资金流：含净流入序列与整体趋势")
        void fundflowContext() {
            List<StockFundFlow> flows = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                StockFundFlow ff = new StockFundFlow();
                ff.setCode(CODE);
                ff.setTradeDate(LocalDate.of(2026, 7, 25).minusDays(i));
                ff.setMainNetInflow(new BigDecimal(i % 2 == 0 ? "1000000" : "-200000"));
                flows.add(ff);
            }
            when(fundFlowRepo.findTop60ByCodeOrderByTradeDateDesc(CODE)).thenReturn(flows);

            String ctx = preloader.buildFundflowContext(CODE, 20);

            assertThat(ctx).contains("主力净流入").contains("趋势").contains("净流入天数 10/20")
                    .contains("整体净流入");
        }

        @Test
        @DisplayName("行业+公司：整合 IndustryChain/Competitor/Company 主营")
        void industryAndCompanyContext() {
            StockBasic stock = new StockBasic(CODE, "贵州茅台", "SH", "白酒", LocalDate.of(2001, 8, 27));
            stock.setId(1L);
            when(stockBasicRepo.findByCode(CODE)).thenReturn(Optional.of(stock));

            StockCompany company = new StockCompany();
            company.setMainBusiness("茅台酒及系列酒的生产与销售");
            when(stockCompanyRepo.findByStockId(1L)).thenReturn(Optional.of(company));

            StockIndustryChain chain = new StockIndustryChain();
            chain.setCoreProduct("茅台酒");
            chain.setIndustryPosition("高端白酒龙头");
            when(industryChainRepo.findByStockId(1L)).thenReturn(Optional.of(chain));

            StockCompetitor cp = new StockCompetitor();
            cp.setCompetitorName("五粮液");
            cp.setCompetitorCode("000858");
            when(competitorRepo.findByStockId(1L)).thenReturn(List.of(cp));

            String industryCtx = preloader.buildIndustryContext(CODE);
            String companyCtx = preloader.buildCompanyContext(CODE);

            assertThat(industryCtx).contains("白酒").contains("茅台酒及系列酒")
                    .contains("高端白酒龙头").contains("五粮液");
            assertThat(companyCtx).contains("贵州茅台").contains(CODE).contains("SH");
        }

        @Test
        @DisplayName("buildContextVariables：有K线无其他 → 仅 _kline_context 有内容")
        void mixedData() {
            when(klineRepo.findTop120ByCodeOrderByTradeDateDesc(CODE)).thenReturn(klines(60));
            when(financeRepo.findByCodeOrderByReportDateDesc(CODE)).thenReturn(List.of());
            when(fundFlowRepo.findTop60ByCodeOrderByTradeDateDesc(CODE)).thenReturn(List.of());
            when(stockBasicRepo.findByCode(CODE)).thenReturn(Optional.empty());

            Map<String, Object> vars = preloader.buildContextVariables(CODE);

            assertThat((String) vars.get(StockContextPreloader.VAR_KLINE)).contains("最新收盘价");
            assertThat(vars.get(StockContextPreloader.VAR_FINANCE)).isEqualTo(StockContextPreloader.NO_DATA);
            assertThat(vars.get(StockContextPreloader.VAR_FUNDFLOW)).isEqualTo(StockContextPreloader.NO_DATA);
        }

        @Test
        @DisplayName("resolveStockName：DB 有则返回名称")
        void stockNameFound() {
            StockBasic stock = new StockBasic(CODE, "贵州茅台", "SH", "白酒", null);
            when(stockBasicRepo.findByCode(CODE)).thenReturn(Optional.of(stock));
            assertThat(preloader.resolveStockName(CODE)).isEqualTo("贵州茅台");
        }
    }

    // ─────────────────── 测试数据构造 ───────────────────

    /** n 根日K（降序），价格 10.00 递增，量 1000 起 */
    private static List<StockKlineDaily> klines(int n) {
        List<StockKlineDaily> list = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 7, 27);
        for (int i = 0; i < n; i++) {
            StockKlineDaily k = new StockKlineDaily();
            k.setCode(CODE);
            k.setTradeDate(date.minusDays(i));
            BigDecimal base = new BigDecimal("10.00").add(BigDecimal.valueOf((n - i) * 0.01));
            k.setOpen(base);
            k.setHigh(base.add(new BigDecimal("0.10")));
            k.setLow(base.subtract(new BigDecimal("0.10")));
            k.setClose(base);
            k.setVolume(1000L + i);
            list.add(k);
        }
        return list;
    }

    private static StockFinance finance(LocalDate reportDate, String eps, String revenue) {
        StockFinance f = new StockFinance();
        f.setCode(CODE);
        f.setReportDate(reportDate);
        f.setBasicEps(new BigDecimal(eps));
        f.setTotalRevenue(new BigDecimal(revenue));
        f.setNetProfit(new BigDecimal(revenue).multiply(new BigDecimal("0.5")));
        f.setWeightedRoe(new BigDecimal("15.0"));
        return f;
    }
}
