package com.stock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_quote", indexes = {
    @Index(name = "idx_quote_code_time", columnList = "code, updateTime DESC")
})
public class StockQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;      // 现价

    @Column(precision = 12, scale = 2)
    private BigDecimal open;       // 开盘

    @Column(precision = 12, scale = 2)
    private BigDecimal high;       // 最高

    @Column(precision = 12, scale = 2)
    private BigDecimal low;        // 最低

    @Column(precision = 12, scale = 2)
    private BigDecimal preClose;   // 昨收

    private Long volume;           // 成交量(手)

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;     // 成交额

    @Column(precision = 8, scale = 2)
    private BigDecimal changePct;  // 涨跌幅%

    @Column(nullable = false)
    private LocalDateTime updateTime = LocalDateTime.now();

    public StockQuote() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public BigDecimal getOpen() { return open; }
    public void setOpen(BigDecimal open) { this.open = open; }
    public BigDecimal getHigh() { return high; }
    public void setHigh(BigDecimal high) { this.high = high; }
    public BigDecimal getLow() { return low; }
    public void setLow(BigDecimal low) { this.low = low; }
    public BigDecimal getPreClose() { return preClose; }
    public void setPreClose(BigDecimal preClose) { this.preClose = preClose; }
    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getChangePct() { return changePct; }
    public void setChangePct(BigDecimal changePct) { this.changePct = changePct; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
