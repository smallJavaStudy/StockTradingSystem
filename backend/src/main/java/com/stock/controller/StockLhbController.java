package com.stock.controller;

import com.stock.entity.StockLhbDetail;
import com.stock.repository.StockLhbDetailRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 个股龙虎榜上榜记录：只读复用 Task19 交付的 StockLhbDetailRepository。
 */
@RestController
@RequestMapping("/api/stock")
public class StockLhbController {

    private final StockLhbDetailRepository lhbRepo;

    public StockLhbController(StockLhbDetailRepository lhbRepo) {
        this.lhbRepo = lhbRepo;
    }

    /** 某股票全部上榜记录（按上榜日倒序，同日多原因多行） */
    @GetMapping("/{code}/lhb")
    public ResponseEntity<List<StockLhbDetail>> stockLhb(@PathVariable String code) {
        return ResponseEntity.ok(lhbRepo.findByCodeOrderByTradeDateDesc(code));
    }
}
