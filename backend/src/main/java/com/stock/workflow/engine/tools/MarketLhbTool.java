package com.stock.workflow.engine.tools;

import com.stock.entity.StockLhbDetail;
import com.stock.repository.StockLhbDetailRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * market_lhb 自定义工具：按日期或股票代码查询龙虎榜明细（真实 DB 数据）。
 * <p>注册方式：Toolkit.registerTool(实例)，与 {@link StockKlineTool} 同一机制。
 * <p>数据说明：同一股票同日可因多个上榜原因出现多行，属正常现象，金额单位为元（输出已折算为万元/亿元）。
 */
@Component
public class MarketLhbTool {

    static final int DATE_LIMIT = 40;
    static final int CODE_LIMIT = 20;

    private final StockLhbDetailRepository lhbRepo;

    public MarketLhbTool(StockLhbDetailRepository lhbRepo) {
        this.lhbRepo = lhbRepo;
    }

    @Tool(name = "market_lhb",
          description = "查询A股龙虎榜：按交易日期查当日上榜个股（净买额降序）或按股票代码查该股近期上榜记录，"
                  + "含买入/卖出/净买额、上榜原因与机构解读。数据来自本地数据库真实榜单。",
          readOnly = true, concurrencySafe = true)
    public String marketLhb(
            @ToolParam(name = "date", description = "交易日期，格式 yyyy-MM-dd，缺省为最新有数据交易日（stockCode 为空时生效）", required = false)
            String date,
            @ToolParam(name = "stockCode", description = "6位股票代码；填写后查该股近期上榜记录，忽略 date", required = false)
            String stockCode) {
        try {
            if (stockCode != null && !stockCode.isBlank()) {
                String code = ToolParams.normalizeStockCode(stockCode);
                if (code == null) {
                    return "[market_lhb] 参数错误：stockCode 必须是6位数字股票代码，实际: " + stockCode;
                }
                return byCode(code);
            }
            LocalDate tradeDate = ToolParams.parseDate(date);
            if (tradeDate == null) {
                if (date != null && !date.isBlank()) {
                    return "[market_lhb] 参数错误：date 必须是 yyyy-MM-dd 格式，实际: " + date;
                }
                tradeDate = lhbRepo.findMaxTradeDate().orElse(null);
                if (tradeDate == null) {
                    return "[market_lhb] 数据库中暂无龙虎榜数据";
                }
            }
            return byDate(tradeDate);
        } catch (Exception e) {
            return "[market_lhb] 查询失败: " + e.getMessage();
        }
    }

    private String byDate(LocalDate tradeDate) {
        List<StockLhbDetail> rows = lhbRepo.findByTradeDateOrderByNetAmountDesc(tradeDate);
        if (rows.isEmpty()) {
            return "[market_lhb] " + tradeDate + " 无龙虎榜数据（可能非交易日或数据未抓取，最新有数据日期: "
                    + lhbRepo.findMaxTradeDate().map(String::valueOf).orElse("无") + "）";
        }
        long netBuyCount = rows.stream()
                .filter(r -> r.getNetAmount() != null && r.getNetAmount().signum() > 0).count();
        StringBuilder sb = new StringBuilder();
        sb.append("【龙虎榜 ").append(tradeDate).append("】上榜记录 ").append(rows.size())
          .append(" 条（同股同日可因多个上榜原因多行），净买入记录 ").append(netBuyCount).append(" 条\n");
        sb.append("明细Top").append(Math.min(DATE_LIMIT, rows.size())).append("（按净买额降序）:\n");
        sb.append("代码 | 名称 | 涨幅% | 净买额 | 买入额 | 卖出额 | 上榜原因 | 解读\n");
        rows.stream().limit(DATE_LIMIT).forEach(r -> appendRow(sb, r, false));
        return sb.toString();
    }

    private String byCode(String code) {
        List<StockLhbDetail> rows = lhbRepo.findByCodeOrderByTradeDateDesc(code);
        if (rows.isEmpty()) {
            return "[market_lhb] 股票 " + code + " 近期无龙虎榜上榜记录";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【龙虎榜 个股 ").append(code).append(" ").append(nvl(rows.get(0).getName()))
          .append("】近 ").append(Math.min(CODE_LIMIT, rows.size())).append(" 条上榜记录（按日期倒序）:\n");
        sb.append("日期 | 涨幅% | 净买额 | 买入额 | 卖出额 | 上榜原因 | 解读\n");
        rows.stream().limit(CODE_LIMIT).forEach(r -> appendRow(sb, r, true));
        return sb.toString();
    }

    private void appendRow(StringBuilder sb, StockLhbDetail r, boolean withDate) {
        if (withDate) {
            sb.append(r.getTradeDate()).append(" | ");
        } else {
            sb.append(r.getCode()).append(" | ").append(nvl(r.getName())).append(" | ");
        }
        sb.append(num(r.getChangePct())).append(" | ")
          .append(money(r.getNetAmount())).append(" | ")
          .append(money(r.getBuyAmount())).append(" | ")
          .append(money(r.getSellAmount())).append(" | ")
          .append(nvl(r.getRankReason())).append(" | ")
          .append(nvl(r.getInterpretation())).append("\n");
    }

    /** 金额（元）折算为万元/亿元可读文本 */
    private static String money(BigDecimal v) {
        if (v == null) return "-";
        BigDecimal abs = v.abs();
        if (abs.compareTo(BigDecimal.valueOf(100_000_000)) >= 0) {
            return v.divide(BigDecimal.valueOf(100_000_000), 2, RoundingMode.HALF_UP).toPlainString() + "亿";
        }
        return v.divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).toPlainString() + "万";
    }

    private static String nvl(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }
}
