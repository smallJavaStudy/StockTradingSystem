package com.stock.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "watchlist")
public class Watchlist {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 6)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String stockName;

    @Column(nullable = false, length = 50)
    private String groupName = "默认分组";

    @Column(length = 200)
    private String tags; // 逗号分隔，如 "半导体,国产替代"

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Watchlist() {}

    public Watchlist(String stockCode, String stockName, String groupName, String tags, String note) {
        this.stockCode = stockCode;
        this.stockName = stockName;
        if (groupName != null && !groupName.isBlank()) this.groupName = groupName;
        this.tags = tags;
        this.note = note;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
