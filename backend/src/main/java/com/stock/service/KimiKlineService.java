package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stock.entity.StockKlineDaily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 日K线获取服务 — 腾讯直连为主 → 新浪直连次之 → Kimi agent loop 兜底。
 * <p>
 * 背景：东财 push2his K线接口频繁连接中断（RemoteDisconnected），
 * 实测腾讯 ifzq 接口稳定且数据与东财逐条一致，故提为主源。
 * <p>
 * Kimi 兜底机制：kimi-for-coding 自身不联网，只产出工具调用。本服务向它声明
 * 受限的 {@code http_get} 工具（域名白名单，由本服务代为执行），Kimi 自主
 * 选择可用行情 URL、解析并把结果整理为固定 CSV；全程不执行 LLM 生成的代码。
 */
@Service
public class KimiKlineService {

    private static final Logger log = LoggerFactory.getLogger(KimiKlineService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TENCENT_KLINE_API = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
    private static final String SINA_KLINE_API =
            "https://quotes.sina.cn/cn/api/jsonp_v2.php/var/CN_MarketDataService.getKLineData";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    /** Kimi http_get 工具允许访问的域名后缀（白名单，防止被引导到任意地址） */
    private static final String[] HOST_WHITELIST = {
            "gtimg.cn", "tencent.com", "sina.cn", "sina.com.cn", "eastmoney.com"};
    private static final int MAX_TOOL_TURNS = 6;
    /** Kimi 返回体截断长度（K线 JSON 远小于此值，防异常大响应撑爆上下文） */
    private static final int MAX_TOOL_RESULT_CHARS = 60000;

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final RestTemplate chatRest;   // Kimi API（慢，长超时）
    private final RestTemplate dataRest;   // 行情接口（快，短超时）

    public KimiKlineService(@Value("${agent.model.kimi.api-key:}") String apiKey,
                            @Value("${agent.model.kimi.base-url:https://api.kimi.com/coding}") String baseUrl,
                            @Value("${agent.model.kimi.model-name:kimi-for-coding}") String modelName) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        SimpleClientHttpRequestFactory chatFactory = new SimpleClientHttpRequestFactory();
        chatFactory.setConnectTimeout(10000);
        chatFactory.setReadTimeout(180000);
        this.chatRest = new RestTemplate(chatFactory);
        SimpleClientHttpRequestFactory dataFactory = new SimpleClientHttpRequestFactory();
        dataFactory.setConnectTimeout(5000);
        dataFactory.setReadTimeout(15000);
        this.dataRest = new RestTemplate(dataFactory);
    }

    /**
     * 拉取日K线：腾讯 → 新浪 → Kimi 兜底。全部失败返回空列表（调用方决定降级）。
     *
     * @param days 目标条数（腾讯/新浪按此请求；Kimi 固定 120）
     */
    public List<StockKlineDaily> fetchKlines(String code, int days) {
        List<StockKlineDaily> rows = fetchFromTencent(code, days);
        if (!rows.isEmpty()) {
            log.info("KimiKlineService: 腾讯K线命中 {} {} 条", code, rows.size());
            return rows;
        }
        rows = fetchFromSina(code, days);
        if (!rows.isEmpty()) {
            log.info("KimiKlineService: 新浪K线命中 {} {} 条", code, rows.size());
            return rows;
        }
        rows = fetchViaKimi(code, 120);
        if (!rows.isEmpty()) {
            log.info("KimiKlineService: Kimi兜底K线命中 {} {} 条", code, rows.size());
        }
        return rows;
    }

    // ==================== 直连源 ====================

