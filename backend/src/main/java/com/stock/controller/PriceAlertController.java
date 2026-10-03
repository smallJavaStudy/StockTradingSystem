package com.stock.controller;

import com.stock.entity.PriceAlert;
import com.stock.repository.PriceAlertRepository;
import com.stock.service.TechnicalSignalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 价格预警 CRUD + 技术信号：/api/stock/{code}/alerts、/api/stock/{code}/signals。
 * 预警触发由 AlertScanService 定时扫描完成；技术信号实时计算不入库。
 */
@RestController
@RequestMapping("/api/stock/{code}")
public class PriceAlertController {

    private final PriceAlertRepository alertRepo;
    private final TechnicalSignalService signalService;

    public PriceAlertController(PriceAlertRepository alertRepo, TechnicalSignalService signalService) {
        this.alertRepo = alertRepo;
        this.signalService = signalService;
    }

    // ==================== 价格预警 ====================

    @GetMapping("/alerts")
    public ResponseEntity<List<PriceAlert>> list(@PathVariable String code) {
        return ResponseEntity.ok(alertRepo.findByCodeOrderByCreatedAtDesc(code));
    }

    @PostMapping("/alerts")
    public ResponseEntity<PriceAlert> create(@PathVariable String code, @RequestBody PriceAlert alert) {
        if (alert.getType() == null) {
            throw new IllegalArgumentException("预警类型不能为空（PRICE_ABOVE/PRICE_BELOW）");
        }
        if (alert.getThreshold() == null || alert.getThreshold().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("阈值必须为正数");
        }
        PriceAlert entity = new PriceAlert(code, alert.getType(), alert.getThreshold());
        if (alert.getEnabled() != null) {
            entity.setEnabled(alert.getEnabled());
        }
        return ResponseEntity.ok(alertRepo.save(entity));
    }

    /** 更新阈值/类型/启用状态；重置 triggered 可让预警重新生效 */
    @PutMapping("/alerts/{id}")
    public ResponseEntity<PriceAlert> update(@PathVariable String code, @PathVariable Long id,
                                             @RequestBody PriceAlert patch) {
        PriceAlert existing = alertRepo.findById(id)
                .filter(a -> a.getCode().equals(code))
                .orElseThrow(() -> new NoSuchElementException("预警不存在: " + id));
        if (patch.getType() != null) {
            existing.setType(patch.getType());
        }
        if (patch.getThreshold() != null) {
            if (patch.getThreshold().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("阈值必须为正数");
            }
            existing.setThreshold(patch.getThreshold());
        }
        if (patch.getEnabled() != null) {
            existing.setEnabled(patch.getEnabled());
        }
        if (patch.getTriggered() != null && !patch.getTriggered()) {
            existing.setTriggered(false);
            existing.setTriggeredAt(null);
        }
        return ResponseEntity.ok(alertRepo.save(existing));
    }

    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<Void> delete(@PathVariable String code, @PathVariable Long id) {
        PriceAlert existing = alertRepo.findById(id)
                .filter(a -> a.getCode().equals(code))
                .orElseThrow(() -> new NoSuchElementException("预警不存在: " + id));
        alertRepo.delete(existing);
        return ResponseEntity.noContent().build();
    }

    // ==================== 技术信号 ====================

    /** MA5/MA20 金叉死叉 + 放量信号（基于 StockKlineDaily 实时计算） */
    @GetMapping("/signals")
    public ResponseEntity<Map<String, Object>> signals(@PathVariable String code) {
        return ResponseEntity.ok(signalService.getSignals(code));
    }
}
