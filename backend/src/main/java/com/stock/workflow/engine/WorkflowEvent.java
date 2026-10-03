package com.stock.workflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工作流 SSE 事件载体。
 * type: NODE_STARTED | NODE_DELTA | NODE_COMPLETED | NODE_FAILED
 *       | PROCESS_COMPLETED | PROCESS_CANCELLED | PROCESS_FAILED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowEvent {

    public static final String NODE_STARTED = "NODE_STARTED";
    public static final String NODE_DELTA = "NODE_DELTA";
    public static final String NODE_COMPLETED = "NODE_COMPLETED";
    public static final String NODE_FAILED = "NODE_FAILED";
    public static final String PROCESS_COMPLETED = "PROCESS_COMPLETED";
    public static final String PROCESS_CANCELLED = "PROCESS_CANCELLED";
    public static final String PROCESS_FAILED = "PROCESS_FAILED";

    private String type;
    private String processInstanceId;
    private String nodeId;
    private String nodeName;
    private String delta;
    private String output;
    private String error;
    private long timestamp = System.currentTimeMillis();

    public WorkflowEvent() {}

    public WorkflowEvent(String type, String processInstanceId) {
        this.type = type;
        this.processInstanceId = processInstanceId;
    }

    public static WorkflowEvent of(String type, String processInstanceId) {
        return new WorkflowEvent(type, processInstanceId);
    }

    public static WorkflowEvent nodeEvent(String type, String processInstanceId,
                                          String nodeId, String nodeName) {
        WorkflowEvent e = new WorkflowEvent(type, processInstanceId);
        e.nodeId = nodeId;
        e.nodeName = nodeName;
        return e;
    }

    public WorkflowEvent withDelta(String delta) { this.delta = delta; return this; }
    public WorkflowEvent withOutput(String output) { this.output = output; return this; }
    public WorkflowEvent withError(String error) { this.error = error; return this; }

    /** 是否为流程级终止事件 */
    public boolean isTerminal() {
        return PROCESS_COMPLETED.equals(type)
                || PROCESS_CANCELLED.equals(type)
                || PROCESS_FAILED.equals(type);
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getDelta() { return delta; }
    public void setDelta(String delta) { this.delta = delta; }
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
