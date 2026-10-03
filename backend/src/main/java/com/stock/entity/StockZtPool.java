package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 涨停股池（东财 stock_zt_pool_em / stock_zt_pool_previous_em）。
 * <p>
 * poolType=TODAY 为当日涨停池（tradeDate=涨停当日）；
 * poolType=PREVIOUS 为昨日涨停池（tradeDate=抓取日，记录昨日涨停股的今日表现）。
 * 金额单位：amount=成交额(元)。
 */
@Entity
@Table(name = "stock_zt_pool", indexes = {
    @Index(name = "idx_ztpool_code_date", columnList = "code, tradeDate DESC"),
    @Index(name = "idx_ztpool_date_type", columnList = "tradeDate, poolType"),
    @Index(name = "idx_ztpool_code_date_type_unique", columnList = "code, tradeDate, poolType", unique = true)
})
public class StockZtPool {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(length = 32)
    private String name;

    @Column(nullable = false)
    private LocalDate tradeDate;

    /** 池类型：TODAY=今日涨停池 / PREVIOUS=昨日涨停池 */
    @Column(nullable = false, length = 10)
    private String poolType;

    /** 最新价/收盘价(元) */
    @Column(precision = 12, scale = 2)
    private BigDecimal closePrice;

    /** 涨跌幅(%) */
    @Column(precision = 10, scale = 2)
    private BigDecimal changePct;

    /** 连板数（昨日池为昨日连板数） */
    private Integer limitUpDays;

    /** 首次封板时间 HH:mm:ss（昨日池为昨日封板时间） */
    @Column(length = 8)
    private String firstTime;

    /** 最后封板时间 HH:mm:ss */
    @Column(length = 8)
    private String lastTime;

    /** 炸板次数 */
    private Integer openTimes;

    /** 成交额(元) */
    @Column(precision = 20, scale = 2)
    private BigDecimal amount;

    /** 换手率(%) */
    @Column(precision = 10, scale = 2)
    private BigDecimal turnoverRate;

    @Column(length = 32)
    private String industry;

    /** 涨停原因/题材（东财涨停池接口未提供，预留可空；ztStat 为涨停统计如"3/2"） */
    @Column(length = 200)
    private String reason;

    /** 涨停统计（N天/M板） */
    @Column(length = 16)
    private String ztStat;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockZtPool() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public String getPoolType() { return poolType; }
    public void setPoolType(String poolType) { this.poolType = poolType; }
    public BigDecimal getClosePrice() { return closePrice; }
    public void setClosePrice(BigDecimal closePrice) { this.closePrice = closePrice; }
    public BigDecimal getChangePct() { return changePct; }
    public void setChangePct(BigDecimal changePct) { this.changePct = changePct; }
    public Integer getLimitUpDays() { return limitUpDays; }
    public void setLimitUpDays(Integer limitUpDays) { this.limitUpDays = limitUpDays; }
    public String getFirstTime() { return firstTime; }
    public void setFirstTime(String firstTime) { this.firstTime = firstTime; }
    public String getLastTime() { return lastTime; }
    public void setLastTime(String lastTime) { this.lastTime = lastTime; }
    public Integer getOpenTimes() { return openTimes; }
    public void setOpenTimes(Integer openTimes) { this.openTimes = openTimes; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getTurnoverRate() { return turnoverRate; }
    public void setTurnoverRate(BigDecimal turnoverRate) { this.turnoverRate = turnoverRate; }
    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getZtStat() { return ztStat; }
    public void setZtStat(String ztStat) { this.ztStat = ztStat; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
