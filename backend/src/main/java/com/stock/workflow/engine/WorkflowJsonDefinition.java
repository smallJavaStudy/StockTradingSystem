package com.stock.workflow.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 简化工作流 JSON 定义（WorkflowDef.definitionJson 的反序列化载体）。
 * <pre>
 * {
 *   "name": "开发工作流",
 *   "nodes": [ { "id": "n1", "name": "需求编写", "agentId": 1,
 *                "promptTemplate": "根据目标 ${goal} 编写需求文档",
 *                "timeoutSeconds": 600, "dependsOn": [] } ]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowJsonDefinition {

    private String name;
    private List<WorkflowJsonNode> nodes = new ArrayList<>();

    public WorkflowJsonDefinition() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<WorkflowJsonNode> getNodes() { return nodes; }
    public void setNodes(List<WorkflowJsonNode> nodes) { this.nodes = nodes; }
}
