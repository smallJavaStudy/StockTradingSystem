package com.stock.workflow.engine.tools;

import com.stock.workflow.engine.StockContextPreloader;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * stock_kline 自定义工具：查询个股日K线与均线摘要（真实 DB 数据）。
 * <p>注册方式：Toolkit.registerTool(实例)，方法上的 @Tool 注解被 Harness 扫描为工具 Schema
 * （与 agent-scope 模块 WebSearchTool 同一机制，2.0.0-RC2 已验证）。
 */
@Component
public class StockKlineTool {

    static final int DEFAULT_DAYS = 120;

    private final StockContextPreloader preloader;

    public StockKlineTool(StockContextPreloader preloader) {
        this.preloader = preloader;
    }

    @Tool(name = "stock_kline",
          description = "查询A股个股日K线数据摘要：最新价、MA5/20/60均线、量价特征与近30日明细。数据来自本地数据库真实行情。",
          readOnly = true, concurrencySafe = true)
    public String stockKline(
            @ToolParam(name = "stockCode", description = "6位股票代码，如 600519")
            String stockCode,
            @ToolParam(name = "days", description = "查询天数（交易日），默认120，范围5-250", required = false)
            String days) {
        String code = ToolParams.normalizeStockCode(stockCode);
        if (code == null) {
            return "[stock_kline] 参数错误：stockCode 必须是6位数字股票代码，实际: " + stockCode;
        }
        int n = ToolParams.parseDays(days, DEFAULT_DAYS, 5, 250);
        try {
            return preloader.buildKlineContext(code, n);
        } catch (Exception e) {
            return "[stock_kline] 查询失败: " + e.getMessage();
        }
    }
}