    /** 腾讯前复权日K：data.{sym}.qfqday|day = [[date,open,close,high,low,volume(手),分红对象?],...] */
    List<StockKlineDaily> fetchFromTencent(String code, int days) {
        try {
            String sym = symbol(code);
            String url = TENCENT_KLINE_API + "?param=" + sym + ",day,,," + days + ",qfq";
            JsonNode data = MAPPER.readTree(get(dataRest, url, "https://gu.qq.com/")).path("data").path(sym);
            JsonNode arr = data.has("qfqday") ? data.path("qfqday") : data.path("day");
            if (!arr.isArray() || arr.isEmpty()) {
                return List.of();
            }
            List<StockKlineDaily> rows = new ArrayList<>();
            for (JsonNode r : arr) {
                if (!r.isArray() || r.size() < 6) {
                    continue;
                }
                StockKlineDaily k = new StockKlineDaily();
                k.setTradeDate(LocalDate.parse(r.get(0).asText()));
                k.setOpen(new BigDecimal(r.get(1).asText()));
                k.setClose(new BigDecimal(r.get(2).asText()));
                k.setHigh(new BigDecimal(r.get(3).asText()));
                k.setLow(new BigDecimal(r.get(4).asText()));
                k.setVolume(new BigDecimal(r.get(5).asText()).longValue());
                rows.add(k);
            }
            return rows;
        } catch (Exception e) {
            log.warn("KimiKlineService: 腾讯K线失败 {}: {}", code, e.getMessage());
            return List.of();
        }
    }

    /** 新浪日K：JSONP var([{day,open,high,low,close,volume(股)}])；volume 需 /100 归一为手 */
    List<StockKlineDaily> fetchFromSina(String code, int days) {
        try {
            String url = SINA_KLINE_API + "?symbol=" + symbol(code) + "&scale=240&ma=no&datalen=" + days;
            String body = get(dataRest, url, "https://finance.sina.com.cn/");
            int l = body.indexOf('['), r = body.lastIndexOf(']');
            if (l < 0 || r <= l) {
                return List.of();
            }
            JsonNode arr = MAPPER.readTree(body.substring(l, r + 1));
            List<StockKlineDaily> rows = new ArrayList<>();
            for (JsonNode item : arr) {
                StockKlineDaily k = new StockKlineDaily();
                k.setTradeDate(LocalDate.parse(item.path("day").asText()));
                k.setOpen(new BigDecimal(item.path("open").asText()));
                k.setHigh(new BigDecimal(item.path("high").asText()));
                k.setLow(new BigDecimal(item.path("low").asText()));
                k.setClose(new BigDecimal(item.path("close").asText()));
                k.setVolume(Long.parseLong(item.path("volume").asText()) / 100);
                rows.add(k);
            }
            return rows;
        } catch (Exception e) {
            log.warn("KimiKlineService: 新浪K线失败 {}: {}", code, e.getMessage());
            return List.of();
        }
    }

    // ==================== Kimi agent loop 兜底 ====================

    /**
     * Kimi 兜底：声明受限 http_get 工具，由 Kimi 自主选择行情 URL 并整理为固定 CSV。
     * 本服务只代执行白名单域名的 GET，不执行任何 LLM 生成的代码。
     */
    List<StockKlineDaily> fetchViaKimi(String code, int days) {
        if (apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        try {
            ArrayNode messages = MAPPER.createArrayNode();
            messages.addObject().put("role", "user").put("content", buildTaskPrompt(code, days));
            for (int turn = 0; turn < MAX_TOOL_TURNS; turn++) {
                JsonNode msg = chat(messages);
                if (msg == null) {
                    return List.of();
                }
                JsonNode toolCalls = msg.path("tool_calls");
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    return parseCsv(msg.path("content").asText(""));
                }
                // 把 assistant 消息（含 tool_calls）追加进上下文
                ObjectNode assistant = MAPPER.createObjectNode();
                assistant.put("role", "assistant");
                assistant.put("content", msg.path("content").asText(""));
                assistant.set("tool_calls", toolCalls);
                messages.add(assistant);
                for (JsonNode tc : toolCalls) {
                    String url = MAPPER.readTree(tc.path("function").path("arguments").asText("{}"))
                            .path("url").asText("");
                    String result = allowedHost(url) ? safeGet(url) : "[拒绝] URL 不在白名单内: " + url;
                    messages.addObject()
                            .put("role", "tool")
                            .put("tool_call_id", tc.path("id").asText())
                            .put("content", truncate(result));
                }
            }
            log.warn("KimiKlineService: Kimi {} 轮内未给出最终K线", MAX_TOOL_TURNS);
            return List.of();
        } catch (Exception e) {
            log.warn("KimiKlineService: Kimi兜底失败 {}: {}", code, e.getMessage());
            return List.of();
        }
    }

