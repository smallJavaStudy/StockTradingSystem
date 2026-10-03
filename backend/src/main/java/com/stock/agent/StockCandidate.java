package com.stock.agent;

public record StockCandidate(String code, String name, String market, String industry) {
    public String getMarketDisplay() {
        return "SH".equals(market) ? "上海" : "深圳";
    }
}
