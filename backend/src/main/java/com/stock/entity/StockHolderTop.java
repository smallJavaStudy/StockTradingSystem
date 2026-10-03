package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 十大股东 / 十大流通股东（东财 stock_gdfx_top_10_em / stock_gdfx_free_top_10_em）。
 * <p>
 * holderType 区分 TOP10（十大股东）与 TOP10_FLOAT（十大流通股东）；
 * (code, reportDate, holderType, holderRank) 唯一。
 * 注意列名用 holder_rank，避开 MySQL 8 保留字 RANK。
 */
@Entity
@Table(name = "stock_holder_top", indexes = {
    @Index(name = "idx_holdertop_code_date", columnList = "code, reportDate DESC"),
    @Index(name = "idx_holdertop_unique", columnList = "code, reportDate, holderType, holderRank", unique = true)
})
public class StockHolderTop {

    /** 十大股东 */
    public static final String TYPE_TOP10 = "TOP10";
    /** 十大流通股东 */
    public static final String TYPE_TOP10_FLOAT = "TOP10_FLOAT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    /** 报告期（季度末） */
    @Column(nullable = false)
    private LocalDate reportDate;

    /** TOP10 / TOP10_FLOAT */
    @Column(nullable = false, length = 12)
    private String holderType;

    /** 名次 1-10 */
    @Column(name = "holder_rank", nullable = false)
    private Integer holderRank;

    @Column(nullable = false, length = 200)
    private String holderName;

    /** 股东性质（个人/投资公司/QFII等，接口缺省为 null） */
    @Column(length = 50)
    private String holderNature;

    /** 持股数(股) */
    private Long shares;

    /** 占总股本/流通股本比例(%) */
    @Column(precision = 10, scale = 4)
    private BigDecimal holdRatio;

    /** 较上期增减：数字(股数变化)或"新进"/"不变" */
    @Column(length = 32)
    private String changeDesc;

    /** 变动比率(%)，新进/不变时为 null */
    @Column(precision = 12, scale = 4)
    private BigDecimal changeRatio;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockHolderTop() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }
    public String getHolderType() { return holderType; }
    public void setHolderType(String holderType) { this.holderType = holderType; }
    public Integer getHolderRank() { return holderRank; }
    public void setHolderRank(Integer holderRank) { this.holderRank = holderRank; }
    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }
    public String getHolderNature() { return holderNature; }
    public void setHolderNature(String holderNature) { this.holderNature = holderNature; }
    public Long getShares() { return shares; }
    public void setShares(Long shares) { this.shares = shares; }
    public BigDecimal getHoldRatio() { return holdRatio; }
    public void setHoldRatio(BigDecimal holdRatio) { this.holdRatio = holdRatio; }
    public String getChangeDesc() { return changeDesc; }
    public void setChangeDesc(String changeDesc) { this.changeDesc = changeDesc; }
    public BigDecimal getChangeRatio() { return changeRatio; }
    public void setChangeRatio(BigDecimal changeRatio) { this.changeRatio = changeRatio; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
