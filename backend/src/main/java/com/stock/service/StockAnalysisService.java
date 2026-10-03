package com.stock.service;

import com.stock.entity.*;
import com.stock.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class StockAnalysisService {

    @Autowired private StockBasicRepository stockBasicRepo;
    @Autowired private StockCompanyRepository stockCompanyRepo;
    @Autowired private StockIndustryChainRepository industryChainRepo;
    @Autowired private StockProductBreakdownRepository productBreakdownRepo;
    @Autowired private StockCompetitorRepository competitorRepo;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== Kimi JSON 解析入库 ====================

    @Transactional
    public Map<String, Object> processKimiData(Map<String, Object> kimiJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> saved = new ArrayList<>();

        // 1. 解析 basic → StockBasic
        Long stockId = resolveStock(kimiJson.get("basic"));
        saved.add("basic");

        // 2. 解析 company → StockCompany
        Object companyData = kimiJson.get("company");
        if (companyData instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> company = (Map<String, Object>) companyData;
            saveCompany(stockId, company);
            saved.add("company");
        }

        // 3. 解析 industry_chain → StockIndustryChain
        Object chainData = kimiJson.get("industry_chain");
        if (chainData instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> chain = (Map<String, Object>) chainData;
            saveIndustryChain(stockId, chain);
            saved.add("industry_chain");
        }

        // 4. 解析 products → StockProductBreakdown (全量替换)
        Object productsData = kimiJson.get("products");
        if (productsData instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> products = (List<Map<String, Object>>) productsData;
            saveProducts(stockId, products);
            saved.add("products");
        }

        // 5. 解析 competitors → StockCompetitor (全量替换)
        Object competitorsData = kimiJson.get("competitors");
        if (competitorsData instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> competitors = (List<Map<String, Object>>) competitorsData;
            saveCompetitors(stockId, competitors);
            saved.add("competitors");
        }

        result.put("stockId", stockId);
        result.put("saved", saved);
        return result;
    }

    // ==================== 查询接口 ====================

    public Map<String, Object> getIndustryChain(String code) {
        StockBasic stock = findStockByCode(code);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", code);
        Optional<StockIndustryChain> chain = industryChainRepo.findByStockId(stock.getId());
        result.put("data", chain.orElse(null));
        result.put("dataStatus", chain.isPresent() ? "COMPLETE" : "MISSING");
        return result;
    }

    public Map<String, Object> getProductBreakdown(String code) {
        StockBasic stock = findStockByCode(code);
        List<StockProductBreakdown> products = productBreakdownRepo.findByStockIdOrderByReportDateDesc(stock.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", code);
        result.put("data", products);
        result.put("dataStatus", products.isEmpty() ? "MISSING" : "COMPLETE");
        return result;
    }

    public Map<String, Object> getCompetitors(String code) {
        StockBasic stock = findStockByCode(code);
        List<StockCompetitor> competitors = competitorRepo.findByStockId(stock.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", code);
        result.put("data", competitors);
        result.put("dataStatus", competitors.isEmpty() ? "MISSING" : "COMPLETE");
        return result;
    }

    public Map<String, Object> getStockAnalysisFull(String code) {
        StockBasic stock = findStockByCode(code);
        Long stockId = stock.getId();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", code);

        // Basic
        result.put("basic", stock);

        // Company
        Optional<StockCompany> company = stockCompanyRepo.findByStockId(stockId);
        result.put("company", company.orElse(null));

        // Industry Chain
        Optional<StockIndustryChain> chain = industryChainRepo.findByStockId(stockId);
        result.put("industryChain", chain.orElse(null));

        // Products
        List<StockProductBreakdown> products = productBreakdownRepo.findByStockIdOrderByReportDateDesc(stockId);
        result.put("products", products);

        // Competitors
        List<StockCompetitor> competitors = competitorRepo.findByStockId(stockId);
        result.put("competitors", competitors);

        // dataStatus: 模块级完整度标识
        Map<String, String> dataStatus = new LinkedHashMap<>();
        dataStatus.put("basic", "COMPLETE");
        dataStatus.put("company", company.isPresent() ? "COMPLETE" : "MISSING");
        dataStatus.put("industryChain", chain.isPresent() ? "COMPLETE" : "MISSING");
        dataStatus.put("products", products.isEmpty() ? "MISSING" : "COMPLETE");
        dataStatus.put("competitors", competitors.isEmpty() ? "MISSING" : "COMPLETE");
        result.put("dataStatus", dataStatus);

        return result;
    }

    // ==================== 内部方法 ====================

    private Long resolveStock(Object basicData) {
        if (!(basicData instanceof Map)) {
            throw new IllegalArgumentException("kimi JSON must contain 'basic' field with stock code");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> basic = (Map<String, Object>) basicData;
        String code = Objects.toString(basic.get("code"), null);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("stock code is required in basic field");
        }

        StockBasic stock = stockBasicRepo.findByCode(code).orElseGet(() -> {
            StockBasic s = new StockBasic();
            s.setCode(code);
            s.setName(Objects.toString(basic.get("name"), ""));
            s.setMarket(Objects.toString(basic.get("market"), null));
            s.setIndustry(Objects.toString(basic.get("industry"), null));
            return s;
        });

        // Update basic fields if provided
        if (basic.containsKey("name") && basic.get("name") != null) {
            stock.setName(Objects.toString(basic.get("name")));
        }
        if (basic.containsKey("market") && basic.get("market") != null) {
            stock.setMarket(Objects.toString(basic.get("market")));
        }
        if (basic.containsKey("industry") && basic.get("industry") != null) {
            stock.setIndustry(Objects.toString(basic.get("industry")));
        }
        if (basic.containsKey("listDate") && basic.get("listDate") != null) {
            stock.setListDate(parseDate(basic.get("listDate")));
        }

        stock = stockBasicRepo.save(stock);
        return stock.getId();
    }

    private void saveCompany(Long stockId, Map<String, Object> data) {
        StockCompany company = stockCompanyRepo.findByStockId(stockId).orElse(new StockCompany());
        company.setStockId(stockId);
        company.setCompanyName(str(data, "companyName"));
        company.setEnglishName(str(data, "englishName"));
        company.setMarket(str(data, "market"));
        company.setListDate(parseDate(data.get("listDate")));
        company.setTotalShares(longVal(data, "totalShares"));
        company.setCirculatingShares(longVal(data, "circulatingShares"));
        company.setIndustry(str(data, "industry"));
        company.setIndustryCode(str(data, "industryCode"));
        company.setCompanyProfile(str(data, "companyProfile"));
        company.setMainBusiness(str(data, "mainBusiness"));
        company.setWebsite(str(data, "website"));
        company.setAddress(str(data, "address"));
        company.setPhone(str(data, "phone"));
        company.setLegalRepresentative(str(data, "legalRepresentative"));
        company.setBoardSecretary(str(data, "boardSecretary"));
        stockCompanyRepo.save(company);
    }

    private void saveIndustryChain(Long stockId, Map<String, Object> data) {
        StockIndustryChain chain = industryChainRepo.findByStockId(stockId).orElse(new StockIndustryChain());
        chain.setStockId(stockId);
        chain.setCoreProduct(str(data, "coreProduct"));
        chain.setIndustryPosition(str(data, "industryPosition"));
        chain.setUpstream(str(data, "upstream"));
        chain.setDownstream(str(data, "downstream"));
        chain.setKeyCustomers(str(data, "keyCustomers"));
        chain.setKeySuppliers(str(data, "keySuppliers"));
        chain.setLifecycleStage(str(data, "lifecycleStage"));
        chain.setLifecycleNote(str(data, "lifecycleNote"));
        chain.setPolicyImpact(str(data, "policyImpact"));
        chain.setPolicyDetail(str(data, "policyDetail"));
        chain.setIndustryTrend(str(data, "industryTrend"));
        chain.setIndustrySize(str(data, "industrySize"));
        chain.setIndustryGrowth(str(data, "industryGrowth"));
        chain.setTechRoute(str(data, "techRoute"));
        chain.setTechTrend(str(data, "techTrend"));
        industryChainRepo.save(chain);
    }

    private void saveProducts(Long stockId, List<Map<String, Object>> products) {
        // 全量替换：删旧插新
        productBreakdownRepo.deleteByStockId(stockId);
        for (Map<String, Object> item : products) {
            StockProductBreakdown p = new StockProductBreakdown();
            p.setStockId(stockId);
            p.setReportDate(parseDate(item.get("reportDate")));
            p.setProductName(str(item, "productName"));
            p.setProductCategory(str(item, "productCategory"));
            p.setRevenue(bigDecimalVal(item, "revenue"));
            p.setRevenueRatio(bigDecimalVal(item, "revenueRatio"));
            p.setGrossMargin(bigDecimalVal(item, "grossMargin"));
            p.setRevenueYoy(bigDecimalVal(item, "revenueYoy"));
            p.setMarketPosition(str(item, "marketPosition"));
            p.setCompetitiveness(str(item, "competitiveness"));
            productBreakdownRepo.save(p);
        }
    }

    private void saveCompetitors(Long stockId, List<Map<String, Object>> competitors) {
        // 全量替换：删旧插新
        competitorRepo.deleteByStockId(stockId);
        for (Map<String, Object> item : competitors) {
            StockCompetitor c = new StockCompetitor();
            c.setStockId(stockId);
            c.setCompetitorName(str(item, "competitorName"));
            c.setCompetitorCode(str(item, "competitorCode"));
            c.setCompetitorExchange(str(item, "competitorExchange"));
            c.setCompetitorMarketCap(longVal(item, "competitorMarketCap"));
            c.setCompetitorRevenue(bigDecimalVal(item, "competitorRevenue"));
            c.setCompetitorNetProfit(bigDecimalVal(item, "competitorNetProfit"));
            c.setCompetitorGrossMargin(bigDecimalVal(item, "competitorGrossMargin"));
            c.setCompetitorRoe(bigDecimalVal(item, "competitorRoe"));
            c.setCompetitorMainProduct(str(item, "competitorMainProduct"));
            c.setMarketShareNote(str(item, "marketShareNote"));
            c.setMarketShareRank(byteVal(item, "marketShareRank"));
            c.setScarcity(str(item, "scarcity"));
            c.setScarcityNote(str(item, "scarcityNote"));
            c.setMoat(str(item, "moat"));
            c.setMoatNote(str(item, "moatNote"));
            c.setAdvantage(str(item, "advantage"));
            c.setDisadvantage(str(item, "disadvantage"));
            c.setCompareDate(parseDate(item.get("compareDate")));
            if (item.get("competitorIsListed") != null) {
                c.setCompetitorIsListed(boolVal(item, "competitorIsListed"));
            }
            competitorRepo.save(c);
        }
    }

    private StockBasic findStockByCode(String code) {
        return stockBasicRepo.findByCode(code)
                .orElseThrow(() -> new NoSuchElementException("Stock not found: " + code));
    }

    // ==================== 辅助方法 ====================

    private String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private BigDecimal bigDecimalVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof BigDecimal) return (BigDecimal) val;
        try {
            return new BigDecimal(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Byte byteVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).byteValue();
        try {
            return Byte.parseByte(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean boolVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Boolean) return (Boolean) val;
        String s = val.toString().toLowerCase();
        return "true".equals(s) || "1".equals(s);
    }

    private LocalDate parseDate(Object val) {
        if (val == null) return null;
        if (val instanceof LocalDate) return (LocalDate) val;
        try {
            return LocalDate.parse(val.toString(), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
