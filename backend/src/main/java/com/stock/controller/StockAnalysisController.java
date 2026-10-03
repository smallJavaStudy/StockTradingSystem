package com.stock.controller;

import com.stock.service.StockAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/stocks")
public class StockAnalysisController {

    @Autowired
    private StockAnalysisService analysisService;

    /**
     * 接收 kimi AI 返回的完整 JSON 并解析入库
     * JSON 结构: { basic, company, industry_chain, products, competitors }
     */
    @PostMapping("/{code}/kimi-data")
    public ResponseEntity<Map<String, Object>> receiveKimiData(
            @PathVariable String code,
            @RequestBody Map<String, Object> kimiJson) {
        // 确保 basic.code 与路径 code 一致
        @SuppressWarnings("unchecked")
        Map<String, Object> basic = (Map<String, Object>) kimiJson.computeIfAbsent("basic", k -> new java.util.LinkedHashMap<>());
        basic.put("code", code);
        return ResponseEntity.ok(analysisService.processKimiData(kimiJson));
    }

    /** 查询产业链分析 */
    @GetMapping("/{code}/industry-chain")
    public ResponseEntity<Map<String, Object>> getIndustryChain(@PathVariable String code) {
        return ResponseEntity.ok(analysisService.getIndustryChain(code));
    }

    /** 查询主营产品构成 */
    @GetMapping("/{code}/product-breakdown")
    public ResponseEntity<Map<String, Object>> getProductBreakdown(@PathVariable String code) {
        return ResponseEntity.ok(analysisService.getProductBreakdown(code));
    }

    /** 查询竞品对比 */
    @GetMapping("/{code}/competitors")
    public ResponseEntity<Map<String, Object>> getCompetitors(@PathVariable String code) {
        return ResponseEntity.ok(analysisService.getCompetitors(code));
    }

    /** 聚合查询：全部产业分析数据 */
    @GetMapping("/{code}/full")
    public ResponseEntity<Map<String, Object>> getAnalysisFull(@PathVariable String code) {
        return ResponseEntity.ok(analysisService.getStockAnalysisFull(code));
    }
}
