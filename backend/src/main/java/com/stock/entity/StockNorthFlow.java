package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 北向资金每日净流入（东财 stock_hsgt_hist_em，市场级无 code）。
 * <p>
 * 注意：2024-08-19 起交易所停止披露北向逐日成交净买额，
 * 有效历史数据截至 2024-08-16；此后行接口返回 NaN，不入库。
 * 金额单位：netFlow/accumFlow 均为亿元。
 */
@Entity
@Table(name = "stock_north_flow", indexes = {
    @Index(name = "idx_northflow_date_unique", columnList = "tradeDate", unique = true)
})
public class StockNorthFlow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 当日成交净买额(亿元) */
    @Column(precision = 16, scale = 4)
    private BigDecimal netFlow;

    /** 历史累计净买额(亿元) */
    @Column(precision = 18, scale = 4)
    private BigDecimal accumFlow;

    /** 当日买入成交额(亿元) */
    @Column(precision = 16, scale = 4)
    private BigDecimal buyAmount;

    /** 当日卖出成交额(亿元) */
    @Column(precision = 16, scale = 4)
    private BigDecimal sellAmount;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockNorthFlow() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public BigDecimal getNetFlow() { return netFlow; }
    public void setNetFlow(BigDecimal netFlow) { this.netFlow = netFlow; }
    public BigDecimal getAccumFlow() { return accumFlow; }
    public void setAccumFlow(BigDecimal accumFlow) { this.accumFlow = accumFlow; }
    public BigDecimal getBuyAmount() { return buyAmount; }
    public void setBuyAmount(BigDecimal buyAmount) { this.buyAmount = buyAmount; }
    public BigDecimal getSellAmount() { return sellAmount; }
    public void setSellAmount(BigDecimal sellAmount) { this.sellAmount = sellAmount; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
