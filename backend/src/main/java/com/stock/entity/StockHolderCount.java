package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股东户数历史（东财 stock_zh_a_gdhs_detail_em）。
 * <p>
 * (code, statDate) 唯一；户数为期末值，changeRatio 为较上期增减比例(%)。
 */
@Entity
@Table(name = "stock_holder_count", indexes = {
    @Index(name = "idx_holdercnt_code_date", columnList = "code, statDate DESC"),
    @Index(name = "idx_holdercnt_unique", columnList = "code, statDate", unique = true)
})
public class StockHolderCount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    /** 股东户数统计截止日 */
    @Column(nullable = false)
    private LocalDate statDate;

    /** 股东户数-本次 */
    private Long holderCount;

    /** 股东户数-上次 */
    private Long prevCount;

    /** 股东户数-增减比例(%) */
    @Column(precision = 12, scale = 4)
    private BigDecimal changeRatio;

    /** 户均持股数量(股) */
    @Column(precision = 20, scale = 2)
    private BigDecimal avgHoldShares;

    /** 户均持股市值(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal avgHoldValue;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockHolderCount() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public LocalDate getStatDate() { return statDate; }
    public void setStatDate(LocalDate statDate) { this.statDate = statDate; }
    public Long getHolderCount() { return holderCount; }
    public void setHolderCount(Long holderCount) { this.holderCount = holderCount; }
    public Long getPrevCount() { return prevCount; }
    public void setPrevCount(Long prevCount) { this.prevCount = prevCount; }
    public BigDecimal getChangeRatio() { return changeRatio; }
    public void setChangeRatio(BigDecimal changeRatio) { this.changeRatio = changeRatio; }
    public BigDecimal getAvgHoldShares() { return avgHoldShares; }
    public void setAvgHoldShares(BigDecimal avgHoldShares) { this.avgHoldShares = avgHoldShares; }
    public BigDecimal getAvgHoldValue() { return avgHoldValue; }
    public void setAvgHoldValue(BigDecimal avgHoldValue) { this.avgHoldValue = avgHoldValue; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
