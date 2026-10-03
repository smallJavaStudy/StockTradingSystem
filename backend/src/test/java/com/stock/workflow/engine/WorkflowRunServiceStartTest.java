package com.stock.workflow.engine;

import com.stock.service.MarketSentimentService;
import com.stock.service.StockDataAcquisitionService;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.WorkflowDefRepository;
import com.stock.workflow.repository.WorkflowRunLogRepository;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link WorkflowRunService#start} 数据预注入单元测试：mock Flowable 服务与
 * {@link StockContextPreloader}，验证 STOCK_ANALYSIS 预注入触发条件与变量契约。
 */
class WorkflowRunServiceStartTest {

    private static final long DEF_ID = 7L;
    private static final String CODE = "600519";

    private WorkflowDefRepository workflowDefRepo;
    private RuntimeService runtimeService;
    private StockContextPreloader preloader;
    private MarketSentimentService sentimentService;
    private StockDataAcquisitionService acquisitionService;
    private WorkflowRunService service;

    @BeforeEach
    void setUp() {
        workflowDefRepo = mock(WorkflowDefRepository.class);
        runtimeService = mock(RuntimeService.class);
        preloader = mock(StockContextPreloader.class);
        sentimentService = mock(MarketSentimentService.class);
        acquisitionService = mock(StockDataAcquisitionService.class);
        service = new WorkflowRunService(workflowDefRepo, mock(WorkflowRunLogRepository.class),
                runtimeService, mock(HistoryService.class), mock(ManagementService.class),
                preloader, sentimentService, acquisitionService);
        // 缺省：数据闸门放行（拉取完备）
        when(acquisitionService.ensureStockData(anyString())).thenReturn(
                new StockDataAcquisitionService.AcquisitionResult(true, 120, 8, 60, "本地数据完备"));

        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn("pi-1");
        when(runtimeService.startProcessInstanceByKey(anyString(), anyMap())).thenReturn(instance);
    }

    private WorkflowDef publishedDef(String category) {
        WorkflowDef def = new WorkflowDef();
        def.setId(DEF_ID);
        def.setName("测试工作流");
        def.setCategory(category);
        def.setStatus("PUBLISHED");
        def.setProcessDefinitionKey("wf_test");
        return def;
    }

