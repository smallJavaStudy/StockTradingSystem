package com.stock.controller;

import com.stock.entity.AnalysisReport;
import com.stock.service.AnalysisEngineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisEngineService analysisEngine;

    /**
     * 触发分析：POST /api/v1/analysis/300373/run?mode=DEEP
     * 目前仅支持 DEEP 范式
     */
    @PostMapping("/{code}/run")
    public ResponseEntity<Map<String, Object>> runAnalysis(
            @PathVariable String code,
            @RequestParam(defaultValue = "DEEP") String mode) {
        AnalysisReport report = analysisEngine.runAnalysis(code, mode.toUpperCase());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", report.getId());
        result.put("stockCode", report.getStockCode());
        result.put("stockName", report.getStockName());
        result.put("paradigm", report.getParadigm());
        result.put("overallScore", report.getOverallScore());
        result.put("conclusion", report.getConclusion());
        result.put("conclusionReason", report.getConclusionReason());
        result.put("risks", report.getRiskCount() != null ? report.getRiskCount() + "项风险" : "N/A");
        result.put("tokens", Map.of(
                "input", report.getInputTokens() != null ? report.getInputTokens() : 0,
                "output", report.getOutputTokens() != null ? report.getOutputTokens() : 0));
        result.put("elapsedMs", report.getElapsedMs());
        result.put("createdAt", report.getCreatedAt() != null ? report.getCreatedAt().toString() : "");
        return ResponseEntity.ok(result);
    }

    /**
     * 获取最新分析报告：GET /api/v1/analysis/300373/latest?mode=DEEP
     */
    @GetMapping("/{code}/latest")
    public ResponseEntity<?> getLatest(
            @PathVariable String code,
            @RequestParam(defaultValue = "DEEP") String mode) {
        AnalysisReport report = analysisEngine.getLatestReport(code, mode.toUpperCase());
        if (report == null) {
            return ResponseEntity.ok(Map.of(
                    "stockCode", code,
                    "exists", false,
                    "message", "No report found. Run POST /api/v1/analysis/" + code + "/run?mode=" + mode + " first."));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", report.getId());
        result.put("stockCode", report.getStockCode());
        result.put("stockName", report.getStockName());
        result.put("paradigm", report.getParadigm());
        result.put("overallScore", report.getOverallScore());
        result.put("conclusion", report.getConclusion());
        result.put("conclusionReason", report.getConclusionReason());
        result.put("riskCount", report.getRiskCount());
        result.put("contentJson", report.getContentJson());
        result.put("inputTokens", report.getInputTokens());
        result.put("outputTokens", report.getOutputTokens());
        result.put("elapsedMs", report.getElapsedMs());
        result.put("createdAt", report.getCreatedAt() != null ? report.getCreatedAt().toString() : "");
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有历史报告：GET /api/v1/analysis/300373/reports
     */
    @GetMapping("/{code}/reports")
    public ResponseEntity<Map<String, Object>> getReports(@PathVariable String code) {
        List<AnalysisReport> reports = analysisEngine.getReports(code);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stockCode", code);
        result.put("count", reports.size());
        result.put("reports", reports.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.getId());
            item.put("paradigm", r.getParadigm());
            item.put("overallScore", r.getOverallScore());
            item.put("conclusion", r.getConclusion());
            item.put("riskCount", r.getRiskCount());
            item.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : "");
            return item;
        }).toList());
        return ResponseEntity.ok(result);
    }
}
