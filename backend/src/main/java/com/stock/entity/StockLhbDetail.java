package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 龙虎榜每日明细（东财 stock_lhb_detail_em）。
 * <p>
 * 同一股票同日可因多个上榜原因多次上榜，故 (code, tradeDate) 为非唯一索引。
 * 金额单位：buyAmount/sellAmount/netAmount/totalAmount 均为元。
 */
@Entity
@Table(name = "stock_lhb_detail", indexes = {
    @Index(name = "idx_lhb_code_date", columnList = "code, tradeDate DESC"),
    @Index(name = "idx_lhb_date", columnList = "tradeDate")
})
public class StockLhbDetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(length = 32)
    private String name;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 上榜原因 */
    @Column(length = 200)
    private String rankReason;

    /** 龙虎榜买入额(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal buyAmount;

    /** 龙虎榜卖出额(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal sellAmount;

    /** 龙虎榜净买额(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal netAmount;

    /** 龙虎榜成交额(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal totalAmount;

    /** 当日涨跌幅(%) */
    @Column(precision = 10, scale = 2)
    private BigDecimal changePct;

    /** 收盘价(元) */
    @Column(precision = 12, scale = 2)
    private BigDecimal closePrice;

    /** 东财解读（如"2家机构买入"） */
    @Column(length = 200)
    private String interpretation;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockLhbDetail() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public String getRankReason() { return rankReason; }
    public void setRankReason(String rankReason) { this.rankReason = rankReason; }
    public BigDecimal getBuyAmount() { return buyAmount; }
    public void setBuyAmount(BigDecimal buyAmount) { this.buyAmount = buyAmount; }
    public BigDecimal getSellAmount() { return sellAmount; }
    public void setSellAmount(BigDecimal sellAmount) { this.sellAmount = sellAmount; }
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public BigDecimal getChangePct() { return changePct; }
    public void setChangePct(BigDecimal changePct) { this.changePct = changePct; }
    public BigDecimal getClosePrice() { return closePrice; }
    public void setClosePrice(BigDecimal closePrice) { this.closePrice = closePrice; }
    public String getInterpretation() { return interpretation; }
    public void setInterpretation(String interpretation) { this.interpretation = interpretation; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
