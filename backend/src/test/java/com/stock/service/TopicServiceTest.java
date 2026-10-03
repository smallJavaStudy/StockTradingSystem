package com.stock.service;

import com.stock.entity.StockZtPool;
import com.stock.repository.StockZtPoolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link TopicService} 单元测试：mock {@link StockZtPoolRepository} 与 {@link DataEnricher}（不依赖数据库与 LLM），
 * 覆盖行业聚合（Top15 / 代表股上限 5）、buildContext 表格格式、enrich 缓存 key 契约
 * （stockCode="MARKET" + directionKey="TOPIC_yyyy-MM-dd"）与 LLM 级联失败降级（source="fallback"）。
 */
class TopicServiceTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 29);

    private StockZtPoolRepository ztPoolRepo;
    private DataEnricher dataEnricher;
    private TopicService service;

    @BeforeEach
    void setUp() {
        ztPoolRepo = mock(StockZtPoolRepository.class);
        dataEnricher = mock(DataEnricher.class);
        service = new TopicService(ztPoolRepo, dataEnricher);
    }

    // ─────────────────── 场景A：多行业正常路径 ───────────────────

    @Test
    @DisplayName("场景A：Top15 行业上限、代表股上限 5、context 表格格式、enrich key 契约（MARKET + TOPIC_日期）")
    @SuppressWarnings("unchecked")
    void getTopics_multiIndustry_llmSuccess() {
        when(ztPoolRepo.findMaxTradeDate()).thenReturn(Optional.of(TRADE_DATE));
        when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(TRADE_DATE, "TODAY"))
                .thenReturn(buildPool());
        when(dataEnricher.enrich(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("【DB上下文段】原始行业分布\n=== DeepSeek 增强分析 ===\n主线为半导体📈，其次军工。");

        Map<String, Object> resp = service.getTopics();

        // 行业分布：17 个行业截断为 Top15，家数降序，首位为 6 家的“半导体”
        List<TopicService.IndustryBucket> industries =
                (List<TopicService.IndustryBucket>) resp.get("industries");
        assertThat(industries).hasSize(TopicService.INDUSTRY_LIMIT);
        assertThat(industries.get(0).industry()).isEqualTo("半导体");
        assertThat(industries.get(0).count()).isEqualTo(6);
        assertThat(industries.get(0).maxLimitUpDays()).isEqualTo(4);
        // 代表股上限 5，格式：名称(代码,N板[,统计x/y])
        assertThat(industries.get(0).topStocks()).hasSize(TopicService.REP_STOCK_LIMIT);
        assertThat(industries.get(0).topStocks().get(0)).isEqualTo("半导股0(300100,4板,统计4/4)");
        assertThat(industries.get(1).industry()).isEqualTo("军工");
        assertThat(industries.get(1).count()).isEqualTo(3);

        // enrich 契约：stockCode="MARKET"、directionKey="TOPIC_yyyy-MM-dd"、context 为表格文本
        ArgumentCaptor<String> ctxCaptor = ArgumentCaptor.forClass(String.class);
        verify(dataEnricher).enrich(eq(TopicService.MARKET_CODE), eq("A股市场"),
                ctxCaptor.capture(), eq("TOPIC_" + TRADE_DATE), any());
        String context = ctxCaptor.getValue();
        assertThat(context).contains("【" + TRADE_DATE + " 涨停池行业分布（家数降序）】");
        assertThat(context).contains("行业 | 涨停家数 | 最高连板 | 代表股");
        assertThat(context).contains("半导体 | 6 | 4板 | ");
        assertThat(context).contains("军工 | 3 | 2板 | ");

        // LLM 成功：仅保留“=== DeepSeek”之后的分析段，且过 stripNonBmp（emoji 被剔除）
        assertThat(resp.get("source")).isEqualTo("llm");
        assertThat(resp.get("tradeDate")).isEqualTo(TRADE_DATE);
        String llm = (String) resp.get("llmAnalysis");
        assertThat(llm).startsWith("=== DeepSeek");
        assertThat(llm).doesNotContain("【DB上下文段】");
        assertThat(llm).doesNotContain("📈");
    }

    // ─────────────────── 场景B：LLM 级联失败降级 ───────────────────

    @Test
    @DisplayName("场景B：DataEnricher 抛异常→source=fallback、llmAnalysis 含降级提示、行业分布仍返回")
    @SuppressWarnings("unchecked")
    void getTopics_enricherFails_fallback() {
        when(ztPoolRepo.findMaxTradeDate()).thenReturn(Optional.of(TRADE_DATE));
        when(ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(TRADE_DATE, "TODAY"))
                .thenReturn(buildPool());
        when(dataEnricher.enrich(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("DeepSeek/Kimi 级联全部超时"));

        Map<String, Object> resp = service.getTopics();

        assertThat(resp.get("source")).isEqualTo("fallback");
        assertThat((String) resp.get("llmAnalysis"))
                .contains("LLM 题材分析暂不可用")
                .contains("DeepSeek/Kimi 级联全部超时");
        List<TopicService.IndustryBucket> industries =
                (List<TopicService.IndustryBucket>) resp.get("industries");
        assertThat(industries).isNotEmpty();
        assertThat(industries.get(0).industry()).isEqualTo("半导体");
    }

    @Test
    @DisplayName("无涨停池数据：source=none，不调 LLM")
    void getTopics_noData_returnsNone() {
        when(ztPoolRepo.findMaxTradeDate()).thenReturn(Optional.empty());

        Map<String, Object> resp = service.getTopics();

        assertThat(resp.get("source")).isEqualTo("none");
        assertThat((List<?>) resp.get("industries")).isEmpty();
        verifyNoInteractions(dataEnricher);
    }

    // ─────────────────── 测试数据 ───────────────────

    /**
     * 17 个行业：半导体 6 只（连板 4/3/2/1/1/null，验证代表股截断 5）+ 军工 3 只 +
     * 15 个单只行业（验证 Top15 截断）；行业为空串的股票归入“未知”。
     */
    private List<StockZtPool> buildPool() {
        List<StockZtPool> pool = new ArrayList<>();
        int[] semiDays = {4, 3, 2, 1, 1};
        for (int i = 0; i < 5; i++) {
            pool.add(zt("30010" + i, "半导股" + i, "半导体", semiDays[i], semiDays[i] + "/" + semiDays[i]));
        }
        pool.add(zt("300105", "半导股5", "半导体", null, null)); // limitUpDays null 按 1 板兜底
        for (int i = 0; i < 3; i++) {
            pool.add(zt("60020" + i, "军工股" + i, "军工", 2, null));
        }
        for (int i = 0; i < 14; i++) {
            pool.add(zt(String.format("%06d", 1000 + i), "个股" + i, "行业" + i, 1, null));
        }
        pool.add(zt("000999", "无行业股", "", 1, null)); // 归入“未知”，共 17 个行业
        return pool;
    }

    private StockZtPool zt(String code, String name, String industry, Integer limitUpDays, String ztStat) {
        StockZtPool z = new StockZtPool();
        z.setCode(code);
        z.setName(name);
        z.setTradeDate(TRADE_DATE);
        z.setPoolType("TODAY");
        z.setIndustry(industry);
        z.setLimitUpDays(limitUpDays);
        z.setZtStat(ztStat);
        return z;
    }
}
