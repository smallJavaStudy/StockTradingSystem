package com.stock.workflow.service;

import java.time.LocalDateTime;

/**
 * 报告库列表项：一次工作流运行的汇总视图。
 */
public record ReportRunSummary(
        String runId,
        String workflowName,
        String stockCode,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        long nodeCount,
        String finalOutputPreview) {
}
