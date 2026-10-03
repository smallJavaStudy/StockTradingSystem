package com.stock.controller;

import com.stock.service.StockComparisonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 财务对标/竞品对比 API：指标并排表 + LLM 竞争格局点评。
 */
@RestController
@RequestMapping("/api/stock")
public class StockComparisonController {

    private final StockComparisonService comparisonService;

    public StockComparisonController(StockComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    /** 本股 + 竞品（StockCompetitor）最新期 EPS/ROE/营收/净利/市值/PE 并排，缺数据字段为 null */
    @GetMapping("/{code}/comparison")
    public ResponseEntity<Map<String, Object>> comparison(@PathVariable String code) {
        return ResponseEntity.ok(comparisonService.buildComparison(code));
    }

    /** 竞争格局点评（300-500字，按 code+日期 缓存于 EnrichmentData） */
    @GetMapping("/{code}/comparison/comment")
    public ResponseEntity<Map<String, Object>> comparisonComment(@PathVariable String code) {
        return ResponseEntity.ok(comparisonService.getComment(code));
    }
}
