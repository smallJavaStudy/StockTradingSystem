package com.stock.controller;

import com.stock.entity.*;
import com.stock.service.StockDataAcquisitionService;
import com.stock.service.StockDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/stock")
public class StockDataController {

    @Autowired
    private StockDataService stockDataService;

    @Autowired
    private StockDataAcquisitionService acquisitionService;

    // ==================== 数据准备闸门（运维/调试） ====================

    /**
     * 手动触发数据准备闸门：盘点本地库并补齐行情/K线/财务/资金流，
     * 不启动工作流、不烧 token。用于补数运维与数据链路验证。
     */
    @PostMapping("/{code}/acquire")
    public ResponseEntity<StockDataAcquisitionService.AcquisitionResult> acquire(@PathVariable String code) {
        return ResponseEntity.ok(acquisitionService.ensureStockData(code));
    }

    // ==================== 股票基本信息 ====================

    @GetMapping("/list")
    public ResponseEntity<List<StockBasic>> listAll() {
        return ResponseEntity.ok(stockDataService.getAllBasics());
    }

    @GetMapping("/{code}/basic")
    public ResponseEntity<StockBasic> getBasic(@PathVariable String code) {
        return stockDataService.getBasic(code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/basic")
    public ResponseEntity<StockBasic> saveBasic(@RequestBody StockBasic basic) {
        return ResponseEntity.ok(stockDataService.saveBasic(basic));
    }

    // ==================== 行情 ====================

    @GetMapping("/{code}/quote")
    public ResponseEntity<StockQuote> getQuote(@PathVariable String code) {
        return stockDataService.getLatestQuote(code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{code}/quote")
    public ResponseEntity<StockQuote> saveQuote(@PathVariable String code, @RequestBody StockQuote quote) {
        quote.setCode(code);
        return ResponseEntity.ok(stockDataService.saveQuote(quote));
    }

    // ==================== K线 ====================

    @GetMapping("/{code}/kline")
    public ResponseEntity<List<StockKlineDaily>> getKlines(@PathVariable String code) {
        return ResponseEntity.ok(stockDataService.getKlines(code));
    }

    @PostMapping("/{code}/kline")
    public ResponseEntity<List<StockKlineDaily>> saveKlines(@PathVariable String code, @RequestBody List<StockKlineDaily> klines) {
        return ResponseEntity.ok(stockDataService.saveKlines(code, klines));
    }

    // ==================== 基本面 ====================

    @GetMapping("/{code}/finance")
    public ResponseEntity<List<StockFinance>> getFinances(@PathVariable String code) {
        return ResponseEntity.ok(stockDataService.getFinances(code));
    }

    @PostMapping("/{code}/finance")
    public ResponseEntity<List<StockFinance>> saveFinances(@PathVariable String code, @RequestBody List<StockFinance> finances) {
        return ResponseEntity.ok(stockDataService.saveFinances(code, finances));
    }

    // ==================== 资金流 ====================

    @GetMapping("/{code}/fundflow")
    public ResponseEntity<List<StockFundFlow>> getFundFlows(@PathVariable String code) {
        return ResponseEntity.ok(stockDataService.getFundFlows(code));
    }

    @PostMapping("/{code}/fundflow")
    public ResponseEntity<List<StockFundFlow>> saveFundFlows(@PathVariable String code, @RequestBody List<StockFundFlow> flows) {
        return ResponseEntity.ok(stockDataService.saveFundFlows(code, flows));
    }

    // ==================== 综合数据 ====================

    @GetMapping("/{code}/full")
    public ResponseEntity<Map<String, Object>> getFullData(@PathVariable String code) {
        return ResponseEntity.ok(stockDataService.getStockFullData(code));
    }
}
