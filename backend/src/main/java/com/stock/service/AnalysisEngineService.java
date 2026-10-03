package com.stock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stock.entity.*;
import com.stock.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class AnalysisEngineService {

    @Autowired private StockBasicRepository stockBasicRepo;
    @Autowired private AnalysisReportRepository reportRepo;
    @Autowired private DataContextBuilder dataContextBuilder;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ────────── DeepSeek API（密钥走配置链：环境变量 DEEPSEEK_API_KEY 或 application-local.yml）──────────
    @org.springframework.beans.factory.annotation.Value("${agent.model.deepseek-v4.api-key:}")
    private String apiKey;
    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String MODEL = "deepseek-chat";

    // ═══════════════════════════════════════════════════════════
    //  DEEP 分析范式 Prompt
    // ═══════════════════════════════════════════════════════════

    private static final String DEEP_SYSTEM_PROMPT = """
你是一个资深A股个股深度分析师。你的任务是根据系统提供的结构化数据库数据，对目标股票进行深度分析，不依赖外部知识。

【分析框架】
1. 基本面评分（三个维度各1-10分）：
   - 营收增长维度：从营收增速、产品线成长性、下游需求景气度评估
   - 盈利能力维度：从毛利率水平与稳定性、ROE水平评估
   - 安全性与质量维度：从负债安全性、现金流质量、客户分散度、治理结构评估

2. 产业链竞争力分析：
   - 议价力（对上游供应商、对下游客户各一句评价）
   - 护城河（2-4条具体护城河及其数据依据）
   - 周期定位与成长空间

3. 竞品对比结论：
   - 综合竞争力排名
   - 核心优势（2-3条，和竞品比最突出的）
   - 核心劣势（2-3条，和竞品比最明显的短板）

4. 风险提示（3-5条具体风险，基于输入数据中的具体信息，每条不超过25字）

5. 综合结论：评级（看多/看空/中性）+ 一句话理由（不超过100字）

【输出格式】纯JSON，无代码块标记：
{
  "fundamental_score": {"growth": 7, "profitability": 6, "safety": 5, "overall": 6, "note": "一句话总评"},
  "industry_competitiveness": {"supplier_power": "对上游议价力", "customer_power": "对下游议价力", "moat_items": ["护城河1", "护城河2"], "cycle_position": "周期位置", "growth_space": "成长空间"},
  "competitor_comparison": {"rank": 1, "total_compared": 3, "advantages": ["优势1", "优势2"], "disadvantages": ["劣势1", "劣势2"]},
  "risks": ["具体风险1", "具体风险2", "具体风险3"],
  "conclusion": {"rating": "看多|看空|中性", "reason": "一句话理由"}
}
规则：分数填整数。文本用中文。理由基于输入数据，不凭空发挥。只输出JSON。""";

    // ═══════════════════════════════════════════════════════════
    //  公开API
    // ═══════════════════════════════════════════════════════════

    @Transactional
    public AnalysisReport runAnalysis(String stockCode, String paradigm) {
        if (!"DEEP".equalsIgnoreCase(paradigm)) {
            throw new IllegalArgumentException("Unsupported paradigm: " + paradigm + ". Currently only DEEP is supported.");
        }
        return runDeepAnalysis(stockCode);
    }

    public AnalysisReport getLatestReport(String stockCode, String paradigm) {
        List<AnalysisReport> reports = reportRepo.findByStockCodeAndParadigmOrderByCreatedAtDesc(stockCode, paradigm.toUpperCase());
        return reports.isEmpty() ? null : reports.get(0);
    }

    public List<AnalysisReport> getReports(String stockCode) {
        return reportRepo.findByStockCodeOrderByCreatedAtDesc(stockCode);
    }

    // ═══════════════════════════════════════════════════════════
    //  DEEP 分析
    // ═══════════════════════════════════════════════════════════

    private AnalysisReport runDeepAnalysis(String stockCode) {
        StockBasic stock = stockBasicRepo.findByCode(stockCode)
                .orElseThrow(() -> new NoSuchElementException("Stock not found: " + stockCode));

        // 1. 从所有表采集数据，构造分析上下文
        ObjectNode dataContext = buildDataContext(stock);

        // 2. 构造 user prompt
        String userPrompt = String.format(
                "请对 %s（%s）进行个股深度分析。以下是数据库中已采集的结构化数据：\n\n%s",
                stock.getName(), stock.getCode(), dataContext.toPrettyString());

        // 3. 调用 DeepSeek API
        ApiResult apiResult;
        try {
            apiResult = callDeepSeek(DEEP_SYSTEM_PROMPT, userPrompt);
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek API call failed: " + e.getMessage(), e);
        }

        // 4. 解析 JSON
        JsonNode analysisJson;
        try {
            analysisJson = MAPPER.readTree(apiResult.content);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse analysis JSON: " + e.getMessage() +
                    "\nRaw content:\n" + apiResult.content, e);
        }

        // 5. 提取摘要字段
        Integer overallScore = null;
        String conclusion = null;
        String conclusionReason = null;
        Integer riskCount = null;

        try {
            JsonNode fs = analysisJson.get("fundamental_score");
            if (fs != null && fs.has("overall")) {
                overallScore = fs.get("overall").asInt();
            }
            JsonNode cl = analysisJson.get("conclusion");
            if (cl != null) {
                if (cl.has("rating")) conclusion = cl.get("rating").asText();
                if (cl.has("reason")) conclusionReason = cl.get("reason").asText();
            }
            JsonNode risks = analysisJson.get("risks");
            if (risks != null && risks.isArray()) {
                riskCount = risks.size();
            }
        } catch (Exception e) {
            // 摘要提取失败不影响主流程
        }

        // 6. 保存报告
        AnalysisReport report = new AnalysisReport();
        report.setStockCode(stock.getCode());
        report.setStockName(stock.getName());
        report.setParadigm("DEEP");
        report.setContentJson(analysisJson.toPrettyString());
        report.setOverallScore(overallScore);
        report.setConclusion(conclusion);
        report.setConclusionReason(conclusionReason);
        report.setRiskCount(riskCount);
        report.setInputTokens(apiResult.inputTokens);
        report.setOutputTokens(apiResult.outputTokens);
        report.setElapsedMs(apiResult.elapsedMs);
        return reportRepo.save(report);
    }

    // ═══════════════════════════════════════════════════════════
    //  数据采集：从各表汇集结构化数据
    // ═══════════════════════════════════════════════════════════

    private ObjectNode buildDataContext(StockBasic stock) {
        return dataContextBuilder.buildDataContextJson(stock);
    }

    // ═══════════════════════════════════════════════════════════
    //  DeepSeek API 调用
    // ═══════════════════════════════════════════════════════════

    private ApiResult callDeepSeek(String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        String body = MAPPER.createObjectNode()
                .put("model", MODEL)
                .put("temperature", 0.6)
                .put("max_tokens", 8192)
                .set("messages", MAPPER.createArrayNode()
                        .add(MAPPER.createObjectNode()
                                .put("role", "system")
                                .put("content", systemPrompt))
                        .add(MAPPER.createObjectNode()
                                .put("role", "user")
                                .put("content", userPrompt)))
                .toString();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;

        JsonNode root = MAPPER.readTree(response.body());

        if (root.has("error")) {
            String errMsg = root.get("error").has("message")
                    ? root.get("error").get("message").asText()
                    : root.get("error").toString();
            throw new IOException("DeepSeek API error: " + errMsg);
        }

        String content = root.get("choices").get(0).get("message").get("content").asText();

        long inputTokens = 0, outputTokens = 0;
        if (root.has("usage")) {
            JsonNode usage = root.get("usage");
            if (usage.has("prompt_tokens"))     inputTokens  = usage.get("prompt_tokens").asLong();
            if (usage.has("completion_tokens")) outputTokens = usage.get("completion_tokens").asLong();
        }

        return new ApiResult(cleanJson(content), inputTokens, outputTokens, elapsed);
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════

    private static String cleanJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```json")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        return s.trim();
    }

    private static String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private record ApiResult(String content, long inputTokens, long outputTokens, long elapsedMs) {}
}
