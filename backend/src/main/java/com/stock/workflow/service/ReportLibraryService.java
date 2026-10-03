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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * 报告库检索服务：基于 workflow_run_log 按运行（process_instance_id）聚合检索，
 * 股票代码从 Flowable 历史流程变量（stockCode/stockName）提取，可空。
 */
@Service
public class ReportLibraryService {

    private static final Logger log = LoggerFactory.getLogger(ReportLibraryService.class);

    /** IN 条件占位哨兵：filter 标志为 false 时保证列表非空，防 JPQL 空 IN */
    private static final List<Long> SENTINEL_DEF_IDS = List.of(-1L);
    private static final List<String> SENTINEL_PIDS = List.of("-");

    static final String VAR_STOCK_CODE = "stockCode";
    static final String VAR_STOCK_NAME = "stockName";

    private final ReportRunLogRepository reportRepo;
    private final WorkflowDefRepository workflowDefRepo;
    private final HistoryService historyService;

    public ReportLibraryService(ReportRunLogRepository reportRepo,
                                WorkflowDefRepository workflowDefRepo,
                                HistoryService historyService) {
        this.reportRepo = reportRepo;
        this.workflowDefRepo = workflowDefRepo;
        this.historyService = historyService;
    }

    /**
     * 分页检索运行级汇总（时间倒序）。
     *
     * @param stockCode    精确匹配启动变量 stockCode，可空
     * @param workflowName 工作流名模糊匹配（忽略大小写），可空
     * @param startDate    开始日期（含当天 00:00:00），可空
     * @param endDate      结束日期（含当天 23:59:59），可空
     */
    public Page<ReportRunSummary> search(String stockCode, String workflowName,
                                         LocalDate startDate, LocalDate endDate,
                                         int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));

        // ── 工作流名 → defId 集合（定义表数据量小，内存过滤即可） ──
        boolean filterDefIds = workflowName != null && !workflowName.isBlank();
        List<Long> defIds = SENTINEL_DEF_IDS;
        if (filterDefIds) {
            String kw = workflowName.trim().toLowerCase();
            defIds = workflowDefRepo.findAll().stream()
                    .filter(d -> d.getName() != null && d.getName().toLowerCase().contains(kw))
                    .map(WorkflowDef::getId)
                    .toList();
            if (defIds.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, 0);
            }
        }

        // ── 股票代码 → 历史流程变量精确匹配的实例 id 集合 ──
        boolean filterPids = stockCode != null && !stockCode.isBlank();
        List<String> pids = SENTINEL_PIDS;
        if (filterPids) {
            pids = findProcessInstanceIdsByStockCode(stockCode.trim());
            if (pids.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, 0);
            }
        }

        LocalDateTime startTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endTime = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        Page<RunSummaryRow> rows = reportRepo.findRunSummaries(
                filterDefIds, defIds, filterPids, pids, startTime, endTime, pageable);
        if (rows.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, rows.getTotalElements());
        }

        // ── 批量补齐：工作流名 / 最终输出预览 / 股票代码 ──
        List<String> pagePids = rows.getContent().stream()
                .map(RunSummaryRow::getProcessInstanceId).toList();
        Map<Long, String> defNames = loadDefNames(rows.getContent());
        Map<String, String> previews = loadFinalPreviews(pagePids);

        List<ReportRunSummary> items = rows.getContent().stream().map(row -> {
            String status = deriveStatus(row.getFailedCount(), row.getRunningCount());
            boolean running = "RUNNING".equals(status);
            return new ReportRunSummary(
                    row.getProcessInstanceId(),
                    defNames.get(row.getWorkflowDefId()),
                    lookupVariable(row.getProcessInstanceId(), VAR_STOCK_CODE),
                    row.getStartTime(),
                    running ? null : row.getEndTime(),
                    status,
                    row.getNodeCount() != null ? row.getNodeCount() : 0,
                    previews.get(row.getProcessInstanceId()));
        }).toList();
        return new PageImpl<>(items, pageable, rows.getTotalElements());
    }

    /** 单次运行全部节点明细（同一 nodeId 多条日志时保留最新一条，与执行状态视图口径一致） */
    public ReportRunDetail getRunDetail(String runId) {
        List<WorkflowRunLog> logs = reportRepo.findByProcessInstanceIdOrderByStartedAtAscIdAsc(runId);
        if (logs.isEmpty()) {
            throw new NoSuchElementException("运行记录不存在: " + runId);
        }

        Map<String, WorkflowRunLog> latestByNode = new LinkedHashMap<>();
        for (WorkflowRunLog runLog : logs) {
            latestByNode.put(runLog.getNodeId(), runLog); // 升序遍历，后写覆盖保留最新
        }

        List<ReportNodeDetail> nodes = latestByNode.values().stream()
                .map(l -> new ReportNodeDetail(
                        l.getNodeId(), l.getNodeName(), l.getStatus(),
                        l.getOutputText(), l.getErrorMessage(),
                        l.getStartedAt(), l.getCompletedAt(),
                        durationMs(l.getStartedAt(), l.getCompletedAt())))
                .toList();

        long failed = nodes.stream().filter(n -> "FAILED".equals(n.status())).count();
        long running = nodes.stream().filter(n -> "RUNNING".equals(n.status())).count();
        String status = deriveStatus(failed, running);

        LocalDateTime startTime = logs.stream().map(WorkflowRunLog::getStartedAt)
                .filter(t -> t != null).min(LocalDateTime::compareTo).orElse(null);
        LocalDateTime endTime = "RUNNING".equals(status) ? null
                : logs.stream().map(WorkflowRunLog::getCompletedAt)
                        .filter(t -> t != null).max(LocalDateTime::compareTo).orElse(null);

        Long defId = logs.get(0).getWorkflowDefId();
        String workflowName = defId == null ? null
                : workflowDefRepo.findById(defId).map(WorkflowDef::getName).orElse(null);

        return new ReportRunDetail(runId, workflowName,
                lookupVariable(runId, VAR_STOCK_CODE),
                lookupVariable(runId, VAR_STOCK_NAME),
                status, startTime, endTime, new ArrayList<>(nodes));
    }

    // ────────── 私有辅助 ──────────

    /** 运行级状态口径：任一节点 FAILED → FAILED；否则有 RUNNING → RUNNING；否则 COMPLETED */
    private String deriveStatus(Long failedCount, Long runningCount) {
        if (failedCount != null && failedCount > 0) return "FAILED";
        if (runningCount != null && runningCount > 0) return "RUNNING";
        return "COMPLETED";
    }

    private Long durationMs(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return null;
        return Duration.between(start, end).toMillis();
    }

    private Map<Long, String> loadDefNames(List<RunSummaryRow> rows) {
        List<Long> ids = rows.stream().map(RunSummaryRow::getWorkflowDefId)
                .filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return workflowDefRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(WorkflowDef::getId, WorkflowDef::getName, (a, b) -> a));
    }

    /** 每运行最终节点输出预览：查询按时间升序，后写覆盖即保留最后一条 */
    private Map<String, String> loadFinalPreviews(List<String> pids) {
        Map<String, String> previews = new HashMap<>();
        for (PreviewRow row : reportRepo.findOutputPreviews(pids)) {
            previews.put(row.getProcessInstanceId(), row.getPreview());
        }
        return previews;
    }

    /** 按 stockCode 精确匹配历史流程变量，返回实例 id 列表（历史缺失时返回空） */
    private List<String> findProcessInstanceIdsByStockCode(String stockCode) {
        try {
            return historyService.createHistoricProcessInstanceQuery()
                    .variableValueEquals(VAR_STOCK_CODE, stockCode)
                    .list().stream()
                    .map(HistoricProcessInstance::getId)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            log.warn("按 stockCode 检索历史流程实例失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 提取单实例的指定启动变量值，历史缺失/异常时返回 null（字段可空契约） */
    private String lookupVariable(String processInstanceId, String variableName) {
        try {
            HistoricVariableInstance var = historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .variableName(variableName)
                    .singleResult();
            if (var == null || var.getValue() == null) return null;
            String value = String.valueOf(var.getValue()).trim();
            return value.isEmpty() || "null".equals(value) ? null : value;
        } catch (Exception e) {
            log.debug("读取历史变量失败: pid={}, var={}, err={}", processInstanceId, variableName, e.getMessage());
            return null;
        }
    }
}
