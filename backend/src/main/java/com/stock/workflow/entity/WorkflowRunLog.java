package com.stock.workflow.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 工作流节点执行日志
 */
@Entity
@Table(name = "workflow_run_log",
        indexes = @Index(name = "idx_wf_run_log_proc_inst", columnList = "process_instance_id"))
@EntityListeners(AuditingEntityListener.class)
public class WorkflowRunLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_def_id")
    private Long workflowDefId;

    @Column(name = "process_instance_id", length = 64)
    private String processInstanceId;

    @Column(name = "node_id", length = 100)
    private String nodeId;

    @Column(name = "node_name", length = 100)
    private String nodeName;

    @Column(name = "agent_id")
    private Long agentId;

    /** RUNNING / COMPLETED / FAILED */
    @Column(name = "status", length = 20)
    private String status;

    @Lob
    @Column(name = "input_text", columnDefinition = "LONGTEXT")
    private String inputText;

    @Lob
    @Column(name = "output_text", columnDefinition = "LONGTEXT")
    private String outputText;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @CreatedDate
    @Column(name = "started_at", updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public WorkflowRunLog() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getWorkflowDefId() { return workflowDefId; }
    public void setWorkflowDefId(Long workflowDefId) { this.workflowDefId = workflowDefId; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInputText() { return inputText; }
    public void setInputText(String inputText) { this.inputText = inputText; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
