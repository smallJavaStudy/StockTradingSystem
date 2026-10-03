package com.stock.workflow.engine.tools;

import com.stock.workflow.engine.StockContextPreloader;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/** stock_finance 自定义工具：查询个股最近多期财务指标（真实 DB 数据）。 */
@Component
public class StockFinanceTool {

    private final StockContextPreloader preloader;

    public StockFinanceTool(StockContextPreloader preloader) {
        this.preloader = preloader;
    }

    @Tool(name = "stock_finance",
          description = "查询A股个股最近多期财务指标：EPS、ROE、营收、归母净利及同比。数据来自本地数据库真实财报。",
          readOnly = true, concurrencySafe = true)
    public String stockFinance(
            @ToolParam(name = "stockCode", description = "6位股票代码，如 600519")
            String stockCode) {
        String code = ToolParams.normalizeStockCode(stockCode);
        if (code == null) {
            return "[stock_finance] 参数错误：stockCode 必须是6位数字股票代码，实际: " + stockCode;
        }
        try {
            return preloader.buildFinanceContext(code);
        } catch (Exception e) {
            return "[stock_finance] 查询失败: " + e.getMessage();
        }
    }
}
