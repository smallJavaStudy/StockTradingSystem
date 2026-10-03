package demo.semiconductor;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Web 页面抓取工具 — 获取指定 URL 的文本内容。
 *
 * <p>用于获取 WebSearchTool 找到的页面的详细内容。
 * 自动去除 HTML 标签，只保留可读文本。
 */
public class WebFetchTool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Tool(name = "web_fetch",
          description = "抓取指定URL的网页内容，提取纯文本。用于获取搜索结果的详细内容。最大返回8000字符。",
          readOnly = true, concurrencySafe = true)
    public String webFetch(
            @ToolParam(name = "url", description = "要抓取的网页URL")
            String url) {

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; SemiconductorResearch/1.0)")
                    .header("Accept", "text/html,text/plain")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                return "[web_fetch] HTTP " + resp.statusCode() + " — " + url;
            }

            String html = resp.body();
            String text = htmlToText(html);

            if (text.length() > 8000) {
                text = text.substring(0, 8000) + "\n\n... [内容已截断，共 " + text.length() + " 字符]";
            }

            return "[web_fetch] " + url + "\n" + "=" .repeat(60) + "\n" + text;

        } catch (Exception e) {
            return "[web_fetch] 获取失败: " + e.getMessage() + " — " + url;
        }
    }

    /**
     * 简单的 HTML→文本转换。移除 script/style 标签和所有 HTML 标记。
     */
    private String htmlToText(String html) {
        // 移除 script 和 style
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        html = html.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        html = html.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");

        // 移除 HTML 注释
        html = html.replaceAll("(?is)<!--.*?-->", " ");

        // 块级元素换行
        html = html.replaceAll("(?i)<br\\s*/?>", "\n");
        html = html.replaceAll("(?i)</?p[^>]*>", "\n\n");
        html = html.replaceAll("(?i)</?(div|section|article|header|footer|h[1-6]|li|tr)[^>]*>", "\n");

        // 移除所有其他标签
        html = html.replaceAll("<[^>]+>", " ");

        // HTML实体解码
        html = html.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#x27;", "'")
                .replace("&nbsp;", " ").replace("&#160;", " ");

        // 压缩空白行
        html = html.replaceAll(" +", " ");
        html = html.replaceAll("\n{3,}", "\n\n");
        html = html.replaceAll("^\\s+", "");

        return html.trim();
    }
}
