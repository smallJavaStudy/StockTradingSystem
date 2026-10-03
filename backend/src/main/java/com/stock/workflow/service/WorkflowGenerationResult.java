package com.stock.workflow.service;

import com.stock.workflow.entity.WorkflowDef;

/**
 * AI 生成工作流的结果载体：生成的 WorkflowDef + 是否回退到内置模板 + 说明信息。
 */
public class WorkflowGenerationResult {

    private final WorkflowDef workflow;
    private final boolean fallback;
    private final String message;

    public WorkflowGenerationResult(WorkflowDef workflow, boolean fallback, String message) {
        this.workflow = workflow;
        this.fallback = fallback;
        this.message = message != null ? message : "";
    }

    public WorkflowDef getWorkflow() { return workflow; }
    public boolean isFallback() { return fallback; }
    public String getMessage() { return message; }
}
