package com.stock.service;

import com.stock.entity.PriceAlert;
import com.stock.entity.StockQuote;
import com.stock.repository.PriceAlertRepository;
import com.stock.repository.StockQuoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 价格预警扫描：每 5 分钟扫描 enabled 且未触发的预警，
 * 对照 StockQuote 最新价，命中则置 triggered=true 并记录触发时间。
 * <p>
 * 注：@EnableScheduling 由 config/SchedulingConfig.java 全局开启（并行任务负责），
 * 本服务只声明 @Scheduled 任务。
 */
@Service
public class AlertScanService {

    private static final Logger log = LoggerFactory.getLogger(AlertScanService.class);

    /** 扫描间隔：5 分钟 */
    static final long SCAN_INTERVAL_MS = 5 * 60 * 1000L;

    private final PriceAlertRepository alertRepo;
    private final StockQuoteRepository quoteRepo;

    public AlertScanService(PriceAlertRepository alertRepo, StockQuoteRepository quoteRepo) {
        this.alertRepo = alertRepo;
        this.quoteRepo = quoteRepo;
    }

    @Scheduled(fixedRate = SCAN_INTERVAL_MS, initialDelay = 60_000)
    public void scheduledScan() {
        try {
            int triggered = scanOnce();
            if (triggered > 0) {
                log.info("AlertScanService: 本轮触发 {} 条价格预警", triggered);
            }
        } catch (Exception e) {
            log.error("AlertScanService: 扫描异常", e);
        }
    }

    /**
     * 扫描一轮所有待触发预警，返回本轮触发条数。
     * 同一股票的最新行情只查一次；无行情数据的股票跳过。
     */
    public int scanOnce() {
        List<PriceAlert> pending = alertRepo.findByEnabledTrueAndTriggeredFalse();
        if (pending.isEmpty()) {
            return 0;
        }
        Map<String, Optional<StockQuote>> quoteCache = new HashMap<>();
        int triggered = 0;
        for (PriceAlert alert : pending) {
            Optional<StockQuote> quote = quoteCache.computeIfAbsent(alert.getCode(),
                    quoteRepo::findTopByCodeOrderByUpdateTimeDesc);
            if (quote.isEmpty() || quote.get().getPrice() == null) {
                continue;
            }
            if (isHit(alert, quote.get().getPrice())) {
                alert.setTriggered(true);
                alert.setTriggeredAt(LocalDateTime.now());
                alertRepo.save(alert);
                triggered++;
                log.info("AlertScanService: 预警触发 code={} type={} threshold={} price={}",
                        alert.getCode(), alert.getType(), alert.getThreshold(), quote.get().getPrice());
            }
        }
        return triggered;
    }

    /** 判定命中：PRICE_ABOVE 现价≥阈值；PRICE_BELOW 现价≤阈值 */
    boolean isHit(PriceAlert alert, BigDecimal price) {
        return switch (alert.getType()) {
            case PRICE_ABOVE -> price.compareTo(alert.getThreshold()) >= 0;
            case PRICE_BELOW -> price.compareTo(alert.getThreshold()) <= 0;
        };
    }
}
