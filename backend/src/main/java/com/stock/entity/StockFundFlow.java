package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 个股每日资金流（主力/大单/中单/小单净流入）。
 * <p>
 * 与 {@link StockKlineDaily} 同构的按日数据表；当前 DB 中该表可能为空，
 * 消费方（工作流预注入 / stock_fundflow 工具）在无数据时返回"暂无"占位，禁止 Mock。
 */
@Entity
@Table(name = "stock_fund_flow", indexes = {
    @Index(name = "idx_fundflow_code_date", columnList = "code, tradeDate DESC"),
    @Index(name = "idx_fundflow_code_date_unique", columnList = "code, tradeDate", unique = true)
})
public class StockFundFlow {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 主力净流入(元) = 超大单 + 大单 */
    @Column(precision = 20, scale = 2)
    private BigDecimal mainNetInflow;

    /** 主力净流入占成交额比例(%) */
    @Column(precision = 10, scale = 2)
    private BigDecimal mainNetRatio;

    /** 超大单净流入(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal superLargeNetInflow;

    /** 大单净流入(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal largeNetInflow;

    /** 中单净流入(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal mediumNetInflow;

    /** 小单净流入(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal smallNetInflow;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockFundFlow() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public BigDecimal getMainNetInflow() { return mainNetInflow; }
    public void setMainNetInflow(BigDecimal mainNetInflow) { this.mainNetInflow = mainNetInflow; }
    public BigDecimal getMainNetRatio() { return mainNetRatio; }
    public void setMainNetRatio(BigDecimal mainNetRatio) { this.mainNetRatio = mainNetRatio; }
    public BigDecimal getSuperLargeNetInflow() { return superLargeNetInflow; }
    public void setSuperLargeNetInflow(BigDecimal superLargeNetInflow) { this.superLargeNetInflow = superLargeNetInflow; }
    public BigDecimal getLargeNetInflow() { return largeNetInflow; }
    public void setLargeNetInflow(BigDecimal largeNetInflow) { this.largeNetInflow = largeNetInflow; }
    public BigDecimal getMediumNetInflow() { return mediumNetInflow; }
    public void setMediumNetInflow(BigDecimal mediumNetInflow) { this.mediumNetInflow = mediumNetInflow; }
    public BigDecimal getSmallNetInflow() { return smallNetInflow; }
    public void setSmallNetInflow(BigDecimal smallNetInflow) { this.smallNetInflow = smallNetInflow; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
