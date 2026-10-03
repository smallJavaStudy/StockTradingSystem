package com.stock.workflow.service;

import com.stock.entity.Watchlist;
import com.stock.repository.WatchlistRepository;
import com.stock.workflow.engine.WorkflowRunService;
import com.stock.workflow.engine.WorkflowStatusView;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link DailyScheduleService} 单元测试：mock {@link WorkflowRunService}/{@link WorkflowDefRepository}/
 * {@link WatchlistRepository}（不依赖数据库与 Flowable 引擎），覆盖自选股为空跳过、
 * 两只自选股串行触发与终态计数、等待终态超时（TIMEOUT）、triggerRun 防重入四个场景。
 * <p>批处理主体走包级 {@code runBatch} 同步调用（不经内部执行器），防重入场景才走 {@code triggerRun} 异步路径。
 */
class DailyScheduleServiceTest {

    private WatchlistRepository watchlistRepo;
    private WorkflowDefRepository workflowDefRepo;
    private WorkflowRunService runService;
    private DailyScheduleService service;

    @BeforeEach
    void setUp() {
        watchlistRepo = mock(WatchlistRepository.class);
        workflowDefRepo = mock(WorkflowDefRepository.class);
        runService = mock(WorkflowRunService.class);
        service = new DailyScheduleService(watchlistRepo, workflowDefRepo, runService);
        // 缩短等待参数，避免测试真实 sleep 15s/30min
        service.perStockTimeoutMs = 200;
        service.pollIntervalMs = 10;
        // 默认：复盘工作流不存在（各场景聚焦自选股步骤）
        when(workflowDefRepo.findByName(WorkflowSeedService.REVIEW_WORKFLOW_NAME))
                .thenReturn(Optional.empty());
    }

    // ─────────────────── 场景A：自选股为空 ───────────────────

    @Test
    @DisplayName("场景A：自选股列表为空→跳过并在摘要 steps 中记录，不触发任何工作流")
    void runBatch_emptyWatchlist_skipsAndRecords() {
        stubPublishedStockWorkflow(11L);
        when(watchlistRepo.findAll()).thenReturn(List.of());

        service.runBatch("test");

        List<String> steps = lastRunSteps();
        assertThat(steps).anyMatch(s -> s.contains("自选股分析: 跳过（自选股列表为空）"));
        assertThat(steps).anyMatch(s -> s.contains("每日复盘: 跳过"));
        assertThat(lastRun().get("state")).isEqualTo("DONE");
        assertThat(lastRun().get("source")).isEqualTo("test");
        verify(runService, never()).start(anyLong(), anyMap());
    }

    @Test
    @DisplayName("场景A补充：股票分析工作流为 DRAFT→整步跳过，不查自选股")
    void runBatch_draftWorkflow_skipsWatchlistStep() {
        WorkflowDef draft = def(11L, WorkflowSeedService.STOCK_WORKFLOW_NAME, "DRAFT");
        when(workflowDefRepo.findByName(WorkflowSeedService.STOCK_WORKFLOW_NAME))
                .thenReturn(Optional.of(draft));

        service.runBatch("test");

        assertThat(lastRunSteps()).anyMatch(s -> s.contains("自选股分析: 跳过（股票分析工作流不存在或未发布）"));
        verify(watchlistRepo, never()).findAll();
    }

    // ─────────────────── 场景B：两只自选股不同终态 ───────────────────

    @Test
    @DisplayName("场景B：两只自选股串行触发（前一只终态后才启动下一只），COMPLETED/FAILED 计数正确")
    void runBatch_twoStocks_serialAndCounted() {
        stubPublishedStockWorkflow(11L);
        when(watchlistRepo.findAll()).thenReturn(List.of(
                watch("600519", "贵州茅台"), watch("300364", "中文在线")));
        when(runService.start(eq(11L), anyMap())).thenReturn("pid-1", "pid-2");
        when(runService.getStatus("pid-1")).thenReturn(status("COMPLETED"));
        when(runService.getStatus("pid-2")).thenReturn(status("FAILED"));

        service.runBatch("test");

        assertThat(lastRunSteps()).anyMatch(s -> s.contains("自选股分析: 共 2 只，成功 1，异常 1"));
        // 串行顺序：start(茅台)→等 pid-1 终态→start(中文在线)→等 pid-2 终态
        InOrder inOrder = inOrder(runService);
        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        inOrder.verify(runService).start(eq(11L), inputCaptor.capture());
        inOrder.verify(runService).getStatus("pid-1");
        inOrder.verify(runService).start(eq(11L), inputCaptor.capture());
        inOrder.verify(runService).getStatus("pid-2");
        List<Map<String, Object>> inputs = inputCaptor.getAllValues();
        assertThat(inputs.get(0).get("stockCode")).isEqualTo("600519");
        assertThat(inputs.get(1).get("stockCode")).isEqualTo("300364");
        assertThat(inputs.get(0).get("goal").toString()).contains("盘后例行");
    }

