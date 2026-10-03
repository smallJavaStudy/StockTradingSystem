package com.stock.workflow.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 工作流定义
 */
@Entity
@Table(name = "workflow_def")
@EntityListeners(AuditingEntityListener.class)
public class WorkflowDef {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    /** DEV_PROCESS / STOCK_ANALYSIS / CUSTOM */
    @Column(name = "category", length = 30)
    private String category;

    /** 前端画布 JSON 定义 */
    @Lob
    @Column(name = "definition_json", columnDefinition = "LONGTEXT")
    private String definitionJson;

    @Column(name = "process_definition_key", length = 100)
    private String processDefinitionKey;

    @Column(name = "deployment_id", length = 64)
    private String deploymentId;

    /** DRAFT / PUBLISHED / ARCHIVED */
    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "version")
    private Integer version;

    /** USER / AGENT */
    @Column(name = "created_by", length = 20)
    private String createdBy;

    /** 种子版本号：null=用户创建；非空=由种子机制管理，低于目标版本时启动升级 */
    @Column(name = "seed_version")
    private Integer seedVersion;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public WorkflowDef() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }
    public String getProcessDefinitionKey() { return processDefinitionKey; }
    public void setProcessDefinitionKey(String processDefinitionKey) { this.processDefinitionKey = processDefinitionKey; }
    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Integer getSeedVersion() { return seedVersion; }
    public void setSeedVersion(Integer seedVersion) { this.seedVersion = seedVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
