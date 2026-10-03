package com.stock.workflow.engine;

import com.stock.service.MarketSentimentService;
import com.stock.service.StockDataAcquisitionService;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.entity.WorkflowRunLog;
import com.stock.workflow.repository.WorkflowDefRepository;
import com.stock.workflow.repository.WorkflowRunLogRepository;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.*;

/**
 * 工作流运行服务：启动 / 取消 / 状态合成视图。
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);
    private static final int OUTPUT_TRUNCATE_CHARS = WorkflowTextUtils.OUTPUT_TRUNCATE_CHARS;
    /** MARKET_REVIEW 类工作流预注入的情绪指标变量名（_ 开头，前端执行弹窗跳过） */
    public static final String VAR_SENTIMENT = "_sentiment_context";
    static final int SENTIMENT_CONTEXT_DAYS = 10;

    private final WorkflowDefRepository workflowDefRepo;
    private final WorkflowRunLogRepository runLogRepo;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final ManagementService managementService;
    private final StockContextPreloader stockContextPreloader;
    private final MarketSentimentService marketSentimentService;
    private final StockDataAcquisitionService stockDataAcquisitionService;

    public WorkflowRunService(WorkflowDefRepository workflowDefRepo,
                              WorkflowRunLogRepository runLogRepo,
                              RuntimeService runtimeService,
                              HistoryService historyService,
                              ManagementService managementService,
                              StockContextPreloader stockContextPreloader,
                              MarketSentimentService marketSentimentService,
                              StockDataAcquisitionService stockDataAcquisitionService) {
        this.workflowDefRepo = workflowDefRepo;
        this.runLogRepo = runLogRepo;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.managementService = managementService;
        this.stockContextPreloader = stockContextPreloader;
        this.marketSentimentService = marketSentimentService;
        this.stockDataAcquisitionService = stockDataAcquisitionService;
    }

    /**
     * 启动流程实例。
     *
     * @param inputVars 启动输入变量（如 goal），供节点 promptTemplate 的 ${goal} 插值
     * @return processInstanceId
     */
    public String start(Long workflowDefId, Map<String, Object> inputVars) {
        WorkflowDef def = workflowDefRepo.findById(workflowDefId)
                .orElseThrow(() -> new NoSuchElementException("工作流定义不存在: id=" + workflowDefId));
        if (!"PUBLISHED".equals(def.getStatus()) || def.getProcessDefinitionKey() == null) {
            throw new IllegalStateException(
                    "工作流 [" + def.getName() + "] 尚未发布（status=" + def.getStatus() + "），请先部署");
        }

        Map<String, Object> vars = new HashMap<>();
        if (inputVars != null) {
            // 启动输入写入流程变量前净化非 BMP 字符（MySQL utf8 三字节无法存 emoji）
            inputVars.forEach((k, v) -> vars.put(k,
                    v instanceof String s ? WorkflowTextUtils.stripNonBmp(s) : v));
        }
        vars.put("workflowDefId", def.getId());

        // 股票分析类工作流：从 DB 预注入真实数据上下文（_xxx_context 变量契约）
        preloadStockContexts(def, vars);
        // 市场复盘类工作流：预注入多日情绪指标序列（_sentiment_context）
        preloadSentimentContext(def, vars);

        ProcessInstance instance =
                runtimeService.startProcessInstanceByKey(def.getProcessDefinitionKey(), vars);
        log.info("工作流启动: defId={}, processKey={}, processInstanceId={}",
                workflowDefId, def.getProcessDefinitionKey(), instance.getId());
        return instance.getId();
    }

    /**
     * 股票分析预注入：category == STOCK_ANALYSIS 且输入含 stockCode 时，先走数据准备闸门
     * （本地库缺行情/K线/财务/资金流时自动从东财拉取），再预注入五个 _xxx_context 变量；
     * stockName 用户未填时从 DB 回填。K线拉不到则抛异常阻断启动（快速失败，
     * 避免 11 个 LLM 节点在数据真空上空跑烧 token）；预注入本身的异常只记日志不中断。
     */
    private void preloadStockContexts(WorkflowDef def, Map<String, Object> vars) {
        if (!"STOCK_ANALYSIS".equals(def.getCategory())) {
            return;
        }
        Object codeVar = vars.get("stockCode");
        String stockCode = codeVar != null ? String.valueOf(codeVar).trim() : "";
        if (stockCode.isEmpty() || "null".equals(stockCode)) {
            log.warn("STOCK_ANALYSIS 工作流 [{}] 启动输入缺 stockCode，跳过数据预注入", def.getName());
            return;
        }
        // 数据准备闸门：缺数自动拉取；拉不到 K线则阻断启动（IllegalStateException → 409）
        StockDataAcquisitionService.AcquisitionResult acq =
                stockDataAcquisitionService.ensureStockData(stockCode);
        if (!acq.tradeable()) {
            throw new IllegalStateException("股票 " + stockCode + " 数据不足且自动拉取失败（K线 "
                    + acq.klineCount() + " 条，低于可分析阈值 "
                    + StockDataAcquisitionService.MIN_KLINES_FOR_TRADEABLE
                    + "）：" + acq.detail()
                    + "。已阻断工作流启动以避免 LLM 空跑；请确认股票代码有效、东财接口可达后重试");
        }
        try {
            Map<String, Object> contexts = stockContextPreloader.buildContextVariables(stockCode);
            vars.putAll(contexts);
            Object nameVar = vars.get("stockName");
            if (nameVar == null || String.valueOf(nameVar).isBlank()) {
                String dbName = stockContextPreloader.resolveStockName(stockCode);
                if (dbName != null) {
                    vars.put("stockName", dbName);
                }
            }
            log.info("股票数据预注入完成: code={}, 变量={}", stockCode, contexts.keySet());
        } catch (Exception e) {
            // 兜底：预注入失败不得中断流程，占位变量由插值处缺失补空串
            log.error("股票数据预注入失败（不中断流程）: code={}, err={}", stockCode, e.getMessage());
        }
    }

    /**
     * 市场复盘预注入：category == MARKET_REVIEW 时计算最近 N 日情绪指标序列写入
     * _sentiment_context 变量（供 PROMPT 型情绪节点使用）。任何异常只记日志不中断流程启动。
     */
    private void preloadSentimentContext(WorkflowDef def, Map<String, Object> vars) {
        if (!"MARKET_REVIEW".equals(def.getCategory())) {
            return;
        }
        try {
            vars.put(VAR_SENTIMENT, marketSentimentService.buildSentimentContext(SENTIMENT_CONTEXT_DAYS));
            log.info("市场情绪指标预注入完成: workflow={}", def.getName());
        } catch (Exception e) {
            // 兜底：预注入失败不得中断流程，占位变量置缺数声明供提示词降级处理
            log.error("市场情绪指标预注入失败（不中断流程）: workflow={}, err={}", def.getName(), e.getMessage());
            vars.putIfAbsent(VAR_SENTIMENT, "情绪指标数据未注入（计算失败：" + e.getMessage() + "）");
        }
    }

    /** 取消运行中的流程实例 */
    public void cancel(String processInstanceId, String reason) {
        runtimeService.deleteProcessInstance(processInstanceId,
                reason != null ? reason : "用户取消");
        log.info("工作流取消: processInstanceId={}, reason={}", processInstanceId, reason);
    }

    /**
     * 状态合成视图：
     * 流程实例状态（RuntimeService/HistoryService）+ 节点状态（WorkflowRunLog 为主，
     * HistoricActivityInstance 兜底补齐尚无日志的节点）。
     */
    public WorkflowStatusView getStatus(String processInstanceId) {
        HistoricProcessInstance hpi = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (hpi == null) {
            throw new NoSuchElementException("流程实例不存在: " + processInstanceId);
        }

        List<WorkflowRunLog> logs =
                runLogRepo.findByProcessInstanceIdOrderByStartedAtAsc(processInstanceId);
        boolean anyNodeFailed = logs.stream().anyMatch(l -> "FAILED".equals(l.getStatus()));

        // ── 流程级状态 ──
        String status;
        if (hpi.getEndTime() != null) {
            if (hpi.getDeleteReason() != null && !hpi.getDeleteReason().isBlank()) {
                status = "CANCELLED";
            } else {
                status = anyNodeFailed ? "FAILED" : "COMPLETED";
            }
        } else {
            long deadLetterJobs = managementService.createDeadLetterJobQuery()
                    .processInstanceId(processInstanceId).count();
            status = (deadLetterJobs > 0 || anyNodeFailed) ? "FAILED" : "RUNNING";
        }

        // ── 节点级状态：RunLog 为主（同一 nodeId 取最新一条）──
        WorkflowStatusView view = new WorkflowStatusView();
        view.setProcessInstanceId(processInstanceId);
        view.setStatus(status);

        Map<String, WorkflowStatusView.NodeStatusView> nodeViews = new LinkedHashMap<>();
        for (WorkflowRunLog runLog : logs) {
            WorkflowStatusView.NodeStatusView nv = new WorkflowStatusView.NodeStatusView();
            nv.setNodeId(runLog.getNodeId());
            nv.setNodeName(runLog.getNodeName());
            nv.setStatus(runLog.getStatus());
            nv.setOutput(truncate(runLog.getOutputText()));
            nv.setErrorMessage(runLog.getErrorMessage());
            nv.setStartedAt(runLog.getStartedAt());
            nv.setCompletedAt(runLog.getCompletedAt());
            nodeViews.put(runLog.getNodeId(), nv); // 后写覆盖（日志按 startedAt 升序，保留最新）
        }

        // ── HistoricActivityInstance 兜底：已进入引擎但尚未写 RunLog 的 ServiceTask ──
        try {
            List<HistoricActivityInstance> activities = historyService
                    .createHistoricActivityInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .activityType("serviceTask")
                    .list();
            for (HistoricActivityInstance act : activities) {
                String nodeId = stripTaskPrefix(act.getActivityId());
                if (nodeViews.containsKey(nodeId)) {
                    continue;
                }
                WorkflowStatusView.NodeStatusView nv = new WorkflowStatusView.NodeStatusView();
                nv.setNodeId(nodeId);
                nv.setNodeName(act.getActivityName());
                nv.setStatus(act.getEndTime() == null ? "RUNNING" : "COMPLETED");
                if (act.getStartTime() != null) {
                    nv.setStartedAt(act.getStartTime().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDateTime());
                }
                if (act.getEndTime() != null) {
                    nv.setCompletedAt(act.getEndTime().toInstant()
                            .atZone(ZoneId.systemDefault()).toLocalDateTime());
                }
                nodeViews.put(nodeId, nv);
            }
        } catch (Exception e) {
            log.warn("查询 HistoricActivityInstance 失败: {}", e.getMessage());
        }

        view.setNodes(new ArrayList<>(nodeViews.values()));
        return view;
    }

    private String truncate(String text) {
        return WorkflowTextUtils.truncate(text, OUTPUT_TRUNCATE_CHARS);
    }

    private String stripTaskPrefix(String activityId) {
        if (activityId != null && activityId.startsWith(JsonToBpmnConverter.TASK_ID_PREFIX)) {
            return activityId.substring(JsonToBpmnConverter.TASK_ID_PREFIX.length());
        }
        return activityId;
    }
}
