package com.stock.controller;

import com.stock.entity.StockKlineDaily;
import com.stock.entity.StockQuote;
import com.stock.entity.Watchlist;
import com.stock.repository.StockKlineDailyRepository;
import com.stock.repository.StockQuoteRepository;
import com.stock.repository.WatchlistRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private static final Logger log = LoggerFactory.getLogger(WatchlistController.class);

    private final WatchlistRepository watchlistRepository;
    private final StockQuoteRepository stockQuoteRepository;
    private final StockKlineDailyRepository klineRepository;

    public WatchlistController(WatchlistRepository watchlistRepository,
                               StockQuoteRepository stockQuoteRepository,
                               StockKlineDailyRepository klineRepository) {
        this.watchlistRepository = watchlistRepository;
        this.stockQuoteRepository = stockQuoteRepository;
        this.klineRepository = klineRepository;
    }

    /** 自选股条目 + 内联行情速览（无行情数据时三个行情字段置 null，不报错） */
    public record WatchlistQuoteView(Long id, String stockCode, String stockName, String groupName,
                                     String tags, String note, LocalDateTime createdAt,
                                     BigDecimal latestPrice, BigDecimal changePct, String quoteDate) {
    }

    @GetMapping
    public ResponseEntity<List<WatchlistQuoteView>> listAll() {
        List<WatchlistQuoteView> views = watchlistRepository.findAllByOrderByGroupNameAscCreatedAtDesc()
                .stream().map(this::withQuote).toList();
        return ResponseEntity.ok(views);
    }

    /** 行情内联：优先 stock_quote 最新一条（现价+涨跌幅现成），缺失时退化用日K最新收盘价推算 */
    private WatchlistQuoteView withQuote(Watchlist w) {
        BigDecimal latestPrice = null;
        BigDecimal changePct = null;
        String quoteDate = null;
        try {
            StockQuote quote = stockQuoteRepository.findTopByCodeOrderByUpdateTimeDesc(w.getStockCode()).orElse(null);
            if (quote != null && quote.getPrice() != null) {
                latestPrice = quote.getPrice();
                changePct = quote.getChangePct();
                quoteDate = quote.getUpdateTime() == null ? null : quote.getUpdateTime().toLocalDate().toString();
            } else {
                List<StockKlineDaily> klines = klineRepository.findTop2ByCodeOrderByTradeDateDesc(w.getStockCode());
                if (!klines.isEmpty() && klines.get(0).getClose() != null) {
                    latestPrice = klines.get(0).getClose();
                    quoteDate = klines.get(0).getTradeDate() == null ? null : klines.get(0).getTradeDate().toString();
                    if (klines.size() > 1 && klines.get(1).getClose() != null
                            && klines.get(1).getClose().signum() != 0) {
                        BigDecimal prevClose = klines.get(1).getClose();
                        changePct = latestPrice.subtract(prevClose)
                                .multiply(BigDecimal.valueOf(100))
                                .divide(prevClose, 2, RoundingMode.HALF_UP);
                    }
                }
            }
        } catch (Exception e) {
            // 行情查询异常不阻断自选股列表，行情字段置 null
            log.warn("自选股行情速览查询失败: code={}, err={}", w.getStockCode(), e.getMessage());
        }
        return new WatchlistQuoteView(w.getId(), w.getStockCode(), w.getStockName(), w.getGroupName(),
                w.getTags(), w.getNote(), w.getCreatedAt(), latestPrice, changePct, quoteDate);
    }

    @PostMapping
    public ResponseEntity<Watchlist> add(@RequestBody Watchlist watchlist) {
        if (watchlist.getStockCode() == null || watchlist.getStockCode().isBlank()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        if (watchlist.getStockName() == null || watchlist.getStockName().isBlank()) {
            throw new IllegalArgumentException("股票名称不能为空");
        }
        if (watchlistRepository.existsByStockCode(watchlist.getStockCode())) {
            throw new IllegalArgumentException("股票 " + watchlist.getStockCode() + " 已在自选股中");
        }
        watchlist.setId(null);
        if (watchlist.getGroupName() == null || watchlist.getGroupName().isBlank()) {
            watchlist.setGroupName("默认分组");
        }
        return ResponseEntity.ok(watchlistRepository.save(watchlist));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Watchlist> update(@PathVariable Long id, @RequestBody Watchlist patch) {
        Watchlist existing = watchlistRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("自选股记录不存在: " + id));
        // stockCode 变更时同样做去重校验
        if (patch.getStockCode() != null && !patch.getStockCode().isBlank()
                && !patch.getStockCode().equals(existing.getStockCode())) {
            if (watchlistRepository.existsByStockCode(patch.getStockCode())) {
                throw new IllegalArgumentException("股票 " + patch.getStockCode() + " 已在自选股中");
            }
            existing.setStockCode(patch.getStockCode());
        }
        if (patch.getStockName() != null && !patch.getStockName().isBlank()) {
            existing.setStockName(patch.getStockName());
        }
        if (patch.getGroupName() != null && !patch.getGroupName().isBlank()) {
            existing.setGroupName(patch.getGroupName());
        }
        if (patch.getTags() != null) {
            existing.setTags(patch.getTags());
        }
        if (patch.getNote() != null) {
            existing.setNote(patch.getNote());
        }
        return ResponseEntity.ok(watchlistRepository.save(existing));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!watchlistRepository.existsById(id)) {
            throw new NoSuchElementException("自选股记录不存在: " + id);
        }
        watchlistRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
