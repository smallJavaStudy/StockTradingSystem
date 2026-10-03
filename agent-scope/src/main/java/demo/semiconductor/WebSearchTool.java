package demo.semiconductor;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web 搜索工具 — 使用 DuckDuckGo HTML 搜索，无需 API Key。
 *
 * <p>Agent 调用示例（Agent 自然语言触发）：
 * <pre>
 *   - "搜索中芯国际 14nm 量产时间"
 *   - "搜索半导体光刻机 国产化率 2025"
 * </pre>
 */
public class WebSearchTool {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    // DuckDuckGo HTML 搜索（非 JS 渲染，Agent-friendly）
    private static final String SEARCH_URL = "https://html.duckduckgo.com/html/?q=";

    // 提取搜索结果的正则
    private static final Pattern RESULT_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");

    @Tool(name = "web_search",
          description = "搜索互联网获取半导体、芯片制造、国产化等相关信息。返回标题、链接和摘要。",
          readOnly = true, concurrencySafe = true)
    public String webSearch(
            @ToolParam(name = "query", description = "搜索关键词，中英文均可。建议包含具体技术名词或公司名")
            String query,
            @ToolParam(name = "max_results", description = "最大返回结果数，默认8")
            String maxResults) {

        int max = 8;
        try { max = Integer.parseInt(maxResults); } catch (Exception ignored) {}

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SEARCH_URL + encoded))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "text/html")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            String html = resp.body();

            if (html == null || html.isBlank()) {
                return "[web_search] 搜索无结果或DuckDuckGo不可用。请换用其他搜索方式。";
            }

            List<SearchResult> results = parseResults(html, max);
            if (results.isEmpty()) {
                return "[web_search] 未解析到搜索结果。HTML长度: " + html.length();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[web_search] 搜索 \"").append(query).append("\" 的结果：\n\n");
            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                sb.append(i + 1).append(". ").append(r.title).append("\n");
                sb.append("   链接: ").append(r.url).append("\n");
                sb.append("   摘要: ").append(r.snippet).append("\n\n");
            }
            return sb.toString();

        } catch (Exception e) {
            return "[web_search] 搜索失败: " + e.getMessage()
                    + "\n请尝试使用 web_fetch 工具直接访问已知的芯片资料网站。";
        }
    }

    private List<SearchResult> parseResults(String html, int max) {
        List<SearchResult> results = new ArrayList<>();

        // 提取所有结果链接
        Matcher linkMatcher = RESULT_PATTERN.matcher(html);
        List<String[]> links = new ArrayList<>();
        while (linkMatcher.find() && links.size() < max + 5) {
            String url = cleanUrl(linkMatcher.group(1));
            String title = stripTags(linkMatcher.group(2)).trim();
            if (!title.isEmpty() && !url.contains("duckduckgo.com")) {
                links.add(new String[]{title, url});
            }
        }

        // 提取所有摘要
        Matcher snippetMatcher = SNIPPET_PATTERN.matcher(html);
        List<String> snippets = new ArrayList<>();
        while (snippetMatcher.find() && snippets.size() < max + 5) {
            String s = stripTags(snippetMatcher.group(1)).trim();
            if (!s.isEmpty()) snippets.add(s);
        }

        // 配对
        for (int i = 0; i < Math.min(links.size(), Math.min(max, snippets.size())); i++) {
            results.add(new SearchResult(links.get(i)[0], links.get(i)[1], snippets.get(i)));
        }
        return results;
    }

    private String cleanUrl(String raw) {
        // DuckDuckGo 的链接格式: //duckduckgo.com/l/?uddg=ENCODED_URL&rut=...
        if (raw.startsWith("//")) raw = "https:" + raw;
        if (raw.contains("uddg=")) {
            int start = raw.indexOf("uddg=") + 5;
            int end = raw.indexOf("&", start);
            if (end == -1) end = raw.length();
            try {
                return java.net.URLDecoder.decode(raw.substring(start, end), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    private String stripTags(String html) {
        return TAG_RE.matcher(html).replaceAll("").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#x27;", "'")
                .replace("&nbsp;", " ").trim();
    }

    record SearchResult(String title, String url, String snippet) {}
}
