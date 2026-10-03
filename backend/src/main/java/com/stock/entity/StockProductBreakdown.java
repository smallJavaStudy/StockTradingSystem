package com.stock.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_product_breakdown", indexes = {
    @Index(name = "idx_product_stock_date", columnList = "stock_id, report_date")
})
@EntityListeners(AuditingEntityListener.class)
public class StockProductBreakdown {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "report_date")
    private LocalDate reportDate;

    @Column(name = "product_name", length = 100)
    private String productName;

    @Column(name = "product_category", length = 50)
    private String productCategory;

    @Column(precision = 20, scale = 2)
    private BigDecimal revenue;

    @Column(name = "revenue_ratio", precision = 8, scale = 2, nullable = false)
    private BigDecimal revenueRatio;

    @Column(name = "gross_margin", precision = 8, scale = 2)
    private BigDecimal grossMargin;

    @Column(name = "revenue_yoy", precision = 8, scale = 2)
    private BigDecimal revenueYoy;

    @Column(name = "market_position", length = 100)
    private String marketPosition;

    @Column(columnDefinition = "TEXT")
    private String competitiveness;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public StockProductBreakdown() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getProductCategory() { return productCategory; }
    public void setProductCategory(String productCategory) { this.productCategory = productCategory; }
    public BigDecimal getRevenue() { return revenue; }
    public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
    public BigDecimal getRevenueRatio() { return revenueRatio; }
    public void setRevenueRatio(BigDecimal revenueRatio) { this.revenueRatio = revenueRatio; }
    public BigDecimal getGrossMargin() { return grossMargin; }
    public void setGrossMargin(BigDecimal grossMargin) { this.grossMargin = grossMargin; }
    public BigDecimal getRevenueYoy() { return revenueYoy; }
    public void setRevenueYoy(BigDecimal revenueYoy) { this.revenueYoy = revenueYoy; }
    public String getMarketPosition() { return marketPosition; }
    public void setMarketPosition(String marketPosition) { this.marketPosition = marketPosition; }
    public String getCompetitiveness() { return competitiveness; }
    public void setCompetitiveness(String competitiveness) { this.competitiveness = competitiveness; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
