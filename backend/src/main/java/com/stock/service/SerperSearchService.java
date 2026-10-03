package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stock.config.SerperConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serper.dev（Google 搜索 API）客户端 — 系统默认优先的外部数据获取通道。
 * <p>
 * POST https://google.serper.dev/search，Header X-API-KEY，body {"q": "..."}。
 * 结果解析 answerBox / knowledgeGraph / organic 三段，拼装为可直接放进
 * LLM 提示词的紧凑文本。任何失败（未配置 key / 网络异常 / 无结果）返回 null，
 * 由调用方降级到 DeepSeek→Kimi 级联，不中断流程。
 */
@Service
public class SerperSearchService {

    private static final Logger log = LoggerFactory.getLogger(SerperSearchService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 单条 snippet 最大长度，防止搜索结果撑爆提示词 */
    private static final int MAX_SNIPPET_CHARS = 300;

    /** 行情片段模式：价格 + 涨跌额 + 涨跌幅%，兼容 "313.33 +0.92 (0.29%)" / "1309.22. +0.67 +0.05%" / "1,309.22 +0.67 (+0.05%)" */
    static final Pattern QUOTE_PATTERN = Pattern.compile(
            "(\\d[\\d,]*(?:\\.\\d+)?)\\.?\\s+([+-]\\d+(?:\\.\\d+)?)\\s+\\(?([+-]?\\d+(?:\\.\\d+)?)%");

    private final SerperConfig config;
    private final RestTemplate restTemplate;

    public SerperSearchService(SerperConfig config) {
        this.config = config;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeoutMs());
        factory.setReadTimeout(config.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    /** Serper 是否可用（api-key 已配置） */
    public boolean isAvailable() {
        return config.isAvailable();
    }

    /**
     * 通用网页搜索（默认中文地区偏好 gl=cn, hl=zh-cn）。
     *
     * @param query 搜索关键词
     * @return 拼装好的文本结果；失败或无结果返回 null（调用方降级）
     */
    public String search(String query) {
        return doSearch("/search", query);
    }

    /**
     * 新闻搜索（Serper /news 端点，用于舆情/最新动态类主题）。
     *
     * @param query 搜索关键词
     * @return 拼装好的文本结果；失败或无结果返回 null（调用方降级）
     */
    public String searchNews(String query) {
        return doSearch("/news", query);
    }

    /** 结构化实时行情（现价/涨跌额/涨跌幅%），数据准备闸门的行情首选通道 */
    public record QuoteResult(BigDecimal price, BigDecimal change, BigDecimal changePct) {}

    /**
     * 拉取个股实时行情：先取 Google 行情卡（answerBox，美股常见），
     * 未命中再扫 organic 摘要（A 股的雪球/Yahoo/新浪片段）。失败或解析不出返回 null（调用方降级东财）。
     */
    public QuoteResult fetchQuote(String code, String name) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String query = (name == null || name.isBlank() || name.equals(code))
                ? code + " 股价" : code + " " + name + " 股价";
        JsonNode root = postJson("/search", query);
        if (root == null) {
            return null;
        }
        QuoteResult r = parseQuote(root.path("answerBox").path("answer").asText(""));
        if (r == null) {
            r = parseQuote(root.path("answerBox").path("snippet").asText(""));
        }
        if (r == null) {
            for (JsonNode item : root.path("organic")) {
                r = parseQuote(item.path("snippet").asText(""));
                if (r != null) {
                    break;
                }
            }
        }
        if (r != null) {
            log.info("SerperSearchService: 行情命中 q=\"{}\" 现价 {} 涨跌幅 {}%", query, r.price(), r.changePct());
        }
        return r;
    }

    /** 从文本片段解析"价格 涨跌额 涨跌幅%"；无有效行情返回 null（包级可见供单测） */
    static QuoteResult parseQuote(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = QUOTE_PATTERN.matcher(text);
        if (!m.find()) {
            return null;
        }
        BigDecimal price = toDec(m.group(1).replace(",", ""));
        BigDecimal change = toDec(m.group(2));
        BigDecimal pct = toDec(m.group(3));
        if (price == null || price.signum() <= 0 || pct == null) {
            return null;
        }
        return new QuoteResult(price, change, pct);
    }

    private static BigDecimal toDec(String s) {
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String doSearch(String path, String query) {
        JsonNode root = postJson(path, query);
        if (root == null) {
            return null;
        }
        String text = formatResult(root, query);
        if (text != null) {
            log.info("SerperSearchService: [{}] 搜索成功 q=\"{}\"，结果 {} 字符", path, query, text.length());
        }
        return text;
    }

    /** 底层 POST：不可用/入参空/网络异常/非法 JSON 一律返回 null（调用方降级） */
    private JsonNode postJson(String path, String query) {
        if (!isAvailable()) {
            log.debug("SerperSearchService: api-key 未配置，跳过");
            return null;
        }
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("q", query.trim());
            body.put("num", config.getNum());
            body.put("gl", "cn");
            body.put("hl", "zh-cn");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-API-KEY", config.getApiKey());

            String resp = restTemplate.postForObject(
                    config.getBaseUrl() + path, new HttpEntity<>(body.toString(), headers), String.class);
            if (resp == null || resp.isBlank()) {
                return null;
            }
            return MAPPER.readTree(resp);
        } catch (Exception e) {
            log.warn("SerperSearchService: [{}] 调用失败 q=\"{}\": {}", path, query, e.getMessage());
            return null;
        }
    }

    /** answerBox / knowledgeGraph / organic|news 三段拼装；全空返回 null */
    private String formatResult(JsonNode root, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("【Serper搜索】").append(query).append("\n");
        boolean hasAny = false;

        JsonNode answerBox = root.path("answerBox");
        if (!answerBox.isMissingNode() && !answerBox.isNull()) {
            String answer = firstNonBlank(answerBox.path("answer").asText(""),
                    answerBox.path("snippet").asText(""));
            if (!answer.isBlank()) {
                sb.append("直接答案: ").append(truncate(answer)).append("\n");
                hasAny = true;
            }
        }

        JsonNode kg = root.path("knowledgeGraph");
        if (kg.isObject()) {
            String title = kg.path("title").asText("");
            String desc = kg.path("description").asText("");
            if (!title.isBlank() || !desc.isBlank()) {
                sb.append("知识图谱: ").append(title);
                if (!desc.isBlank()) {
                    sb.append(" — ").append(truncate(desc));
                }
                sb.append("\n");
                hasAny = true;
            }
        }

        // /search 返回 organic，/news 返回 news，结构一致（title/link/snippet/date）
        JsonNode items = root.has("organic") ? root.path("organic") : root.path("news");
        if (items.isArray() && !items.isEmpty()) {
            sb.append("搜索结果:\n");
            int i = 1;
            for (JsonNode item : items) {
                sb.append(i++).append(". ").append(item.path("title").asText(""));
                String date = item.path("date").asText("");
                if (!date.isBlank()) {
                    sb.append("（").append(date).append("）");
                }
                sb.append("\n");
                String snippet = item.path("snippet").asText("");
                if (!snippet.isBlank()) {
                    sb.append("   ").append(truncate(snippet)).append("\n");
                }
                String link = item.path("link").asText("");
                if (!link.isBlank()) {
                    sb.append("   来源: ").append(link).append("\n");
                }
            }
            hasAny = true;
        }

        return hasAny ? sb.toString().trim() : null;
    }

    private static String truncate(String s) {
        return s.length() > MAX_SNIPPET_CHARS ? s.substring(0, MAX_SNIPPET_CHARS) + "..." : s;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b != null ? b : "");
    }
}
