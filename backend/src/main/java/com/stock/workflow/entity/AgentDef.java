package com.stock.workflow.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 工作流智能体节点定义
 */
@Entity
@Table(name = "workflow_agent_def")
@EntityListeners(AuditingEntityListener.class)
public class AgentDef {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    /** PROMPT / AGENTSCOPE */
    @Column(name = "type", length = 20)
    private String type;

    @Lob
    @Column(name = "system_prompt", columnDefinition = "LONGTEXT")
    private String systemPrompt;

    @Column(name = "model_choice", length = 30)
    private String modelChoice;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "tools_json", length = 2000)
    private String toolsJson;

    @Column(name = "max_iterations")
    private Integer maxIterations;

    @Column(name = "loop_depth")
    private Integer loopDepth;

    @Column(name = "events_json", length = 2000)
    private String eventsJson;

    /** 种子版本号：null=用户创建；非空=由种子机制管理，低于目标版本时启动升级 */
    @Column(name = "seed_version")
    private Integer seedVersion;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public AgentDef() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getModelChoice() { return modelChoice; }
    public void setModelChoice(String modelChoice) { this.modelChoice = modelChoice; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public String getToolsJson() { return toolsJson; }
    public void setToolsJson(String toolsJson) { this.toolsJson = toolsJson; }
    public Integer getMaxIterations() { return maxIterations; }
    public void setMaxIterations(Integer maxIterations) { this.maxIterations = maxIterations; }
    public Integer getLoopDepth() { return loopDepth; }
    public void setLoopDepth(Integer loopDepth) { this.loopDepth = loopDepth; }
    public String getEventsJson() { return eventsJson; }
    public void setEventsJson(String eventsJson) { this.eventsJson = eventsJson; }
    public Integer getSeedVersion() { return seedVersion; }
    public void setSeedVersion(Integer seedVersion) { this.seedVersion = seedVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
