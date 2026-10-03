package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 大宗交易每日明细（东财 stock_dzjy_mrmx）。
 * <p>
 * 同一股票同日可有多笔大宗交易，故 (code, tradeDate) 非唯一；
 * 以 (code, tradeDate, price, volume, buyerBranch) 作为业务去重键（脚本侧保证）。
 * 金额单位：price=成交价(元)、amount=成交额(元)、volume=成交量(股)。
 */
@Entity
@Table(name = "stock_block_trade", indexes = {
    @Index(name = "idx_blocktrade_code_date", columnList = "code, tradeDate DESC"),
    @Index(name = "idx_blocktrade_date", columnList = "tradeDate")
})
public class StockBlockTrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(length = 32)
    private String name;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 大宗成交价(元) */
    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    /** 二级市场收盘价(元) */
    @Column(precision = 12, scale = 2)
    private BigDecimal closePrice;

    /** 成交量(股) */
    private Long volume;

    /** 成交额(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal amount;

    /** 折溢率(%)，负数为折价 */
    @Column(precision = 10, scale = 2)
    private BigDecimal premiumRate;

    /** 买方营业部 */
    @Column(length = 200)
    private String buyerBranch;

    /** 卖方营业部 */
    @Column(length = 200)
    private String sellerBranch;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockBlockTrade() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getClosePrice() { return closePrice; }
    public void setClosePrice(BigDecimal closePrice) { this.closePrice = closePrice; }
    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getPremiumRate() { return premiumRate; }
    public void setPremiumRate(BigDecimal premiumRate) { this.premiumRate = premiumRate; }
    public String getBuyerBranch() { return buyerBranch; }
    public void setBuyerBranch(String buyerBranch) { this.buyerBranch = buyerBranch; }
    public String getSellerBranch() { return sellerBranch; }
    public void setSellerBranch(String sellerBranch) { this.sellerBranch = sellerBranch; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
