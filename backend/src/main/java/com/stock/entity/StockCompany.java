package com.stock.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_company")
@EntityListeners(AuditingEntityListener.class)
public class StockCompany {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stock_id", nullable = false, unique = true)
    private Long stockId;

    @Column(name = "company_name", length = 100)
    private String companyName;

    @Column(name = "english_name", length = 200)
    private String englishName;

    @Column(length = 10)
    private String market;

    @Column(name = "list_date")
    private LocalDate listDate;

    @Column(name = "total_shares")
    private Long totalShares;

    @Column(name = "circulating_shares")
    private Long circulatingShares;

    @Column(length = 50)
    private String industry;

    @Column(name = "industry_code", length = 10)
    private String industryCode;

    @Column(name = "company_profile", columnDefinition = "TEXT")
    private String companyProfile;

    @Column(name = "main_business", columnDefinition = "TEXT")
    private String mainBusiness;

    @Column(length = 200)
    private String website;

    @Column(length = 200)
    private String address;

    @Column(length = 20)
    private String phone;

    @Column(name = "legal_representative", length = 50)
    private String legalRepresentative;

    @Column(name = "board_secretary", length = 50)
    private String boardSecretary;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public StockCompany() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getEnglishName() { return englishName; }
    public void setEnglishName(String englishName) { this.englishName = englishName; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public LocalDate getListDate() { return listDate; }
    public void setListDate(LocalDate listDate) { this.listDate = listDate; }
    public Long getTotalShares() { return totalShares; }
    public void setTotalShares(Long totalShares) { this.totalShares = totalShares; }
    public Long getCirculatingShares() { return circulatingShares; }
    public void setCirculatingShares(Long circulatingShares) { this.circulatingShares = circulatingShares; }
    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }
    public String getIndustryCode() { return industryCode; }
    public void setIndustryCode(String industryCode) { this.industryCode = industryCode; }
    public String getCompanyProfile() { return companyProfile; }
    public void setCompanyProfile(String companyProfile) { this.companyProfile = companyProfile; }
    public String getMainBusiness() { return mainBusiness; }
    public void setMainBusiness(String mainBusiness) { this.mainBusiness = mainBusiness; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLegalRepresentative() { return legalRepresentative; }
    public void setLegalRepresentative(String legalRepresentative) { this.legalRepresentative = legalRepresentative; }
    public String getBoardSecretary() { return boardSecretary; }
    public void setBoardSecretary(String boardSecretary) { this.boardSecretary = boardSecretary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
