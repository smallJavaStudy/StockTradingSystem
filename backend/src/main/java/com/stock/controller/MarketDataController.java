package com.stock.controller;

import com.stock.entity.*;
import com.stock.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 市场级数据查询 API：涨停股池 / 龙虎榜 / 北向资金 / 融资融券 / 大宗交易。
 * <p>
 * 数据由 data-fetcher/fetch_market.py（AKShare）直写 MySQL，本控制器只读。
 * 日期参数与返回统一 yyyy-MM-dd；字段无值时返回 null，不做兜底填充。
 */
@RestController
@RequestMapping("/api/market")
public class MarketDataController {

    /** 今日涨停池 */
    public static final String POOL_TODAY = "TODAY";
    /** 昨日涨停池 */
    public static final String POOL_PREVIOUS = "PREVIOUS";

    private static final int MAX_DATES = 30;

    private final StockZtPoolRepository ztPoolRepo;
    private final StockLhbDetailRepository lhbRepo;
    private final StockNorthFlowRepository northFlowRepo;
    private final StockMarginDailyRepository marginRepo;
    private final StockBlockTradeRepository blockTradeRepo;

    public MarketDataController(StockZtPoolRepository ztPoolRepo,
                                StockLhbDetailRepository lhbRepo,
                                StockNorthFlowRepository northFlowRepo,
                                StockMarginDailyRepository marginRepo,
                                StockBlockTradeRepository blockTradeRepo) {
        this.ztPoolRepo = ztPoolRepo;
        this.lhbRepo = lhbRepo;
        this.northFlowRepo = northFlowRepo;
        this.marginRepo = marginRepo;
        this.blockTradeRepo = blockTradeRepo;
    }

    // ==================== 涨停股池 ====================

