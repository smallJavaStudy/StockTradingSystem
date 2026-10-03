package com.stock.workflow.engine.tools;

import com.stock.agent.AnalysisDirection;
import com.stock.service.DataEnricher;
import com.stock.workflow.engine.StockContextPreloader;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * data_enrich 自定义工具：对指定主题做级联补充查询（Serper搜索优先→DeepSeek→Kimi→DB 缓存）。
 * <p>
 * 注意耗时与限流：单次调用可能耗时 10~60 秒且消耗 LLM 配额，{@link DataEnricher}
 * 自带内存 + DB（enrichment_data 表，6 小时过期）两级缓存，相同 stockCode+topic
 * 会直接命中缓存，Agent 应避免对同一主题重复调用。
 */
@Component
public class DataEnrichTool {

    /** enrichment_data.direction_key 列长 50，预留前缀后主题最长 38 字符 */
    private static final int MAX_TOPIC_KEY_CHARS = 38;

    private final DataEnricher dataEnricher;
    private final StockContextPreloader preloader;

    public DataEnrichTool(DataEnricher dataEnricher, StockContextPreloader preloader) {
        this.dataEnricher = dataEnricher;
        this.preloader = preloader;
    }

    @Tool(name = "data_enrich",
          description = "对指定主题补充数据库没有的信息：优先 Serper 网络搜索，再由 LLM 级联（DeepSeek→Kimi）提炼，如舆情、估值、北向资金等。"
                  + "耗时较长（10~60秒）且有配额限制，结果带6小时缓存，同一主题请勿重复调用。",
          readOnly = true, concurrencySafe = false)
    public String dataEnrich(
            @ToolParam(name = "stockCode", description = "6位股票代码，如 600519")
            String stockCode,
            @ToolParam(name = "topic", description = "要补充查询的主题，如：近期舆情与投资者情绪 / 当前估值与同行对比")
            String topic) {
        String code = ToolParams.normalizeStockCode(stockCode);
        if (code == null) {
            return "[data_enrich] 参数错误：stockCode 必须是6位数字股票代码，实际: " + stockCode;
        }
        if (topic == null || topic.isBlank()) {
            return "[data_enrich] 参数错误：topic 不能为空";
        }
        String cleanTopic = topic.trim();
        try {
            String stockName = preloader.resolveStockName(code);
            if (stockName == null) {
                stockName = code;
            }
            // 轻量 DB 上下文（公司信息），避免把全量 K 线塞进 LLM 查询
            String dbContext = preloader.buildCompanyContext(code);

            AnalysisDirection.EnrichmentSpec spec = new AnalysisDirection.EnrichmentSpec(
                    "你是A股数据查询助手。回答必须基于最新市场数据，逐条给出信息来源方向（如公告/财报/行情软件），"
                            + "仅提供客观数据和事实，不做投资建议。",
                    "请查询{name}({code})关于「" + cleanTopic + "」的最新数据与事实。\n参考背景：{context}",
                    "你是A股数据查询专家，请基于最新数据补充关于「" + cleanTopic + "」的信息，标注来源类型。",
                    0, 0);

            String directionKey = buildDirectionKey(cleanTopic);
            // 完整主题作为 Serper 搜索词（directionKey 受列长限制被截断，不适合搜索）
            return dataEnricher.enrich(code, stockName, dbContext, directionKey, spec, cleanTopic);
        } catch (Exception e) {
            return "[data_enrich] 补充查询失败: " + e.getMessage();
        }
    }

    /** 缓存 key：WF_ENRICH_ + 截断后的主题（direction_key 列长 50） */
    static String buildDirectionKey(String topic) {
        String t = topic.length() > MAX_TOPIC_KEY_CHARS ? topic.substring(0, MAX_TOPIC_KEY_CHARS) : topic;
        return "WF_ENRICH_" + t;
    }
}
