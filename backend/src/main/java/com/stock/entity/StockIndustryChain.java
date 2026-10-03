package com.stock.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "stock_industry_chain")
@EntityListeners(AuditingEntityListener.class)
public class StockIndustryChain {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_id", nullable = false, unique = true)
    private Long stockId;

    @Column(name = "core_product", length = 200)
    private String coreProduct;

    @Column(name = "industry_position", length = 20)
    private String industryPosition;

    @Column(columnDefinition = "TEXT")
    private String upstream;

    @Column(columnDefinition = "TEXT")
    private String downstream;

    @Column(name = "key_customers", length = 500)
    private String keyCustomers;

    @Column(name = "key_suppliers", length = 500)
    private String keySuppliers;

    @Column(name = "lifecycle_stage", length = 20)
    private String lifecycleStage;

    @Column(name = "lifecycle_note", columnDefinition = "TEXT")
    private String lifecycleNote;

    @Column(name = "policy_impact", columnDefinition = "TEXT")
    private String policyImpact;

    @Column(name = "policy_detail", columnDefinition = "TEXT")
    private String policyDetail;

    @Column(name = "industry_trend", columnDefinition = "TEXT")
    private String industryTrend;

    @Column(name = "industry_size", length = 100)
    private String industrySize;

    @Column(name = "industry_growth", length = 50)
    private String industryGrowth;

    @Column(name = "tech_route", length = 200)
    private String techRoute;

    @Column(name = "tech_trend", columnDefinition = "TEXT")
    private String techTrend;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public StockIndustryChain() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public String getCoreProduct() { return coreProduct; }
    public void setCoreProduct(String coreProduct) { this.coreProduct = coreProduct; }
    public String getIndustryPosition() { return industryPosition; }
    public void setIndustryPosition(String industryPosition) { this.industryPosition = industryPosition; }
    public String getUpstream() { return upstream; }
    public void setUpstream(String upstream) { this.upstream = upstream; }
    public String getDownstream() { return downstream; }
    public void setDownstream(String downstream) { this.downstream = downstream; }
    public String getKeyCustomers() { return keyCustomers; }
    public void setKeyCustomers(String keyCustomers) { this.keyCustomers = keyCustomers; }
    public String getKeySuppliers() { return keySuppliers; }
    public void setKeySuppliers(String keySuppliers) { this.keySuppliers = keySuppliers; }
    public String getLifecycleStage() { return lifecycleStage; }
    public void setLifecycleStage(String lifecycleStage) { this.lifecycleStage = lifecycleStage; }
    public String getLifecycleNote() { return lifecycleNote; }
    public void setLifecycleNote(String lifecycleNote) { this.lifecycleNote = lifecycleNote; }
    public String getPolicyImpact() { return policyImpact; }
    public void setPolicyImpact(String policyImpact) { this.policyImpact = policyImpact; }
    public String getPolicyDetail() { return policyDetail; }
    public void setPolicyDetail(String policyDetail) { this.policyDetail = policyDetail; }
    public String getIndustryTrend() { return industryTrend; }
    public void setIndustryTrend(String industryTrend) { this.industryTrend = industryTrend; }
    public String getIndustrySize() { return industrySize; }
    public void setIndustrySize(String industrySize) { this.industrySize = industrySize; }
    public String getIndustryGrowth() { return industryGrowth; }
    public void setIndustryGrowth(String industryGrowth) { this.industryGrowth = industryGrowth; }
    public String getTechRoute() { return techRoute; }
    public void setTechRoute(String techRoute) { this.techRoute = techRoute; }
    public String getTechTrend() { return techTrend; }
    public void setTechTrend(String techTrend) { this.techTrend = techTrend; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