    @Test
    @DisplayName("场景B补充：第一只 start 抛异常不阻断，第二只照常执行")
    void runBatch_startFails_continuesNext() {
        stubPublishedStockWorkflow(11L);
        when(watchlistRepo.findAll()).thenReturn(List.of(
                watch("600519", "贵州茅台"), watch("300364", "中文在线")));
        when(runService.start(eq(11L), anyMap()))
                .thenThrow(new RuntimeException("引擎不可用"))
                .thenReturn("pid-2");
        when(runService.getStatus("pid-2")).thenReturn(status("COMPLETED"));

        service.runBatch("test");

        assertThat(lastRunSteps()).anyMatch(s -> s.contains("自选股分析: 共 2 只，成功 1，异常 1"));
        verify(runService, times(2)).start(eq(11L), anyMap());
    }

    // ─────────────────── 场景C：等待终态超时 ───────────────────

    @Test
    @DisplayName("场景C：流程一直 RUNNING 直到超时→waitForTerminal 返回 TIMEOUT，计入异常数")
    void waitForTerminal_neverTerminal_timesOut() {
        when(runService.getStatus("pid-x")).thenReturn(status("RUNNING"));

        assertThat(service.waitForTerminal("pid-x")).isEqualTo("TIMEOUT");
        verify(runService, atLeastOnce()).getStatus("pid-x");

        // 批处理链路：超时的股票计入“异常”
        stubPublishedStockWorkflow(11L);
        when(watchlistRepo.findAll()).thenReturn(List.of(watch("600519", "贵州茅台")));
        when(runService.start(eq(11L), anyMap())).thenReturn("pid-x");
        service.runBatch("test");
        assertThat(lastRunSteps()).anyMatch(s -> s.contains("自选股分析: 共 1 只，成功 0，异常 1"));
    }

    @Test
    @DisplayName("场景C补充：getStatus 抛异常只告警继续轮询，最终 TIMEOUT 而非上抛")
    void waitForTerminal_statusError_keepsPollingUntilTimeout() {
        when(runService.getStatus("pid-err")).thenThrow(new RuntimeException("查询失败"));

        assertThat(service.waitForTerminal("pid-err")).isEqualTo("TIMEOUT");
    }

    // ─────────────────── 场景D：triggerRun 防重入 ───────────────────

    @Test
    @DisplayName("场景D：批处理运行中二次 triggerRun→started=false；结束后可再次受理")
    void triggerRun_reentrant_rejectedWhileRunning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 第一步 findPublished(股票分析工作流) 时挂起批处理线程，制造“运行中”窗口
        when(workflowDefRepo.findByName(WorkflowSeedService.STOCK_WORKFLOW_NAME)).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        Map<String, Object> first = service.triggerRun("manual");
        assertThat(first.get("started")).isEqualTo(true);
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("批处理线程已进入").isTrue();

        Map<String, Object> second = service.triggerRun("manual");
        assertThat(second.get("started")).isEqualTo(false);
        assertThat(second.get("reason").toString()).contains("仍在运行中");
        assertThat(service.status().get("running")).isEqualTo(true);

        release.countDown();
        awaitNotRunning();
        // 批处理结束后恢复可受理（本轮同样会被挂起 stub 立即放行：release 已 countDown）
        Map<String, Object> third = service.triggerRun("manual");
        assertThat(third.get("started")).isEqualTo(true);
        awaitNotRunning();
    }

    // ─────────────────── 辅助 ───────────────────

    private void stubPublishedStockWorkflow(long id) {
        WorkflowDef def = def(id, WorkflowSeedService.STOCK_WORKFLOW_NAME, "PUBLISHED");
        when(workflowDefRepo.findByName(WorkflowSeedService.STOCK_WORKFLOW_NAME))
                .thenReturn(Optional.of(def));
    }

    private WorkflowDef def(long id, String name, String status) {
        WorkflowDef def = new WorkflowDef();
        def.setId(id);
        def.setName(name);
        def.setStatus(status);
        return def;
    }

    private Watchlist watch(String code, String name) {
        return new Watchlist(code, name, null, null, null);
    }

    private WorkflowStatusView status(String s) {
        WorkflowStatusView view = new WorkflowStatusView();
        view.setStatus(s);
        return view;
    }

    private Map<String, Object> lastRun() {
        return (Map<String, Object>) service.status().get("lastRun");
    }

    @SuppressWarnings("unchecked")
    private List<String> lastRunSteps() {
        return (List<String>) lastRun().get("steps");
    }

    /** 轮询等待后台批处理线程退出（最长 5s），避免固定 sleep */
    private void awaitNotRunning() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.FALSE.equals(service.status().get("running"))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("批处理未在 5s 内结束");
    }
}
