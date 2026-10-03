package com.stock.workflow.service;

import com.stock.entity.Watchlist;
import com.stock.repository.WatchlistRepository;
import com.stock.workflow.engine.WorkflowRunService;
import com.stock.workflow.engine.WorkflowStatusView;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 盘后每日批处理：交易日 17:00（Asia/Shanghai）顺序执行——
 * <ol>
 *   <li>市场数据抓取：由独立的 data-fetcher Python 脚本负责（可单独运行/另行调度），本服务不直接调用，仅记录说明；</li>
 *   <li>自选股逐只串行触发「股票分析工作流」（复用 {@link WorkflowRunService}，前一只到达终态才启动下一只，绝不并发）；</li>
 *   <li>触发「每日复盘工作流」。</li>
 * </ol>
 * 单线程执行器 + AtomicBoolean 防重入；支持 POST /api/schedule/run-now 手动触发验证。
 * 工作流未发布（DRAFT）时跳过该项并记录原因，不阻断批处理其余步骤。
 */
@Service
public class DailyScheduleService {

    private static final Logger log = LoggerFactory.getLogger(DailyScheduleService.class);

    /** 单只股票工作流最长等待时间（分钟） */
    static final int PER_STOCK_TIMEOUT_MINUTES = 30;
    /** 状态轮询间隔（毫秒） */
    static final long POLL_INTERVAL_MS = 15_000;
    private static final List<String> TERMINAL_STATUSES = List.of("COMPLETED", "FAILED", "CANCELLED");

    /** 等待超时/轮询间隔实例字段（默认取上述常量，包级可写便于测试注入缩短） */
    long perStockTimeoutMs = PER_STOCK_TIMEOUT_MINUTES * 60_000L;
    long pollIntervalMs = POLL_INTERVAL_MS;

    private final WatchlistRepository watchlistRepo;
    private final WorkflowDefRepository workflowDefRepo;
    private final WorkflowRunService runService;

    /** 批处理串行执行器（后台单线程，避免占用请求线程） */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "daily-schedule");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 最近一次批处理执行摘要（供 /api/schedule/status 查询） */
    private volatile Map<String, Object> lastRunSummary = Map.of("state", "NEVER_RUN");

    public DailyScheduleService(WatchlistRepository watchlistRepo,
                                WorkflowDefRepository workflowDefRepo,
                                WorkflowRunService runService) {
        this.watchlistRepo = watchlistRepo;
        this.workflowDefRepo = workflowDefRepo;
        this.runService = runService;
    }

    /** 交易日（周一至周五）17:00 自动触发；法定节假日无当日数据，各工作流自会降级输出 */
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Shanghai")
    public void scheduledRun() {
        triggerRun("cron");
    }

    /**
     * 触发一次批处理（异步执行）。已有批处理在跑时拒绝重入。
     *
     * @return started=是否已受理 + reason
     */
    public Map<String, Object> triggerRun(String source) {
        if (!running.compareAndSet(false, true)) {
            log.warn("每日批处理已在运行中，忽略本次触发: source={}", source);
            return Map.of("started", false, "reason", "上一轮批处理仍在运行中，请稍后再试");
        }
        executor.submit(() -> {
            try {
                runBatch(source);
            } finally {
                running.set(false);
            }
        });
        return Map.of("started", true, "reason", "批处理已受理（后台串行执行），可通过 /api/schedule/status 查看进度");
    }

    /** 最近一次批处理摘要 + 当前是否在运行 */
    public Map<String, Object> status() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("running", running.get());
        resp.put("lastRun", lastRunSummary);
        return resp;
    }

    // ────────── 批处理主体 ──────────

    void runBatch(String source) {
        LocalDateTime startedAt = LocalDateTime.now();
        List<String> steps = new ArrayList<>();
        log.info("每日批处理开始: source={}", source);

        // (a) 市场数据抓取：data-fetcher Python 脚本独立运行（依赖 AKShare 环境），此处仅记录说明
        steps.add("数据抓取: 跳过（由 data-fetcher 独立脚本负责，可手动或另行调度执行）");

        // (b) 自选股逐只串行触发股票分析工作流
        steps.add(runWatchlistAnalysis());

        // (c) 触发每日复盘工作流
        steps.add(runDailyReview());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("state", "DONE");
        summary.put("source", source);
        summary.put("startedAt", startedAt.toString());
        summary.put("finishedAt", LocalDateTime.now().toString());
        summary.put("steps", steps);
        lastRunSummary = summary;
        log.info("每日批处理结束: {}", steps);
    }

    /** 自选股逐只串行分析：前一只到达终态（或超时）才启动下一只 */
    private String runWatchlistAnalysis() {
        WorkflowDef def = findPublished(WorkflowSeedService.STOCK_WORKFLOW_NAME);
        if (def == null) {
            return "自选股分析: 跳过（股票分析工作流不存在或未发布）";
        }
        List<Watchlist> items = watchlistRepo.findAll();
        if (items.isEmpty()) {
            return "自选股分析: 跳过（自选股列表为空）";
        }
        int ok = 0;
        int failed = 0;
        for (Watchlist item : items) {
            try {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("stockCode", item.getStockCode());
                input.put("stockName", item.getStockName());
                input.put("goal", "盘后例行全面分析（每日定时批处理）");
                String pid = runService.start(def.getId(), input);
                log.info("自选股分析已启动: {}({}) pid={}", item.getStockName(), item.getStockCode(), pid);
                String status = waitForTerminal(pid);
                if ("COMPLETED".equals(status)) {
                    ok++;
                } else {
                    failed++;
                    log.warn("自选股分析未正常完成: {}({}) status={}",
                            item.getStockName(), item.getStockCode(), status);
                }
            } catch (Exception e) {
                failed++;
                log.error("自选股分析启动失败（继续下一只）: {}({}) err={}",
                        item.getStockName(), item.getStockCode(), e.getMessage());
            }
        }
        return "自选股分析: 共 " + items.size() + " 只，成功 " + ok + "，异常 " + failed;
    }

    /** 触发每日复盘工作流（只启动不等待，作为批处理最后一步） */
    private String runDailyReview() {
        WorkflowDef def = findPublished(WorkflowSeedService.REVIEW_WORKFLOW_NAME);
        if (def == null) {
            return "每日复盘: 跳过（每日复盘工作流不存在或未发布）";
        }
        try {
            String pid = runService.start(def.getId(),
                    Map.of("goal", "盘后例行复盘（每日定时批处理）"));
            log.info("每日复盘工作流已启动: pid={}", pid);
            return "每日复盘: 已启动 pid=" + pid;
        } catch (Exception e) {
            log.error("每日复盘工作流启动失败: {}", e.getMessage());
            return "每日复盘: 启动失败（" + e.getMessage() + "）";
        }
    }

    /** 轮询流程状态直到终态或超时，返回最终状态（超时返回 TIMEOUT） */
    String waitForTerminal(String processInstanceId) {
        long deadline = System.currentTimeMillis() + perStockTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                WorkflowStatusView view = runService.getStatus(processInstanceId);
                if (TERMINAL_STATUSES.contains(view.getStatus())) {
                    return view.getStatus();
                }
            } catch (Exception e) {
                log.warn("轮询流程状态失败（继续等待）: pid={}, err={}", processInstanceId, e.getMessage());
            }
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "INTERRUPTED";
            }
        }
        return "TIMEOUT";
    }

    private WorkflowDef findPublished(String name) {
        return workflowDefRepo.findByName(name)
                .filter(def -> "PUBLISHED".equals(def.getStatus()))
                .orElse(null);
    }
}
