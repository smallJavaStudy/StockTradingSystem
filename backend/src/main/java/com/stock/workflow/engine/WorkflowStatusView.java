package com.stock.workflow.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程运行状态合成视图（流程实例状态 + 节点级状态）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowStatusView {

    private String processInstanceId;
    /** RUNNING / COMPLETED / FAILED / CANCELLED */
    private String status;
    private List<NodeStatusView> nodes = new ArrayList<>();

    public WorkflowStatusView() {}

    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<NodeStatusView> getNodes() { return nodes; }
    public void setNodes(List<NodeStatusView> nodes) { this.nodes = nodes; }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NodeStatusView {
        private String nodeId;
        private String nodeName;
        /** PENDING / RUNNING / COMPLETED / FAILED */
        private String status;
        /** 输出（截断 60000 字符） */
        private String output;
        private String errorMessage;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;

        public NodeStatusView() {}

        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }
        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public LocalDateTime getStartedAt() { return startedAt; }
        public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    }
}
