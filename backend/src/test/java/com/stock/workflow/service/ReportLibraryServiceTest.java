package com.stock.workflow.service;

import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.entity.WorkflowRunLog;
import com.stock.workflow.repository.ReportRunLogRepository;
import com.stock.workflow.repository.ReportRunLogRepository.PreviewRow;
import com.stock.workflow.repository.ReportRunLogRepository.RunSummaryRow;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link ReportLibraryService} 单元测试：
 * 检索过滤逻辑（工作流名 → defIds、股票代码 → 历史实例 ids、日期区间换算）、
 * 运行级状态推导、最终输出预览取"最后一条"、节点明细去重与耗时计算。
 */
class ReportLibraryServiceTest {

    private ReportRunLogRepository reportRepo;
    private WorkflowDefRepository workflowDefRepo;
    private HistoryService historyService;
    private ReportLibraryService service;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ReportRunLogRepository.class);
        workflowDefRepo = mock(WorkflowDefRepository.class);
        historyService = mock(HistoryService.class, RETURNS_DEEP_STUBS);
        service = new ReportLibraryService(reportRepo, workflowDefRepo, historyService);
    }

    // ────────── 检索过滤逻辑 ──────────

    @Test
    @DisplayName("无筛选条件：过滤标志均为 false，日期为 null")
    void search_noFilters() {
        stubEmptyPage();

        service.search(null, null, null, null, 0, 20);

        verify(reportRepo).findRunSummaries(eq(false), anyCollection(), eq(false), anyCollection(),
                isNull(), isNull(), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("日期区间：startDate 含当天 00:00，endDate 含当天 23:59:59")
    void search_dateRangeConversion() {
        stubEmptyPage();

        service.search(null, null, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 20), 0, 20);

        ArgumentCaptor<LocalDateTime> startCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reportRepo).findRunSummaries(anyBoolean(), anyCollection(), anyBoolean(), anyCollection(),
                startCap.capture(), endCap.capture(), any(Pageable.class));
        assertThat(startCap.getValue()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
        assertThat(endCap.getValue()).isAfter(LocalDateTime.of(2026, 7, 20, 23, 59, 58));
        assertThat(endCap.getValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    @DisplayName("工作流名模糊过滤：解析出匹配的 defIds 传入查询")
    void search_workflowNameFilter() {
        when(workflowDefRepo.findAll()).thenReturn(List.of(
                def(1L, "股票分析工作流"), def(2L, "开发流程"), def(3L, "股票复盘")));
        stubEmptyPage();

        service.search(null, "股票", null, null, 0, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> defIdsCap = ArgumentCaptor.forClass(List.class);
        verify(reportRepo).findRunSummaries(eq(true), defIdsCap.capture(), eq(false), anyCollection(),
                isNull(), isNull(), any(Pageable.class));
        assertThat(defIdsCap.getValue()).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    @DisplayName("工作流名无匹配：直接返回空页，不触发数据库检索")
    void search_workflowNameNoMatch() {
        when(workflowDefRepo.findAll()).thenReturn(List.of(def(1L, "开发流程")));

        Page<ReportRunSummary> result = service.search(null, "不存在", null, null, 0, 20);

        assertThat(result.getTotalElements()).isZero();
        verify(reportRepo, never()).findRunSummaries(anyBoolean(), anyCollection(), anyBoolean(),
                anyCollection(), any(), any(), any());
    }

    @Test
    @DisplayName("股票代码过滤：历史流程变量匹配的实例 ids 传入查询")
    void search_stockCodeFilter() {
        HistoricProcessInstance hpi = mock(HistoricProcessInstance.class);
        when(hpi.getId()).thenReturn("pid-1");
        when(historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals("stockCode", "600519").list()).thenReturn(List.of(hpi));
        stubEmptyPage();

        service.search("600519", null, null, null, 0, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> pidsCap = ArgumentCaptor.forClass(List.class);
        verify(reportRepo).findRunSummaries(eq(false), anyCollection(), eq(true), pidsCap.capture(),
                isNull(), isNull(), any(Pageable.class));
        assertThat(pidsCap.getValue()).containsExactly("pid-1");
    }

    @Test
    @DisplayName("股票代码无匹配实例：直接返回空页")
    void search_stockCodeNoMatch() {
        when(historyService.createHistoricProcessInstanceQuery()
                .variableValueEquals("stockCode", "999999").list()).thenReturn(List.of());

        Page<ReportRunSummary> result = service.search("999999", null, null, null, 0, 20);

        assertThat(result.getContent()).isEmpty();
        verify(reportRepo, never()).findRunSummaries(anyBoolean(), anyCollection(), anyBoolean(),
                anyCollection(), any(), any(), any());
    }

    // ────────── 汇总行映射 ──────────

    @Test
    @DisplayName("汇总映射：状态推导 + 工作流名 + 股票代码 + 最终输出预览（取最后一条）")
    void search_mapsSummaryRows() {
        LocalDateTime t0 = LocalDateTime.of(2026, 7, 20, 10, 0);
        RunSummaryRow completed = row("pid-1", 1L, t0, t0.plusMinutes(5), 3L, 0L, 0L);
        RunSummaryRow failed = row("pid-2", 1L, t0.minusHours(1), t0, 4L, 1L, 0L);
        RunSummaryRow running = row("pid-3", 2L, t0.minusHours(2), t0, 2L, 0L, 1L);
        when(reportRepo.findRunSummaries(anyBoolean(), anyCollection(), anyBoolean(), anyCollection(),
                any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(completed, failed, running),
                        PageRequest.of(0, 20), 3));
        when(workflowDefRepo.findAllById(any()))
                .thenReturn(List.of(def(1L, "股票分析"), def(2L, "复盘")));
        // pid-1 有两条输出（升序返回），预览应取最后一条
        List<PreviewRow> previews = List.of(
                preview("pid-1", "第一节点输出"),
                preview("pid-1", "最终建议：买入"),
                preview("pid-2", "部分输出"));
        when(reportRepo.findOutputPreviews(any())).thenReturn(previews);
        stubVariable("pid-1", "stockCode", "600519");

        Page<ReportRunSummary> result = service.search(null, null, null, null, 0, 20);

        assertThat(result.getTotalElements()).isEqualTo(3);
        ReportRunSummary s1 = result.getContent().get(0);
        assertThat(s1.runId()).isEqualTo("pid-1");
        assertThat(s1.workflowName()).isEqualTo("股票分析");
        assertThat(s1.stockCode()).isEqualTo("600519");
        assertThat(s1.status()).isEqualTo("COMPLETED");
        assertThat(s1.nodeCount()).isEqualTo(3);
        assertThat(s1.endTime()).isEqualTo(t0.plusMinutes(5));
        assertThat(s1.finalOutputPreview()).isEqualTo("最终建议：买入");

        ReportRunSummary s2 = result.getContent().get(1);
        assertThat(s2.status()).isEqualTo("FAILED");
        assertThat(s2.finalOutputPreview()).isEqualTo("部分输出");

        ReportRunSummary s3 = result.getContent().get(2);
        assertThat(s3.status()).isEqualTo("RUNNING");
        assertThat(s3.endTime()).isNull(); // 仍在运行不给结束时间
        assertThat(s3.stockCode()).isNull(); // 历史变量缺失可空
        assertThat(s3.finalOutputPreview()).isNull();
    }

    // ────────── 运行明细 ──────────

    @Test
    @DisplayName("明细：同一 nodeId 多条日志保留最新，耗时按毫秒计算")
    void getRunDetail_dedupAndDuration() {
        LocalDateTime t0 = LocalDateTime.of(2026, 7, 20, 10, 0);
        WorkflowRunLog first = log("n1", "技术分析", "FAILED", "旧输出", t0, t0.plusSeconds(3));
        WorkflowRunLog retry = log("n1", "技术分析", "COMPLETED", "重试成功输出", t0.plusMinutes(1), t0.plusMinutes(2));
        WorkflowRunLog second = log("n2", "综合建议", "COMPLETED", "最终建议", t0.plusMinutes(2), t0.plusMinutes(3));
        when(reportRepo.findByProcessInstanceIdOrderByStartedAtAscIdAsc("pid-1"))
                .thenReturn(List.of(first, retry, second));
        when(workflowDefRepo.findById(9L)).thenReturn(Optional.of(def(9L, "股票分析")));
        stubVariable("pid-1", "stockCode", "600519");
        stubVariable("pid-1", "stockName", "贵州茅台");

        ReportRunDetail detail = service.getRunDetail("pid-1");

        assertThat(detail.runId()).isEqualTo("pid-1");
        assertThat(detail.workflowName()).isEqualTo("股票分析");
        assertThat(detail.stockCode()).isEqualTo("600519");
        assertThat(detail.stockName()).isEqualTo("贵州茅台");
        assertThat(detail.status()).isEqualTo("COMPLETED"); // 重试成功后不再算 FAILED
        assertThat(detail.nodes()).hasSize(2);
        ReportNodeDetail n1 = detail.nodes().get(0);
        assertThat(n1.output()).isEqualTo("重试成功输出");
        assertThat(n1.durationMs()).isEqualTo(60_000L);
        assertThat(detail.startTime()).isEqualTo(t0);
        assertThat(detail.endTime()).isEqualTo(t0.plusMinutes(3));
    }

    @Test
    @DisplayName("明细：存在 RUNNING 节点时整体状态 RUNNING 且结束时间为空")
    void getRunDetail_runningStatus() {
        LocalDateTime t0 = LocalDateTime.of(2026, 7, 20, 10, 0);
        WorkflowRunLog done = log("n1", "技术分析", "COMPLETED", "输出", t0, t0.plusMinutes(1));
        WorkflowRunLog running = log("n2", "综合建议", "RUNNING", null, t0.plusMinutes(1), null);
        when(reportRepo.findByProcessInstanceIdOrderByStartedAtAscIdAsc("pid-2"))
                .thenReturn(List.of(done, running));
        when(workflowDefRepo.findById(any())).thenReturn(Optional.empty());

        ReportRunDetail detail = service.getRunDetail("pid-2");

        assertThat(detail.status()).isEqualTo("RUNNING");
        assertThat(detail.endTime()).isNull();
        assertThat(detail.nodes().get(1).durationMs()).isNull();
    }

    @Test
    @DisplayName("明细：运行不存在抛 NoSuchElementException（404）")
    void getRunDetail_notFound() {
        when(reportRepo.findByProcessInstanceIdOrderByStartedAtAscIdAsc("missing"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.getRunDetail("missing"))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ────────── 测试辅助 ──────────

    private void stubEmptyPage() {
        when(reportRepo.findRunSummaries(anyBoolean(), anyCollection(), anyBoolean(), anyCollection(),
                any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
    }

    private void stubVariable(String pid, String name, String value) {
        HistoricVariableInstance var = mock(HistoricVariableInstance.class);
        when(var.getValue()).thenReturn(value);
        when(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(pid).variableName(name).singleResult()).thenReturn(var);
    }

    private WorkflowDef def(Long id, String name) {
        WorkflowDef d = new WorkflowDef();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private RunSummaryRow row(String pid, Long defId, LocalDateTime start, LocalDateTime end,
                              Long nodeCount, Long failedCount, Long runningCount) {
        RunSummaryRow row = mock(RunSummaryRow.class);
        when(row.getProcessInstanceId()).thenReturn(pid);
        when(row.getWorkflowDefId()).thenReturn(defId);
        when(row.getStartTime()).thenReturn(start);
        when(row.getEndTime()).thenReturn(end);
        when(row.getNodeCount()).thenReturn(nodeCount);
        when(row.getFailedCount()).thenReturn(failedCount);
        when(row.getRunningCount()).thenReturn(runningCount);
        return row;
    }

    private PreviewRow preview(String pid, String text) {
        PreviewRow row = mock(PreviewRow.class);
        when(row.getProcessInstanceId()).thenReturn(pid);
        when(row.getPreview()).thenReturn(text);
        return row;
    }

    private WorkflowRunLog log(String nodeId, String nodeName, String status, String output,
                               LocalDateTime start, LocalDateTime end) {
        WorkflowRunLog l = new WorkflowRunLog();
        l.setWorkflowDefId(9L);
        l.setProcessInstanceId("pid");
        l.setNodeId(nodeId);
        l.setNodeName(nodeName);
        l.setStatus(status);
        l.setOutputText(output);
        l.setStartedAt(start);
        l.setCompletedAt(end);
        return l;
    }
}
