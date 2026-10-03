package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_finance", indexes = {
    @Index(name = "idx_finance_code_date", columnList = "code, reportDate DESC"),
    @Index(name = "idx_finance_code_date_unique", columnList = "code, reportDate", unique = true)
})
public class StockFinance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(nullable = false)
    private LocalDate reportDate;   // 报告期

    @Column(precision = 10, scale = 2)
    private BigDecimal basicEps;    // 基本每股收益

    @Column(precision = 10, scale = 2)
    private BigDecimal weightedRoe; // 加权净资产收益率%

    @Column(precision = 20, scale = 2)
    private BigDecimal totalRevenue;// 营业总收入(元)

    @Column(precision = 20, scale = 2)
    private BigDecimal netProfit;   // 归母净利润(元)

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockFinance() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }
    public BigDecimal getBasicEps() { return basicEps; }
    public void setBasicEps(BigDecimal basicEps) { this.basicEps = basicEps; }
    public BigDecimal getWeightedRoe() { return weightedRoe; }
    public void setWeightedRoe(BigDecimal weightedRoe) { this.weightedRoe = weightedRoe; }
    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(BigDecimal totalRevenue) { this.totalRevenue = totalRevenue; }
    public BigDecimal getNetProfit() { return netProfit; }
    public void setNetProfit(BigDecimal netProfit) { this.netProfit = netProfit; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
