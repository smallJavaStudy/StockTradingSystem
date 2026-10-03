package com.stock.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_competitor")
@EntityListeners(AuditingEntityListener.class)
public class StockCompetitor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "competitor_name", length = 100, nullable = false)
    private String competitorName;

    @Column(name = "competitor_code", length = 12)
    private String competitorCode;

    @Column(name = "competitor_exchange", length = 10)
    private String competitorExchange;

    @Column(name = "competitor_market_cap")
    private Long competitorMarketCap;

    @Column(name = "competitor_revenue", precision = 20, scale = 2)
    private BigDecimal competitorRevenue;

    @Column(name = "competitor_net_profit", precision = 20, scale = 2)
    private BigDecimal competitorNetProfit;

    @Column(name = "competitor_gross_margin", precision = 8, scale = 2)
    private BigDecimal competitorGrossMargin;

    @Column(name = "competitor_roe", precision = 8, scale = 2)
    private BigDecimal competitorRoe;

    @Column(name = "competitor_main_product", length = 200)
    private String competitorMainProduct;

    @Column(name = "market_share_note", columnDefinition = "TEXT")
    private String marketShareNote;

    @Column(name = "market_share_rank")
    private Byte marketShareRank;

    @Column(length = 10)
    private String scarcity;

    @Column(name = "scarcity_note", columnDefinition = "TEXT")
    private String scarcityNote;

    @Column(length = 10)
    private String moat;

    @Column(name = "moat_note", columnDefinition = "TEXT")
    private String moatNote;

    @Column(columnDefinition = "TEXT")
    private String advantage;

    @Column(columnDefinition = "TEXT")
    private String disadvantage;

    @Column(name = "compare_date")
    private LocalDate compareDate;

    @Column(name = "competitor_is_listed", columnDefinition = "BOOLEAN DEFAULT TRUE")
    private Boolean competitorIsListed = true;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public StockCompetitor() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public String getCompetitorName() { return competitorName; }
    public void setCompetitorName(String competitorName) { this.competitorName = competitorName; }
    public String getCompetitorCode() { return competitorCode; }
    public void setCompetitorCode(String competitorCode) { this.competitorCode = competitorCode; }
    public String getCompetitorExchange() { return competitorExchange; }
    public void setCompetitorExchange(String competitorExchange) { this.competitorExchange = competitorExchange; }
    public Long getCompetitorMarketCap() { return competitorMarketCap; }
    public void setCompetitorMarketCap(Long competitorMarketCap) { this.competitorMarketCap = competitorMarketCap; }
    public BigDecimal getCompetitorRevenue() { return competitorRevenue; }
    public void setCompetitorRevenue(BigDecimal competitorRevenue) { this.competitorRevenue = competitorRevenue; }
    public BigDecimal getCompetitorNetProfit() { return competitorNetProfit; }
    public void setCompetitorNetProfit(BigDecimal competitorNetProfit) { this.competitorNetProfit = competitorNetProfit; }
    public BigDecimal getCompetitorGrossMargin() { return competitorGrossMargin; }
    public void setCompetitorGrossMargin(BigDecimal competitorGrossMargin) { this.competitorGrossMargin = competitorGrossMargin; }
    public BigDecimal getCompetitorRoe() { return competitorRoe; }
    public void setCompetitorRoe(BigDecimal competitorRoe) { this.competitorRoe = competitorRoe; }
    public String getCompetitorMainProduct() { return competitorMainProduct; }
    public void setCompetitorMainProduct(String competitorMainProduct) { this.competitorMainProduct = competitorMainProduct; }
    public String getMarketShareNote() { return marketShareNote; }
    public void setMarketShareNote(String marketShareNote) { this.marketShareNote = marketShareNote; }
    public Byte getMarketShareRank() { return marketShareRank; }
    public void setMarketShareRank(Byte marketShareRank) { this.marketShareRank = marketShareRank; }
    public String getScarcity() { return scarcity; }
    public void setScarcity(String scarcity) { this.scarcity = scarcity; }
    public String getScarcityNote() { return scarcityNote; }
    public void setScarcityNote(String scarcityNote) { this.scarcityNote = scarcityNote; }
    public String getMoat() { return moat; }
    public void setMoat(String moat) { this.moat = moat; }
    public String getMoatNote() { return moatNote; }
    public void setMoatNote(String moatNote) { this.moatNote = moatNote; }
    public String getAdvantage() { return advantage; }
    public void setAdvantage(String advantage) { this.advantage = advantage; }
    public String getDisadvantage() { return disadvantage; }
    public void setDisadvantage(String disadvantage) { this.disadvantage = disadvantage; }
    public LocalDate getCompareDate() { return compareDate; }
    public void setCompareDate(LocalDate compareDate) { this.compareDate = compareDate; }
    public Boolean getCompetitorIsListed() { return competitorIsListed; }
    public void setCompetitorIsListed(Boolean competitorIsListed) { this.competitorIsListed = competitorIsListed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
