package com.stock.workflow.engine.tools;

import com.stock.service.SerperSearchService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

/**
 * web_search 自定义工具：Serper.dev（Google 搜索 API）实时网络搜索。
 * <p>系统默认优先的外部数据获取通道：比 data_enrich 更快（秒级）、返回真实搜索结果
 * 而非 LLM 生成内容，适合查询实时行情动态、新闻公告、行业政策等。
 * 注册方式与其他自定义工具一致（Toolkit.registerTool + @Tool 注解）。
 */
@Component
public class WebSearchTool {

    private final SerperSearchService serperSearch;

    public WebSearchTool(SerperSearchService serperSearch) {
        this.serperSearch = serperSearch;
    }

    @Tool(name = "web_search",
          description = "实时网络搜索（Google/Serper，系统默认优先数据源）：输入关键词返回真实搜索结果（标题/摘要/来源/日期），"
                  + "适合查询最新新闻公告、行情动态、行业政策、舆情等。比 data_enrich 更快且是真实网络数据，应优先使用。",
          readOnly = true, concurrencySafe = true)
    public String webSearch(
            @ToolParam(name = "query", description = "搜索关键词，如：中文在线 300364 最新公告")
            String query,
            @ToolParam(name = "type", description = "搜索类型：search=网页搜索(默认) / news=新闻搜索", required = false)
            String type) {
        if (query == null || query.isBlank()) {
            return "[web_search] 参数错误：query 不能为空";
        }
        if (!serperSearch.isAvailable()) {
            return "[web_search] Serper API 未配置（serper.api-key），无法搜索";
        }
        try {
            boolean news = type != null && type.trim().equalsIgnoreCase("news");
            String result = news ? serperSearch.searchNews(query.trim())
                    : serperSearch.search(query.trim());
            return result != null ? result : "[web_search] 未搜索到相关结果: " + query;
        } catch (Exception e) {
            return "[web_search] 搜索失败: " + e.getMessage();
        }
    }
}
