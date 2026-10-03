package com.stock.workflow.controller;

import com.stock.workflow.engine.WorkflowRunService;
import com.stock.workflow.engine.WorkflowSseService;
import com.stock.workflow.engine.WorkflowStatusView;
import org.flowable.engine.HistoryService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流执行实例：状态查询 / SSE 事件流 / 取消 / 历史实例列表。
 */
@RestController
@RequestMapping("/api/v1/workflow-execution")
public class WorkflowExecutionController {

    private final WorkflowRunService runService;
    private final WorkflowSseService sseService;
    private final HistoryService historyService;

    public WorkflowExecutionController(WorkflowRunService runService,
                                       WorkflowSseService sseService,
                                       HistoryService historyService) {
        this.runService = runService;
        this.sseService = sseService;
        this.historyService = historyService;
    }

    /** 流程实例状态合成视图（流程级状态 + 节点级状态） */
    @GetMapping("/{processInstanceId}")
    public WorkflowStatusView getStatus(@PathVariable String processInstanceId) {
        return runService.getStatus(processInstanceId);
    }

    /** SSE 事件流（先回放历史事件再实时推送，事件名 workflow-event） */
    @GetMapping(value = "/{processInstanceId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String processInstanceId) {
        return sseService.subscribe(processInstanceId);
    }

    /** 取消运行中的流程实例（body 可选: {reason}） */
    @PostMapping("/{processInstanceId}/cancel")
    public Map<String, Object> cancel(@PathVariable String processInstanceId,
                                      @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        runService.cancel(processInstanceId, reason);
        return Map.of("cancelled", true, "processInstanceId", processInstanceId);
    }

    /**
     * 某工作流定义的历史实例列表（含运行中），按启动时间倒序。
     * 基于 Flowable HistoryService：启动时 WorkflowRunService 已将 workflowDefId 写入流程变量。
     */
    @GetMapping("/history/{workflowDefId}")
    public List<Map<String, Object>> history(@PathVariable Long workflowDefId) {
        List<HistoricProcessInstance> instances = historyService
                .createHistoricProcessInstanceQuery()
                .variableValueEquals("workflowDefId", workflowDefId)
                .orderByProcessInstanceStartTime().desc()
                .list();

        List<Map<String, Object>> result = new ArrayList<>();
        for (HistoricProcessInstance hpi : instances) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("processInstanceId", hpi.getId());
            item.put("status", resolveStatus(hpi));
            item.put("startedAt", toLocalDateTime(hpi.getStartTime()));
            item.put("completedAt", toLocalDateTime(hpi.getEndTime()));
            result.add(item);
        }
        return result;
    }

    /** 历史列表用的轻量状态判定（不逐实例扫描 RunLog，精确状态以 getStatus 为准） */
    private String resolveStatus(HistoricProcessInstance hpi) {
        if (hpi.getEndTime() == null) {
            return "RUNNING";
        }
        if (hpi.getDeleteReason() != null && !hpi.getDeleteReason().isBlank()) {
            return "CANCELLED";
        }
        return "COMPLETED";
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date == null ? null
                : date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
