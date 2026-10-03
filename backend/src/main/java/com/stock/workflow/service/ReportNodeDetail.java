package com.stock.workflow.service;

import java.time.LocalDateTime;

/**
 * 报告库详情中的单节点明细（含完整输出）。
 */
public record ReportNodeDetail(
        String nodeId,
        String nodeName,
        String status,
        String output,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long durationMs) {
}
