package com.stock.controller;

import com.stock.entity.StockHolderCount;
import com.stock.entity.StockHolderTop;
import com.stock.repository.StockHolderCountRepository;
import com.stock.repository.StockHolderTopRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股东追踪 API：十大股东/十大流通股东 + 股东户数趋势。
 * 数据由 data-fetcher/fetch_holders.py（AKShare）直写 MySQL，本控制器只读。
 */
@RestController
@RequestMapping("/api/stock")
public class StockHolderController {

    /** 默认返回最近几期报告 */
    private static final int DEFAULT_PERIODS = 4;

    private final StockHolderTopRepository holderTopRepo;
    private final StockHolderCountRepository holderCountRepo;

    public StockHolderController(StockHolderTopRepository holderTopRepo,
                                 StockHolderCountRepository holderCountRepo) {
        this.holderTopRepo = holderTopRepo;
        this.holderCountRepo = holderCountRepo;
    }

    /**
     * 最近数期十大股东与十大流通股东（含较上期增减）。
     * 返回 {top10: [...], top10Float: [...]}，各自按报告期倒序、名次升序。
     */
    @GetMapping("/{code}/holders")
    public ResponseEntity<Map<String, Object>> holders(
            @PathVariable String code,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PERIODS) int periods) {
        int limit = Math.max(1, Math.min(periods, 12));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("top10", loadRecentPeriods(code, StockHolderTop.TYPE_TOP10, limit));
        result.put("top10Float", loadRecentPeriods(code, StockHolderTop.TYPE_TOP10_FLOAT, limit));
        return ResponseEntity.ok(result);
    }

    /** 股东户数趋势（时间升序） */
    @GetMapping("/{code}/holder-count")
    public ResponseEntity<List<StockHolderCount>> holderCount(@PathVariable String code) {
        return ResponseEntity.ok(holderCountRepo.findByCodeOrderByStatDateAsc(code));
    }

    private List<StockHolderTop> loadRecentPeriods(String code, String holderType, int periods) {
        List<LocalDate> dates = holderTopRepo.findDistinctReportDates(code, holderType);
        if (dates.isEmpty()) {
            return List.of();
        }
        List<LocalDate> recent = dates.subList(0, Math.min(periods, dates.size()));
        return holderTopRepo.findByCodeAndHolderTypeAndReportDateInOrderByReportDateDescHolderRankAsc(
                code, holderType, recent);
    }
}