    /**
     * 涨停股池。date 缺省取最新有数据的交易日；poolType 缺省 TODAY（今日涨停池）。
     * amount 单位为元，changePct/turnoverRate 单位为 %。
     */
    @GetMapping("/zt-pool")
    public ResponseEntity<List<ZtPoolItem>> ztPool(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = POOL_TODAY) String poolType) {
        LocalDate target = date != null ? date : ztPoolRepo.findMaxTradeDate().orElse(null);
        if (target == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<StockZtPool> rows = ztPoolRepo
                .findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(target, poolType);
        return ResponseEntity.ok(rows.stream().map(ZtPoolItem::of).toList());
    }

    // ==================== 龙虎榜 ====================

    /** 龙虎榜明细。date 缺省取最新有数据的交易日；金额单位为元。 */
    @GetMapping("/lhb")
    public ResponseEntity<List<LhbItem>> lhb(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : lhbRepo.findMaxTradeDate().orElse(null);
        if (target == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(lhbRepo.findByTradeDateOrderByNetAmountDesc(target)
                .stream().map(LhbItem::of).toList());
    }

    // ==================== 北向资金 ====================

    /** 北向资金最近 days 个有数据交易日，按日期升序（便于前端绘制时序图）；单位亿元。 */
    @GetMapping("/north-flow")
    public ResponseEntity<List<NorthFlowItem>> northFlow(
            @RequestParam(required = false, defaultValue = "30") int days) {
        int limit = normalizeDays(days);
        List<StockNorthFlow> desc = northFlowRepo.findByOrderByTradeDateDesc(PageRequest.of(0, limit));
        return ResponseEntity.ok(desc.stream()
                .sorted((a, b) -> a.getTradeDate().compareTo(b.getTradeDate()))
                .map(NorthFlowItem::of).toList());
    }

    // ==================== 融资融券 ====================

    /**
     * 融资融券最近 days 个有数据交易日的沪深两市数据（每日 2 行：SH/SZ），
     * 按日期升序、同日 SH 在前；金额单位为元。
     */
    @GetMapping("/margin")
    public ResponseEntity<List<MarginItem>> margin(
            @RequestParam(required = false, defaultValue = "30") int days) {
        int limit = normalizeDays(days);
        List<LocalDate> dates = marginRepo.findDistinctTradeDates(PageRequest.of(0, limit));
        if (dates.isEmpty()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        List<StockMarginDaily> rows = marginRepo.findByTradeDateInOrderByTradeDateDescMarketAsc(dates);
        return ResponseEntity.ok(rows.stream()
                .sorted((a, b) -> {
                    int c = a.getTradeDate().compareTo(b.getTradeDate());
                    return c != 0 ? c : a.getMarket().compareTo(b.getMarket());
                })
                .map(MarginItem::of).toList());
    }

    // ==================== 大宗交易 ====================

    /** 大宗交易明细。date 缺省取最新有数据的交易日；price/amount 单位为元，volume 为股。 */
    @GetMapping("/block-trade")
    public ResponseEntity<List<BlockTradeItem>> blockTrade(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date != null ? date : blockTradeRepo.findMaxTradeDate().orElse(null);
        if (target == null) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(blockTradeRepo.findByTradeDateOrderByAmountDesc(target)
                .stream().map(BlockTradeItem::of).toList());
    }

    // ==================== 有数据的交易日 ====================

    /** 最近 30 个有数据的交易日（倒序）。type=zt|lhb|block|margin，缺省 zt。 */
    @GetMapping("/dates")
    public ResponseEntity<List<LocalDate>> dates(
            @RequestParam(required = false, defaultValue = "zt") String type) {
        PageRequest page = PageRequest.of(0, MAX_DATES);
        List<LocalDate> dates = switch (type == null ? "zt" : type.toLowerCase()) {
            case "lhb" -> lhbRepo.findDistinctTradeDates(page);
            case "block" -> blockTradeRepo.findDistinctTradeDates(page);
            case "margin" -> marginRepo.findDistinctTradeDates(page);
            default -> ztPoolRepo.findDistinctTradeDates(page);
        };
        return ResponseEntity.ok(dates);
    }

    private static int normalizeDays(int days) {
        if (days <= 0) {
            return 30;
        }
        return Math.min(days, 500);
    }

    // ==================== 响应 DTO ====================

    /** 涨停池条目：amount(元)、changePct/turnoverRate(%)、时间为 HH:mm:ss */
    public record ZtPoolItem(String code, String name, LocalDate tradeDate, BigDecimal closePrice,
                             BigDecimal changePct, Integer limitUpDays, String firstTime, String lastTime,
                             Integer openTimes, BigDecimal amount, BigDecimal turnoverRate,
                             String industry, String reason) {
        static ZtPoolItem of(StockZtPool z) {
            return new ZtPoolItem(z.getCode(), z.getName(), z.getTradeDate(), z.getClosePrice(),
                    z.getChangePct(), z.getLimitUpDays(), z.getFirstTime(), z.getLastTime(),
                    z.getOpenTimes(), z.getAmount(), z.getTurnoverRate(), z.getIndustry(), z.getReason());
        }
    }

    /** 龙虎榜条目：金额单位元，changePct 单位 % */
    public record LhbItem(String code, String name, LocalDate tradeDate, String rankReason,
                          BigDecimal buyAmount, BigDecimal sellAmount, BigDecimal netAmount,
                          BigDecimal totalAmount, BigDecimal changePct) {
        static LhbItem of(StockLhbDetail l) {
            return new LhbItem(l.getCode(), l.getName(), l.getTradeDate(), l.getRankReason(),
                    l.getBuyAmount(), l.getSellAmount(), l.getNetAmount(), l.getTotalAmount(), l.getChangePct());
        }
    }

    /** 北向资金条目：netFlow/accumFlow 单位亿元 */
    public record NorthFlowItem(LocalDate tradeDate, BigDecimal netFlow, BigDecimal accumFlow) {
        static NorthFlowItem of(StockNorthFlow n) {
            return new NorthFlowItem(n.getTradeDate(), n.getNetFlow(), n.getAccumFlow());
        }
    }

    /** 融资融券条目：market=SH/SZ，余额单位元 */
    public record MarginItem(LocalDate tradeDate, String market, BigDecimal financingBalance,
                             BigDecimal securitiesBalance, BigDecimal totalBalance) {
        static MarginItem of(StockMarginDaily m) {
            return new MarginItem(m.getTradeDate(), m.getMarket(), m.getFinancingBalance(),
                    m.getSecuritiesBalance(), m.getTotalBalance());
        }
    }

    /** 大宗交易条目：price/amount 单位元，volume 单位股，premiumRate 单位 %（负为折价） */
    public record BlockTradeItem(String code, String name, LocalDate tradeDate, BigDecimal price,
                                 Long volume, BigDecimal amount, BigDecimal premiumRate,
                                 String buyerBranch, String sellerBranch) {
        static BlockTradeItem of(StockBlockTrade b) {
            return new BlockTradeItem(b.getCode(), b.getName(), b.getTradeDate(), b.getPrice(),
                    b.getVolume(), b.getAmount(), b.getPremiumRate(), b.getBuyerBranch(), b.getSellerBranch());
        }
    }
}
