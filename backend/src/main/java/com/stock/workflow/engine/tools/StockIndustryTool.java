package com.stock.workflow.engine.tools;

import com.stock.workflow.engine.StockContextPreloader;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/** stock_industry 自定义工具：查询个股行业/产业链定位与竞品对比（真实 DB 数据）。 */
@Component
public class StockIndustryTool {

    private final StockContextPreloader preloader;

    public StockIndustryTool(StockContextPreloader preloader) {
        this.preloader = preloader;
    }

    @Tool(name = "stock_industry",
          description = "查询A股个股行业与产业链信息：所属行业、主营业务、产业链上下游定位、行业趋势及主要竞品对比。数据来自本地数据库。",
          readOnly = true, concurrencySafe = true)
    public String stockIndustry(
            @ToolParam(name = "stockCode", description = "6位股票代码，如 600519")
            String stockCode) {
        String code = ToolParams.normalizeStockCode(stockCode);
        if (code == null) {
            return "[stock_industry] 参数错误：stockCode 必须是6位数字股票代码，实际: " + stockCode;
        }
        try {
            String industry = preloader.buildIndustryContext(code);
            String company = preloader.buildCompanyContext(code);
            if (StockContextPreloader.NO_DATA.equals(industry)
                    && StockContextPreloader.NO_DATA.equals(company)) {
                return StockContextPreloader.NO_DATA;
            }
            return company + "\n\n" + industry;
        } catch (Exception e) {
            return "[stock_industry] 查询失败: " + e.getMessage();
        }
    }
}