    private String buildTaskPrompt(String code, int days) {
        return "任务：获取股票 " + code + " 最近约 " + days + " 个交易日的日K线数据。"
                + "你可以调用 http_get 工具请求行情接口（推荐："
                + "腾讯 https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" + symbol(code) + ",day,,," + days + ",qfq ，"
                + "响应中 data." + symbol(code) + ".qfqday 每行为 [date,open,close,high,low,volume(手)]；"
                + "或新浪 https://quotes.sina.cn/cn/api/jsonp_v2.php/var/CN_MarketDataService.getKLineData?symbol="
                + symbol(code) + "&scale=240&ma=no&datalen=" + days + " ，volume 单位为股需除以100）。"
                + "最终回答严格按CSV格式：date,open,close,high,low,volume，每行一个交易日，"
                + "date为YYYY-MM-DD升序，价格保留2位小数，volume单位手（整数），不要输出任何其他文字。"
                + "必须使用 http_get 获取真实数据，严禁编造。";
    }

    /** 单轮 chat completions（带 http_get 工具声明），返回 assistant message 节点 */
    private JsonNode chat(ArrayNode messages) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", modelName);
            body.set("messages", messages);
            body.put("stream", false);
            ObjectNode tool = (ObjectNode) MAPPER.readTree("""
                    {"type":"function","function":{"name":"http_get",
                    "description":"对指定URL发起HTTP GET请求并返回响应文本",
                    "parameters":{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}}}""");
            body.putArray("tools").add(tool);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");
            String resp = chatRest.exchange(baseUrl + "/v1/chat/completions", HttpMethod.POST,
                    new HttpEntity<>(MAPPER.writeValueAsString(body), headers), String.class).getBody();
            return MAPPER.readTree(resp).path("choices").path(0).path("message");
        } catch (Exception e) {
            log.warn("KimiKlineService: Kimi API 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String safeGet(String url) {
        try {
            return get(dataRest, url, "https://gu.qq.com/");
        } catch (Exception e) {
            return "[请求失败] " + e.getMessage();
        }
    }

    static boolean allowedHost(String url) {
        if (url == null || !url.startsWith("https://")) {
            return false;
        }
        String host = url.substring(8);
        int slash = host.indexOf('/');
        host = slash > 0 ? host.substring(0, slash) : host;
        for (String suffix : HOST_WHITELIST) {
            if (host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 Kimi 最终回答的固定 CSV：date,open,close,high,low,volume。
     * 容忍代码块标记/表头/说明文字，逐行校验后才入库（包级可见供单测）。
     */
    static List<StockKlineDaily> parseCsv(String text) {
        List<StockKlineDaily> rows = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return rows;
        }
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replace("`", "").trim();
            String[] p = line.split(",");
            if (p.length < 6) {
                continue;
            }
            try {
                StockKlineDaily k = new StockKlineDaily();
                k.setTradeDate(LocalDate.parse(p[0].trim()));
                k.setOpen(new BigDecimal(p[1].trim()));
                k.setClose(new BigDecimal(p[2].trim()));
                k.setHigh(new BigDecimal(p[3].trim()));
                k.setLow(new BigDecimal(p[4].trim()));
                k.setVolume(new BigDecimal(p[5].trim()).longValue());
                rows.add(k);
            } catch (RuntimeException ignored) {
                // 表头/说明文字等非数据行，跳过
            }
        }
        return rows;
    }

    // ==================== 工具方法 ====================

    private String get(RestTemplate template, String url, String referer) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Referer", referer);
        headers.set("User-Agent", UA);
        return template.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() <= MAX_TOOL_RESULT_CHARS ? s : s.substring(0, MAX_TOOL_RESULT_CHARS));
    }

    /** 行情接口 symbol：沪市（6 开头）前缀 sh，深市前缀 sz */
    private static String symbol(String code) {
        return code.startsWith("6") ? "sh" + code : "sz" + code;
    }
}
