package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 融资融券每日汇总（上交所 stock_margin_sse / 深交所 stock_margin_szse，市场级无 code）。
 * <p>
 * market=SH/SZ 分市场存储。原始接口单位不一致（SSE 为元、SZSE 为亿元），
 * 抓取脚本统一归一化为 <b>元</b> 入库。
 */
@Entity
@Table(name = "stock_margin_daily", indexes = {
    @Index(name = "idx_margin_date_market_unique", columnList = "tradeDate, market", unique = true),
    @Index(name = "idx_margin_date", columnList = "tradeDate DESC")
})
public class StockMarginDaily {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 市场：SH=上交所 / SZ=深交所 */
    @Column(nullable = false, length = 4)
    private String market;

    /** 融资余额(元) */
    @Column(precision = 22, scale = 2)
    private BigDecimal financingBalance;

    /** 融资买入额(元) */
    @Column(precision = 22, scale = 2)
    private BigDecimal financingBuyAmount;

    /** 融券余额/融券余量金额(元) */
    @Column(precision = 22, scale = 2)
    private BigDecimal securitiesBalance;

    /** 融资融券余额合计(元) */
    @Column(precision = 22, scale = 2)
    private BigDecimal totalBalance;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockMarginDaily() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public BigDecimal getFinancingBalance() { return financingBalance; }
    public void setFinancingBalance(BigDecimal financingBalance) { this.financingBalance = financingBalance; }
    public BigDecimal getFinancingBuyAmount() { return financingBuyAmount; }
    public void setFinancingBuyAmount(BigDecimal financingBuyAmount) { this.financingBuyAmount = financingBuyAmount; }
    public BigDecimal getSecuritiesBalance() { return securitiesBalance; }
    public void setSecuritiesBalance(BigDecimal securitiesBalance) { this.securitiesBalance = securitiesBalance; }
    public BigDecimal getTotalBalance() { return totalBalance; }
    public void setTotalBalance(BigDecimal totalBalance) { this.totalBalance = totalBalance; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
