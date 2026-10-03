package com.stock.workflow.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报告库详情：一次运行的元信息 + 全部节点明细。
 */
public record ReportRunDetail(
        String runId,
        String workflowName,
        String stockCode,
        String stockName,
        String status,
        LocalDateTime startTime,
        LocalDateTime endTime,
        List<ReportNodeDetail> nodes) {
}
