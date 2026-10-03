package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价格预警：AlertScanService 每 5 分钟扫描 enabled 且未触发的预警，
 * 对照 StockQuote 最新价，命中后置 triggered=true（一次性触发，不重复提醒）。
 */
@Entity
@Table(name = "price_alert", indexes = {
    @Index(name = "idx_alert_code", columnList = "code"),
    @Index(name = "idx_alert_scan", columnList = "enabled, triggered")
})
public class PriceAlert {

    /** 预警类型 */
    public enum AlertType {
        /** 价格上穿阈值 */
        PRICE_ABOVE,
        /** 价格下破阈值 */
        PRICE_BELOW
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertType type;

    /** 阈值价格(元) */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal threshold;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Boolean triggered = false;

    private LocalDateTime triggeredAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public PriceAlert() {}

    public PriceAlert(String code, AlertType type, BigDecimal threshold) {
        this.code = code;
        this.type = type;
        this.threshold = threshold;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public AlertType getType() { return type; }
    public void setType(AlertType type) { this.type = type; }
    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getTriggered() { return triggered; }
    public void setTriggered(Boolean triggered) { this.triggered = triggered; }
    public LocalDateTime getTriggeredAt() { return triggeredAt; }
    public void setTriggeredAt(LocalDateTime triggeredAt) { this.triggeredAt = triggeredAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
