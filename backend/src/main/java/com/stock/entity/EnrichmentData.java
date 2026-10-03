package com.stock.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 智能体分析过程中的关键数据缓存表 — 大JSON方式存储。
 * <p>
 * 每次 DataEnricher 调用外部 LLM（DeepSeek/Kimi）获取的增强数据，
 * 以及 DataContextBuilder 构建的数据上下文，都异步保存到此表。
 * 后续相同 stock+direction 的分析可直接复用，避免重复 LLM 调用。
 */
@Entity
@Table(name = "enrichment_data", indexes = {
    @Index(name = "idx_enc_stock_dir", columnList = "stock_code, direction_key"),
    @Index(name = "idx_enc_created", columnList = "created_at")
})
@EntityListeners(AuditingEntityListener.class)
public class EnrichmentData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 股票代码（6位） */
    @Column(name = "stock_code", length = 6, nullable = false)
    private String stockCode;

    /** 分析方向/模块标识，如 SECTOR_ANALYSIS / CAPITAL_FLOW */
    @Column(name = "direction_key", length = 50, nullable = false)
    private String directionKey;

    /** prompt类型版本号，便于后续升级prompt时区分，如 sector_enrichment_v1 */
    @Column(name = "prompt_type", length = 100, nullable = false)
    private String promptType;

    /**
     * 数据上下文 JSON — DataContextBuilder.buildDataContext() 的完整输出。
     * 包含：K线（最高/最低/收盘）、MA均线、MACD/KDJ/RSI指标、财务数据等。
     * 存储为 JSON 结构化文本，便于后续直接复用。
     */
    @Column(name = "context_json", columnDefinition = "MEDIUMTEXT")
    private String contextJson;

    /**
     * 增强数据 JSON — LLM 返回的补充数据。
     * 结构: {"deepseek_result": "...", "kimi_result": "...", "combined_text": "..."}
     */
    @Column(name = "enrichment_json", columnDefinition = "MEDIUMTEXT")
    private String enrichmentJson;

    /** 数据来源模型：deepseek-v4 / deepseek-v4,kimi */
    @Column(name = "model_source", length = 100)
    private String modelSource;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 过期时间，null 表示永不过期 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    public EnrichmentData() {}

    // ─── Getters / Setters ───

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }

    public String getDirectionKey() { return directionKey; }
    public void setDirectionKey(String directionKey) { this.directionKey = directionKey; }

    public String getPromptType() { return promptType; }
    public void setPromptType(String promptType) { this.promptType = promptType; }

    public String getContextJson() { return contextJson; }
    public void setContextJson(String contextJson) { this.contextJson = contextJson; }

    public String getEnrichmentJson() { return enrichmentJson; }
    public void setEnrichmentJson(String enrichmentJson) { this.enrichmentJson = enrichmentJson; }

    public String getModelSource() { return modelSource; }
    public void setModelSource(String modelSource) { this.modelSource = modelSource; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
