package demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import io.agentscope.core.tool.coding.ShellCommandTool;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek Flash 数据采集智能体 v2 — 使用 AgentScope 框架重构。
 *
 * <h3>重构要点</h3>
 * <pre>
 *   - 使用 io.agentscope.harness.agent.HarnessAgent 替代裸 HttpClient
 *   - 使用 io.agentscope.core.model.OpenAIChatModel 封装 DeepSeek API 调用
 *   - 接入 io.agentscope.core.tool.Toolkit + ReadFileTool + WriteFileTool + ShellCommandTool
 *   - 保留双 Agent 架构中的公开数据层定位
 * </pre>
 *
 * <h3>定位</h3>
 * DeepSeek Flash 是通用大模型，不接入同花顺/天眼查。它从训练数据中提供
 * 公开/常识性数据：公司基本信息、行业定位、产业链定性分析、竞品定性对比。
 * 精确财务数字、实时行情、资金流向等仍由 KimiDataAgent 负责。
 *
 * <h3>覆盖的 Group A 数据表</h3>
 * <pre>
 *   stock（基础字段）            — 代码/名称/行业/概念标签
 *   stock_company（描述字段）     — 法人/实控人/经营范围/商业模式
 *   stock_industry_chain（全量）  — 产业链定位/上下游/政策/趋势
 *   stock_product_breakdown（描述）— 产品名/分类/竞争力（近似数字）
 *   stock_competitor（描述字段）  — 竞品定性对比/护城河/稀缺性
 * </pre>
 *
 * <h3>用法</h3>
 * <pre>
 *   mvn exec:java -Dexec.mainClass="demo.DeepSeekDataAgent" -Dexec.args="扬杰科技 300373"
 *   mvn exec:java -Dexec.mainClass="demo.DeepSeekDataAgent" -Dexec.args="富满微 300671"
 * </pre>
 */
public class DeepSeekDataAgent {

    // ────────── DeepSeek API（通过 AgentScope OpenAIChatModel 调用） ──────────
    private static final String API_KEY  = System.getenv("DEEPSEEK_API_KEY");
    private static final String BASE_URL = "https://api.deepseek.com";
    private static final String MODEL    = "deepseek-chat";

    // ────────── JSON ──────────
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ═══════════════════════════════════════════════════════════
    //  SYSTEM PROMPT — 只要求 DeepSeek 能提供的公开数据
    // ═══════════════════════════════════════════════════════════

    /**
     * DeepSeek Flash 的 SYSTEM_PROMPT。
     *
     * 核心原则：
     * 1. 只要求公开可查的定性/描述性数据
     * 2. 财务数字标注"近似值"（来自新闻/研报/公开财报摘要）
     * 3. 不要求实时行情、精确财务、资金流向、筹码分布
     */
    private static final String SYSTEM_PROMPT = """
你是A股个股基本面分析师。你可以利用你的训练数据中的公开知识来分析上市公司。
注意：你无法访问实时行情、精确财务数据库（如同花顺）、工商数据库（如天眼查）。
请基于你的公开知识提供以下分析。

【分析框架】
1. 基础定位：股票代码、名称、交易所、申万行业分类、上市日期、概念板块
2. 公司概况：工商全称、法人、实控人、成立日期、经营范围、官网、商业模式(IDM/Fabless/FabLite)
3. 产业链定位：主营产品、产业链位置(上游/中游/下游/IDM)、上下游描述、主要客户/供应商、
   行业生命周期阶段及判断依据、产业政策影响、行业趋势、市场规模及增速、技术路线
4. 产品线：按"基本盘→增长核心→未来增量"分层，每层写清产品名、营收占比（近似值）、
   毛利率（近似值）、竞争力分析
5. 竞品对比：至少2家同赛道竞品，从商业模式/主营产品/下游/稀缺性/护城河/优劣势维度定性对比。
   竞品精确财务数字（市值/营收/净利/毛利率/ROE）如果能回忆起公开数据就填，无法确定则不填。
6. 风险提示：结构性风险 + 周期性风险
7. 一句话投资要点总结

【输出格式】纯JSON，无代码块标记：

{
  "basic": {"code":"6位代码","name":"公司简称","exchange":"SSE|SZSE","industry_l1":"申万一级","industry_l2":"申万二级","industry_l3":"申万三级","listing_date":"YYYY-MM-DD","concept_tags":"概念板块逗号分隔"},
  "company": {"full_name":"工商全称","legal_representative":"法人","actual_controller":"实控人","established_date":"YYYY-MM-DD","business_scope":"经营范围","website":"官网","business_model":"IDM|FABLESS|FABLITE","business_model_note":"模式说明"},
  "industry_chain": {"core_product":"主营产品/业务","industry_position":"UPSTREAM|MIDSTREAM|DOWNSTREAM|IDM","upstream":"上游原材料与供应商","downstream":"下游应用领域及收入占比","key_customers":"主要客户","key_suppliers":"主要供应商","lifecycle_stage":"INTRODUCTORY|GROWTH|MATURE|DECLINE","lifecycle_note":"判断依据","policy_impact":"产业政策影响","industry_trend":"行业趋势","industry_size":"市场规模(含数字)","industry_growth":"行业增速(含数字)","tech_route":"主要技术路线"},
  "products": [{"product_name":"产品名称","tier":"CASH_COW|GROWTH_ENGINE|FUTURE_ALPHA","revenue_ratio_approx":"营收占比近似值(如约50%)","gross_margin_approx":"毛利率近似值(如约30-35%)","competitiveness":"竞争力分析"}],
  "competitors": [{"name":"竞品公司名","code":"股票代码","business_model":"IDM|FABLESS|FABLITE","main_product":"主营","scarcity":"HIGH|MEDIUM|LOW","scarcity_note":"稀缺性依据","moat":"STRONG|MEDIUM|WEAK","moat_note":"护城河依据","advantage":"竞争优势","disadvantage":"竞争劣势","comparison_summary":"一句话对比总结"}],
  "risk": ["风险1说明","风险2说明"],
  "summary": "一句话投资要点总结（不构成投资建议）"
}

规则：
- 数值字段填纯数字不写单位，百分比填数字不写%号
- 产品按营收占比从高到低排
- 竞品选相近赛道、有可比性的
- 不确定的精确数字不填，宁缺毋滥
- 只输出JSON，不要markdown代码块
- 注意：数据来源是你的训练数据（截至2025年中的公开信息），而非实时数据库""";


