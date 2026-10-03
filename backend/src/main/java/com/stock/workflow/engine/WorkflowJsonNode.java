package com.stock.workflow.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 简化工作流 JSON 中的单个节点定义。dependsOn 表达 DAG 依赖。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowJsonNode {

    private String id;
    private String name;
    private Long agentId;
    private String promptTemplate;
    /** 节点执行超时（秒），null 时由执行侧取默认值 */
    private Integer timeoutSeconds;
    private List<String> dependsOn = new ArrayList<>();

    public WorkflowJsonNode() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
}