    private Map<String, Object> startedVars() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeService).startProcessInstanceByKey(eq("wf_test"), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("STOCK_ANALYSIS + stockCode：注入 5 个 _xxx_context 变量并回填 stockName")
    void injectsContexts() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("STOCK_ANALYSIS")));
        when(preloader.buildContextVariables(CODE)).thenReturn(Map.of(
                StockContextPreloader.VAR_KLINE, "K线数据",
                StockContextPreloader.VAR_FINANCE, StockContextPreloader.NO_DATA,
                StockContextPreloader.VAR_FUNDFLOW, StockContextPreloader.NO_DATA,
                StockContextPreloader.VAR_INDUSTRY, "行业数据",
                StockContextPreloader.VAR_COMPANY, "公司数据"));
        when(preloader.resolveStockName(CODE)).thenReturn("贵州茅台");

        Map<String, Object> input = new HashMap<>();
        input.put("stockCode", CODE);
        input.put("goal", "全面分析");
        String pid = service.start(DEF_ID, input);

        assertThat(pid).isEqualTo("pi-1");
        Map<String, Object> vars = startedVars();
        assertThat(vars)
                .containsEntry(StockContextPreloader.VAR_KLINE, "K线数据")
                .containsEntry(StockContextPreloader.VAR_FINANCE, StockContextPreloader.NO_DATA)
                .containsEntry(StockContextPreloader.VAR_FUNDFLOW, StockContextPreloader.NO_DATA)
                .containsEntry(StockContextPreloader.VAR_INDUSTRY, "行业数据")
                .containsEntry(StockContextPreloader.VAR_COMPANY, "公司数据")
                .containsEntry("stockName", "贵州茅台")
                .containsEntry("stockCode", CODE)
                .containsEntry("workflowDefId", DEF_ID);
    }

    @Test
    @DisplayName("STOCK_ANALYSIS + 用户已填 stockName：不覆盖用户输入")
    void keepsUserStockName() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("STOCK_ANALYSIS")));
        when(preloader.buildContextVariables(CODE)).thenReturn(Map.of());

        service.start(DEF_ID, Map.of("stockCode", CODE, "stockName", "用户填的名字"));

        assertThat(startedVars()).containsEntry("stockName", "用户填的名字");
        verify(preloader, never()).resolveStockName(anyString());
    }

    @Test
    @DisplayName("数据闸门不通过（K线拉不到）：阻断启动不烧 token")
    void blocksWhenDataUnacquirable() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("STOCK_ANALYSIS")));
        when(acquisitionService.ensureStockData(CODE)).thenReturn(
                new StockDataAcquisitionService.AcquisitionResult(false, 0, 0, 0,
                        "[X] K线拉取失败: connect timed out"));

        assertThatThrownBy(() -> service.start(DEF_ID, Map.of("stockCode", CODE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据不足且自动拉取失败");
        verify(runtimeService, never()).startProcessInstanceByKey(anyString(), anyMap());
        verifyNoInteractions(preloader);
    }

    @Test
    @DisplayName("非 STOCK_ANALYSIS 类别：不触发预注入")
    void skipsNonStockCategory() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("DEV_PROCESS")));

        service.start(DEF_ID, Map.of("stockCode", CODE));

        verifyNoInteractions(preloader);
        assertThat(startedVars()).doesNotContainKey(StockContextPreloader.VAR_KLINE);
    }

    @Test
    @DisplayName("STOCK_ANALYSIS 但缺 stockCode：跳过预注入且正常启动")
    void skipsWithoutStockCode() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("STOCK_ANALYSIS")));

        String pid = service.start(DEF_ID, Map.of("goal", "分析"));

        assertThat(pid).isEqualTo("pi-1");
        verifyNoInteractions(preloader);
    }

    @Test
    @DisplayName("预注入抛异常：不中断流程启动")
    void preloadFailureDoesNotBlockStart() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("STOCK_ANALYSIS")));
        when(preloader.buildContextVariables(CODE)).thenThrow(new RuntimeException("boom"));

        String pid = service.start(DEF_ID, Map.of("stockCode", CODE));

        assertThat(pid).isEqualTo("pi-1");
    }

    @Test
    @DisplayName("MARKET_REVIEW 类别：预注入 _sentiment_context 情绪指标上下文")
    void injectsSentimentContextForMarketReview() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("MARKET_REVIEW")));
        when(sentimentService.buildSentimentContext(WorkflowRunService.SENTIMENT_CONTEXT_DAYS))
                .thenReturn("近10日情绪指标明细");

        service.start(DEF_ID, Map.of("goal", "盘后复盘"));

        assertThat(startedVars())
                .containsEntry(WorkflowRunService.VAR_SENTIMENT, "近10日情绪指标明细");
        verifyNoInteractions(preloader);
    }

    @Test
    @DisplayName("MARKET_REVIEW 情绪计算失败：注入缺数声明且不中断启动")
    void sentimentFailureDegrades() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("MARKET_REVIEW")));
        when(sentimentService.buildSentimentContext(anyInt())).thenThrow(new RuntimeException("db down"));

        String pid = service.start(DEF_ID, Map.of("goal", "盘后复盘"));

        assertThat(pid).isEqualTo("pi-1");
        assertThat((String) startedVars().get(WorkflowRunService.VAR_SENTIMENT))
                .contains("情绪指标数据未注入");
    }

    @Test
    @DisplayName("非 MARKET_REVIEW 类别：不注入情绪上下文")
    void skipsSentimentForOtherCategories() {
        when(workflowDefRepo.findById(DEF_ID)).thenReturn(Optional.of(publishedDef("CUSTOM")));

        service.start(DEF_ID, Map.of("goal", "随意"));

        verifyNoInteractions(sentimentService);
        assertThat(startedVars()).doesNotContainKey(WorkflowRunService.VAR_SENTIMENT);
    }
}