    // ═══════════════════════════════════════════════════════════
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String stockName = args[0];
        String stockCode = "";

        // 解析参数: <名称> [代码]
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("-")) {
                stockCode = arg;
            }
        }

        printBanner(stockName, stockCode);

        try {
            // 1. 构建 AgentScope Agent
            //    使用 OpenAIChatModel 对接 DeepSeek API（OpenAI 兼容协议）
            //    使用 HarnessAgent 作为 Agent 实例
            //    接入 Toolkit + ReadFileTool + WriteFileTool + ShellCommandTool

            OpenAIChatModel model = OpenAIChatModel.builder()
                    .apiKey(API_KEY)
                    .modelName(MODEL)
                    .baseUrl(BASE_URL)
                    .build();

            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new ReadFileTool());
            toolkit.registerTool(new WriteFileTool());
            toolkit.registerTool(new ShellCommandTool());

            String sessionId = "ds-" + safeFilename(stockName) + "-" + System.currentTimeMillis();

            HarnessAgent agent = HarnessAgent.builder()
                    .name("DeepSeek数据采集智能体")
                    .sysPrompt(SYSTEM_PROMPT)
                    .model(model)
                    .toolkit(toolkit)
                    .workspace(Paths.get(".agentscope/deepseek-workspace"))
                    .compaction(CompactionConfig.builder()
                            .triggerMessages(10)
                            .keepMessages(5)
                            .build())
                    .build();

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId(sessionId)
                    .userId("deepseek-agent")
                    .build();

            // 2. 构建 UserPrompt 并调用 Agent
            String userPrompt = String.format(
                    "请对 %s 进行基本面分析，按system prompt指定的框架和JSON格式输出。",
                    stockCode.isEmpty() ? stockName : stockName + "（" + stockCode + "）");

            System.out.println(">>> 正在调用 DeepSeek API via AgentScope ...");

            long start = System.currentTimeMillis();
            Msg responseMsg = agent.call(new UserMessage(userPrompt), ctx).block();
            long elapsed = System.currentTimeMillis() - start;
            String rawContent = responseMsg.getTextContent();

            System.out.printf(">>> 耗时: %d ms | AgentScope 已处理 DeepSeek API 调用%n", elapsed);
            System.out.println();

            // 3. 清洗 & 验证 JSON
            String cleanJson = cleanJson(rawContent);
            System.out.println(">>> 返回 JSON 长度: " + cleanJson.length() + " 字符");

            // 4. 保存（ds_ 前缀）
            Path outputDir = Paths.get("output");
            Files.createDirectories(outputDir);
            Path outputFile = outputDir.resolve("ds_" + safeFilename(stockName) + "_analysis.json");
            Files.writeString(outputFile, cleanJson);
            System.out.println(">>> 已保存: " + outputFile.toAbsolutePath());

            // 5. 验证 & 摘要
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = MAPPER.readValue(cleanJson, Map.class);
                System.out.println(">>> JSON 解析成功: " + data.keySet());
                printSummary(data);
            } catch (Exception e) {
                System.err.println("!!! JSON 解析失败: " + e.getMessage());
                System.err.println("原始内容前 500 字符:\n" + rawContent.substring(0, Math.min(500, rawContent.length())));
            }

        } catch (Exception e) {
            System.err.println("!!! 执行失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
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

    private static String safeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
    }

    @SuppressWarnings("unchecked")
    private static void printSummary(Map<String, Object> data) {
        System.out.println();
        System.out.println("══════════ DeepSeek Flash 分析摘要 ══════════");

        Map<String, Object> basic = (Map<String, Object>) data.get("basic");
        if (basic != null) {
            System.out.printf("  公司: %s (%s) | %s / %s / %s%n",
                    basic.get("name"), basic.get("code"),
                    basic.get("industry_l1"), basic.get("industry_l2"), basic.get("industry_l3"));
            System.out.printf("  上市: %s | 交易所: %s%n", basic.get("listing_date"), basic.get("exchange"));
            System.out.printf("  概念: %s%n", basic.get("concept_tags"));
        }

        Map<String, Object> company = (Map<String, Object>) data.get("company");
        if (company != null) {
            System.out.printf("  全称: %s | 法人: %s | 实控人: %s%n",
                    company.get("full_name"), company.get("legal_representative"),
                    company.get("actual_controller"));
            System.out.printf("  模式: %s — %s%n",
                    company.get("business_model"), company.get("business_model_note"));
        }

        Map<String, Object> chain = (Map<String, Object>) data.get("industry_chain");
        if (chain != null) {
            System.out.printf("  主营: %s | 位置: %s | 周期: %s%n",
                    chain.get("core_product"), chain.get("industry_position"),
                    chain.get("lifecycle_stage"));
            System.out.printf("  市场规模: %s | 增速: %s%n",
                    chain.get("industry_size"), chain.get("industry_growth"));
        }

        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        if (products != null) {
            System.out.printf("  产品线: %d 条%n", products.size());
            for (Map<String, Object> p : products) {
                System.out.printf("    [%s] %s 占比%s 毛利率%s%n",
                        p.get("tier"), p.get("product_name"),
                        p.getOrDefault("revenue_ratio_approx", "N/A"),
                        p.getOrDefault("gross_margin_approx", "N/A"));
            }
        }

        List<Map<String, Object>> comps = (List<Map<String, Object>>) data.get("competitors");
        if (comps != null) {
            System.out.printf("  竞品: %d 家%n", comps.size());
            for (Map<String, Object> c : comps) {
                System.out.printf("    - %s (%s) 模式:%s 稀缺:%s 护城河:%s%n",
                        c.get("name"), c.get("code"), c.get("business_model"),
                        c.get("scarcity"), c.get("moat"));
            }
        }

        Object risk = data.get("risk");
        if (risk instanceof List<?> rl && !rl.isEmpty()) {
            System.out.printf("  风险: %d 项%n", rl.size());
            for (Object r : rl) {
                System.out.printf("    ⚠ %s%n", r);
            }
        }

        Object summary = data.get("summary");
        if (summary instanceof String s && !s.isEmpty()) {
            System.out.printf("  >> %s%n", s);
        }

        System.out.println("══════════════════════════════════════════");
    }

    private static void printBanner(String name, String code) {
        System.out.println("┌─────────────────────────────────────┐");
        System.out.println("│  DeepSeek 数据采集智能体 v2         │");
        System.out.println("│          (AgentScope)               │");
        System.out.printf ("│  标的: %-28s │%n", name + (code.isEmpty() ? "" : " (" + code + ")"));
        System.out.println("│  覆盖: 基本信息/产业链/竞品(定性)    │");
        System.out.println("└─────────────────────────────────────┘");
        System.out.println("  框架: HarnessAgent + OpenAIChatModel + Toolkit");
        System.out.println("  工具: ReadFileTool | WriteFileTool | ShellCommandTool");
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("DeepSeekDataAgent v2 (AgentScope) — 双Agent架构中的公开数据层");
        System.out.println();
        System.out.println("用法: DeepSeekDataAgent <名称> [代码]");
        System.out.println();
        System.out.println("覆盖数据:");
        System.out.println("  stock            股票基本信息（代码/行业/概念）");
        System.out.println("  stock_company    公司工商信息（法人/实控人/商业模式）");
        System.out.println("  stock_industry_chain  产业链定位（全量定性分析）");
        System.out.println("  stock_product_breakdown  主营产品构成（描述性+近似数字）");
        System.out.println("  stock_competitor       竞品对比（定性评价+护城河/稀缺性）");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  DeepSeekDataAgent 扬杰科技 300373");
        System.out.println("  DeepSeekDataAgent 富满微 300671");
        System.out.println();
        System.out.println("框架: HarnessAgent + OpenAIChatModel (DeepSeek API) + Toolkit");
        System.out.println("输出: output/ds_<名称>_analysis.json");
    }
}
