package com.stock.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_basic")
public class StockBasic {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 6)
    private String code;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(length = 2)
    private String market; // SH / SZ

    @Column(length = 50)
    private String industry;

    private LocalDate listDate;

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockBasic() {}

    public StockBasic(String code, String name, String market, String industry, LocalDate listDate) {
        this.code = code;
        this.name = name;
        this.market = market;
        this.industry = industry;
        this.listDate = listDate;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }
    public LocalDate getListDate() { return listDate; }
    public void setListDate(LocalDate listDate) { this.listDate = listDate; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
