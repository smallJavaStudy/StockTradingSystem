package com.stock.workflow.engine.tools;

import com.stock.workflow.engine.StockContextPreloader;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/** stock_fundflow 自定义工具：查询个股近 N 日资金流（真实 DB 数据）。 */
@Component
public class StockFundFlowTool {

    static final int DEFAULT_DAYS = 20;

    private final StockContextPreloader preloader;

    public StockFundFlowTool(StockContextPreloader preloader) {
        this.preloader = preloader;
    }

    @Tool(name = "stock_fundflow",
          description = "查询A股个股近N日资金流：主力净流入序列、净占比与整体趋势。数据来自本地数据库。",
          readOnly = true, concurrencySafe = true)
    public String stockFundflow(
            @ToolParam(name = "stockCode", description = "6位股票代码，如 600519")
            String stockCode,
            @ToolParam(name = "days", description = "查询天数（交易日），默认20，范围5-60", required = false)
            String days) {
        String code = ToolParams.normalizeStockCode(stockCode);
        if (code == null) {
            return "[stock_fundflow] 参数错误：stockCode 必须是6位数字股票代码，实际: " + stockCode;
        }
        int n = ToolParams.parseDays(days, DEFAULT_DAYS, 5, 60);
        try {
            return preloader.buildFundflowContext(code, n);
        } catch (Exception e) {
            return "[stock_fundflow] 查询失败: " + e.getMessage();
        }
    }
}
