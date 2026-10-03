package com.stock.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 投资笔记：挂在个股详情页下，按 5 类分类记录。
 * content 长度 1-10000 字由 Controller 校验（TEXT 列容量足够）。
 */
@Entity
@Table(name = "invest_note", indexes = {
    @Index(name = "idx_note_code_time", columnList = "code, updatedAt DESC")
})
public class InvestNote {

    /** 笔记分类 */
    public enum Category {
        /** 投资逻辑 */
        INVEST_LOGIC,
        /** 风险点 */
        RISK_POINT,
        /** 买入条件 */
        BUY_CONDITION,
        /** 卖出条件 */
        SELL_CONDITION,
        /** 自由笔记 */
        FREE_NOTE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public InvestNote() {}

    public InvestNote(String code, Category category, String content) {
        this.code = code;
        this.category = category;
        this.content = content;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
