package com.stock.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_analysis_session")
@EntityListeners(AuditingEntityListener.class)
public class AgentAnalysisSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 36, unique = true, nullable = false)
    private String sessionId;

    @Column(name = "stock_code", length = 6, nullable = false)
    private String stockCode;

    @Column(name = "stock_name", length = 50)
    private String stockName;

    @Column(name = "model_choice", length = 20, nullable = false)
    private String modelChoice;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "total_elapsed_ms")
    private Long totalElapsedMs;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public AgentAnalysisSession() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getModelChoice() { return modelChoice; }
    public void setModelChoice(String modelChoice) { this.modelChoice = modelChoice; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getTotalElapsedMs() { return totalElapsedMs; }
    public void setTotalElapsedMs(Long totalElapsedMs) { this.totalElapsedMs = totalElapsedMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
