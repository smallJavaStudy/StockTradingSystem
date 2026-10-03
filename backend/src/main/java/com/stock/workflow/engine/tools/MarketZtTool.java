package com.stock.workflow.engine.tools;

import com.stock.entity.StockZtPool;
import com.stock.repository.StockZtPoolRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * market_zt 自定义工具：按日期查询涨停股池与连板梯队统计（真实 DB 数据）。
 * <p>注册方式：Toolkit.registerTool(实例)，与 {@link StockKlineTool} 同一机制。
 * <p>数据说明：涨停原因 reason 字段数据源未提供（恒空），题材线索请用 ztStat（涨停统计，
 * 如 "7/7" 表示7天7板）与 industry 行业分布推断。
 */
@Component
public class MarketZtTool {

    static final int DETAIL_LIMIT = 40;
    static final int INDUSTRY_LIMIT = 10;

    private final StockZtPoolRepository ztPoolRepo;

    public MarketZtTool(StockZtPoolRepository ztPoolRepo) {
        this.ztPoolRepo = ztPoolRepo;
    }

    @Tool(name = "market_zt",
          description = "查询A股涨停股池：指定交易日的涨停家数、连板梯队分布、最高连板、炸板统计、"
                  + "行业分布与个股明细（含涨停统计ztStat如\"7/7\"=7天7板）。数据来自本地数据库真实盘面。",
          readOnly = true, concurrencySafe = true)
    public String marketZt(
            @ToolParam(name = "date", description = "交易日期，格式 yyyy-MM-dd，缺省为最新有数据交易日", required = false)
            String date,
            @ToolParam(name = "poolType", description = "池类型：TODAY=当日涨停池（默认）/ PREVIOUS=昨日涨停池今日表现", required = false)
            String poolType) {
        LocalDate tradeDate = ToolParams.parseDate(date);
        if (tradeDate == null) {
            if (date != null && !date.isBlank()) {
                return "[market_zt] 参数错误：date 必须是 yyyy-MM-dd 格式，实际: " + date;
            }
            tradeDate = ztPoolRepo.findMaxTradeDate().orElse(null);
            if (tradeDate == null) {
                return "[market_zt] 数据库中暂无涨停池数据";
            }
        }
        String pool = normalizePoolType(poolType);
        try {
            List<StockZtPool> rows =
                    ztPoolRepo.findByTradeDateAndPoolTypeOrderByLimitUpDaysDescChangePctDesc(tradeDate, pool);
            if (rows.isEmpty()) {
                return "[market_zt] " + tradeDate + " 无 " + pool + " 涨停池数据（可能非交易日或数据未抓取，"
                        + "最新有数据日期: " + ztPoolRepo.findMaxTradeDate().map(String::valueOf).orElse("无") + "）";
            }
            return buildSummary(tradeDate, pool, rows);
        } catch (Exception e) {
            return "[market_zt] 查询失败: " + e.getMessage();
        }
    }

    private String normalizePoolType(String raw) {
        if (raw == null || raw.isBlank()) return "TODAY";
        String v = raw.trim().toUpperCase();
        return v.startsWith("P") || v.contains("昨") ? "PREVIOUS" : "TODAY";
    }

    private String buildSummary(LocalDate tradeDate, String pool, List<StockZtPool> rows) {
        // 连板梯队分布（板数 → 家数，倒序）与炸板统计
        Map<Integer, Integer> ladder = new TreeMap<>((a, b) -> b - a);
        int broken = 0;
        int maxDays = 0;
        Map<String, Integer> industryCount = new LinkedHashMap<>();
        for (StockZtPool z : rows) {
            int days = z.getLimitUpDays() == null ? 1 : Math.max(1, z.getLimitUpDays());
            ladder.merge(days, 1, Integer::sum);
            maxDays = Math.max(maxDays, days);
            if (z.getOpenTimes() != null && z.getOpenTimes() > 0) broken++;
            String ind = z.getIndustry() == null || z.getIndustry().isBlank() ? "未知" : z.getIndustry();
            industryCount.merge(ind, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【涨停池 ").append(tradeDate).append(" ")
          .append("TODAY".equals(pool) ? "当日涨停池" : "昨日涨停池今日表现").append("】\n");
        sb.append("涨停家数: ").append(rows.size())
          .append(" | 最高连板: ").append(maxDays).append("板")
          .append(" | 炸板股(开板次数>0): ").append(broken)
          .append("家 (炸板率约 ").append(pct(broken, rows.size())).append("%)\n");

        sb.append("连板梯队: ");
        ladder.forEach((days, cnt) -> sb.append(days).append("板x").append(cnt).append("  "));
        sb.append("\n");

        sb.append("行业分布Top").append(INDUSTRY_LIMIT).append(": ");
        industryCount.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(INDUSTRY_LIMIT)
                .forEach(e -> sb.append(e.getKey()).append("(").append(e.getValue()).append(")  "));
        sb.append("\n");

        sb.append("个股明细Top").append(Math.min(DETAIL_LIMIT, rows.size()))
          .append("（按连板数降序；ztStat=涨停统计如7/7表示7天7板；reason字段数据源未提供请用ztStat与行业推断题材）:\n");
        sb.append("代码 | 名称 | 连板 | 涨停统计 | 涨幅% | 首封时间 | 开板次数 | 换手% | 行业\n");
        rows.stream().limit(DETAIL_LIMIT).forEach(z -> sb.append(z.getCode()).append(" | ")
                .append(nvl(z.getName())).append(" | ")
                .append(z.getLimitUpDays() == null ? 1 : z.getLimitUpDays()).append("板 | ")
                .append(nvl(z.getZtStat())).append(" | ")
                .append(num(z.getChangePct())).append(" | ")
                .append(nvl(z.getFirstTime())).append(" | ")
                .append(z.getOpenTimes() == null ? 0 : z.getOpenTimes()).append(" | ")
                .append(num(z.getTurnoverRate())).append(" | ")
                .append(nvl(z.getIndustry())).append("\n"));
        return sb.toString();
    }

    private static String pct(int part, int total) {
        if (total <= 0) return "0";
        return BigDecimal.valueOf(part * 100.0 / total).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nvl(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.toPlainString();
    }
}
