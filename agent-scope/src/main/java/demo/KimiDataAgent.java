package demo;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Kimi 数据采集智能体 v5 — 使用 AgentScope 框架重构。
 *
 * <h3>重构要点</h3>
 * <pre>
 *   - 使用 io.agentscope.harness.agent.HarnessAgent 替代裸 HttpClient
 *   - 使用 io.agentscope.core.model.OpenAIChatModel 封装 Kimi API 调用
 *   - 接入 io.agentscope.core.tool.Toolkit + ReadFileTool + WriteFileTool + ShellCommandTool
 *   - 保留 4 套分析范式（deep/sector/tech/chain）
 *   - 保留 Token 用量追踪与限额感知
 * </pre>
 *
 * <h3>4 套分析范式（基于豆包对话模式提炼）</h3>
 * <pre>
 *   deep   — 个股深度分析（全维度覆盖，豆包Q1模式）
 *   sector — 赛道全景扫描（行业分类+受益排名，豆包Q2模式）
 *   tech   — 技术筹码面（走势/量能/筹码/资金，豆包Q3模式）
 *   chain  — 产业链定位（供需/价格传导/利润分配，豆包Q4模式）
 * </pre>
 *
 * <h3>用法</h3>
 * <pre>
 *   mvn exec:java -Dexec.mainClass="demo.KimiDataAgent" -Dexec.args="扬杰科技 300373 --mode deep"
 *   mvn exec:java -Dexec.mainClass="demo.KimiDataAgent" -Dexec.args="功率半导体 --mode sector"
 *   mvn exec:java -Dexec.mainClass="demo.KimiDataAgent" -Dexec.args="富满微 300671 --mode tech"
 *   mvn exec:java -Dexec.mainClass="demo.KimiDataAgent" -Dexec.args="功率半导体 --mode chain"
 * </pre>
 */
public class KimiDataAgent {

    // ────────── kimi API（通过 AgentScope OpenAIChatModel 调用） ──────────
    private static final String API_KEY  = System.getenv("KIMI_API_KEY");
    private static final String BASE_URL = "https://api.kimi.com/coding";
    private static final String MODEL    = "kimi-for-coding";

    // ────────── 后端 API ──────────
    private static final String BACKEND_BASE = "http://localhost:8080/api/v1";

    // ────────── 限额 (Andante) ──────────
    private static final long LIMIT_5H  = 1_000_000L;
    private static final long LIMIT_7D  = 4_000_000L;
    private static final int  WINDOW_5H = 5;

    // ────────── 持久化 ──────────
    private static final Path USAGE_FILE = Paths.get(".kimi_usage.json").toAbsolutePath();

    // ────────── JSON ──────────
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ═══════════════════════════════════════════════════════════
    //  4 套分析范式 — 每套对应豆包对话中的一种分析模式
    // ═══════════════════════════════════════════════════════════

    enum AnalysisMode {
        /**
         * 范式1: 个股深度分析 (豆包Q1模式)
         * 对应问题：「富满微、扬杰科技等功率半导体公司，具体介绍下」
         * 豆包思考：主营业务→产品布局→竞争优势→经营业绩→风险→竞品对比
         * 特点：产品分梯队（基本盘/增长核心/未来增量）、竞品多维对比表、护城河+稀缺性评估
         */
        DEEP("""
你是A股个股深度分析师，可访问同花顺和天眼查。你的分析框架固定如下：

【分析框架】
1. 基础定位：商业模式(是IDM还是Fabless/FabLite)、产业链位置、核心业务一句话定性
2. 产品线分层：按"基本盘(现金牛)→增长核心→未来增量"三层拆解，每层写清产品+市占率+下游
3. 竞争优势：3-5条核心竞争力，是否IDM、是否有稀缺品类(SiC/车规)、客户分散度
4. 经营业绩：最新年度+季度的核心财务数字（营收/利润/毛利率/增速/ROE）
5. 风险提示：模式固有的结构性风险 + 周期性风险
6. 竞品对比：至少2家同赛道竞品，从商业模式/产品覆盖/下游/盈利稳定性/稀缺性/护城河6个维度对比

【输出格式】纯JSON，无代码块标记：

{
  "basic": {"code":"6位代码","name":"公司简称","exchange":"SSE|SZSE","industry_l1":"申万一级","industry_l2":"申万二级","industry_l3":"申万三级","listing_date":"YYYY-MM-DD","total_shares":"总股本","circulating_shares":"流通股本","concept_tags":"概念板块逗号分隔"},
  "company": {"full_name":"工商全称","legal_representative":"法人","actual_controller":"实控人","established_date":"YYYY-MM-DD","registered_capital":"注册资本","business_scope":"经营范围","employee_count":5000,"website":"官网","business_model":"IDM|FABLESS|FABLITE","business_model_note":"模式说明"},
  "industry_chain": {"core_product":"主营产品/业务","industry_position":"UPSTREAM|MIDSTREAM|DOWNSTREAM|IDM","upstream":"上游原材料与供应商","downstream":"下游应用领域及收入占比","key_customers":"主要客户","key_suppliers":"主要供应商","lifecycle_stage":"INTRODUCTORY|GROWTH|MATURE|DECLINE","lifecycle_note":"判断依据","policy_impact":"产业政策影响","industry_trend":"行业趋势","industry_size":"市场规模(含数字)","industry_growth":"行业增速(含数字)","tech_route":"主要技术路线"},
  "products": [{"product_name":"产品名称","tier":"CASH_COW|GROWTH_ENGINE|FUTURE_ALPHA","revenue_ratio":56.5,"gross_margin":30.2,"market_share_note":"市占率描述","competitiveness":"竞争力分析"}],
  "competitors": [{"name":"竞品公司名","code":"股票代码","business_model":"IDM|FABLESS|FABLITE","market_cap":32000000000,"revenue":9900000000,"net_profit":1480000000,"gross_margin":32.5,"roe":7.8,"main_product":"主营","product_overlap":"产品重合度说明","downstream":"主要下游","profit_stability":"STABLE|CYCLICAL","scarcity":"HIGH|MEDIUM|LOW","scarcity_note":"稀缺性依据","moat":"STRONG|MEDIUM|WEAK","moat_note":"护城河依据","advantage":"竞争优势","disadvantage":"竞争劣势","comparison_summary":"一句话对比总结"}],
  "money_flow": {"main_net_inflow":25000000,"main_inflow_ratio":8.5,"super_large_net_inflow":12500000,"large_net_inflow":12500000,"medium_net_inflow":-10000000,"small_net_inflow":-15000000,"note":"近5日资金流向趋势说明"},
  "chip_structure": {"chip_concentration":"HIGHLY_CONCENTRATED|CONCENTRATED|DISPERSED|HIGHLY_DISPERSED","avg_cost":45.30,"profit_ratio":65.2,"chip_dense_low":42.00,"chip_dense_high":48.50,"chip_dense_ratio":72.0,"note":"筹码状态判断(接力换筹/出货/底部建仓等)"},
  "risk": ["风险1说明","风险2说明"],
  "summary": "一句话投资要点总结（不构成投资建议）"
}

规则：数值字段填纯数字不写单位，百分比填数字不写%号。产品按营收占比从高到低排。竞品选相近赛道、有可比性的。数据参考同花顺/天眼查真实值。只输出JSON。"""),

        /**
         * 范式2: 赛道全景扫描 (豆包Q2模式)
         * 对应问题：「功率半导体涨价谁最受益？」
         * 豆包思考：先确认趋势→按商业模式分类→按受益弹性排序→多维对比
         * 特点：不做单只股票深挖，而是画赛道全景图；分类+排序是核心
         */
        SECTOR("""
你是A股赛道分析师，可访问同花顺。你的分析框架固定如下：

【分析框架】
1. 趋势确认：先确认赛道当前核心趋势(涨价/复苏/衰退/技术迭代)，给出时间、幅度、驱动因素
2. 公司分类：按商业模式分档(IDM全产业链/高端纯标的/Fabless设计/上游材料)，每档列代表公司
3. 受益排序：按该趋势下"谁受益弹性最大"分4个梯队排序，每个梯队说明排序逻辑
4. 关键对比：从自有产能比例/产品结构/下游需求刚性/定价权/库存水平5个维度对比代表公司

【输出格式】纯JSON，无代码块标记：

{
  "sector_name": "赛道名称",
  "trend_confirmation": {"trend_type":"PRICE_HIKE|RECOVERY|DECLINE|TECH_ITERATION","start_time":"YYYY-MM","round":"第几轮","magnitude":"涨幅描述(如10%-15%)","participants":"参与厂商数量与代表","drivers":["驱动因素1","驱动因素2"],"sustainability":"持续性判断(如延续到2027年)","source":"数据来源说明"},
  "company_groups": [{"group_name":"IDM全产业链龙头|高端纯标的|Fabless设计公司|上游材料衬底","group_logic":"为什么这组受益弹性是这个位置","representatives":[{"name":"公司名","code":"代码","market_cap":50000000000,"key_metric":"关键指标描述(如自有晶圆产能6/8/12寸)","highlights":["亮点1","亮点2"],"catalyst":"当前催化事件"}]}],
  "benefit_ranking": [{"tier":1,"tier_label":"第一梯队：确定性最高","logic":"为什么这个梯队最受益","representatives":["代码1","代码2"]},{"tier":2,"tier_label":"第二梯队","logic":"...","representatives":["代码1"]}],
  "key_comparison": {"dimensions":["自有产能比例","产品高端化程度","下游需求刚性","定价权","库存水位"],"companies":[{"name":"公司名","code":"代码","自有产能比例":"高/中/低(说明)","产品高端化程度":"高/中/低(说明)","下游需求刚性":"强/中/弱(说明)","定价权":"强/中/弱(说明)","库存水位":"低/中/高(说明)"}]},
  "summary": "赛道一句话总结"
}

规则：数值字段填纯数字不写单位。公司至少覆盖8-12家。排序逻辑必须写清楚，不要泛泛而谈。数据参考同花顺真实值。只输出JSON。"""),

        /**
         * 范式3: 技术筹码面 (豆包Q3模式)
         * 对应问题：「富满微回踩5日线，根据量能和板块走势预判机会」
         * 豆包思考：走势定位→回踩性质判断→量能分析→筹码结构→主力资金→板块梯队
         * 特点：先出结论再拆解；判断"健康回踩"vs"破位出货"是核心；区分短线/中线
         */
        TECH("""
你是A股技术面分析师，聚焦走势/量能/筹码/资金四个维度。你的分析框架固定如下：

【分析框架】
1. 先给结论：一句话定性当前走势+后续方向判断
2. 走势定位：当前处于什么阶段(底部反弹/主升浪/高位震荡/回调/破位)，阶段涨幅、启动时间、股性(弹性小票/趋势中军/慢牛)
3. 关键位分析：5/10/20日线位置、当前价与均线关系、是否回踩到位
4. 量能判断：当日成交额 vs 近期均值，判断缩量/放量/平量，是正常调整还是放量出逃
5. 筹码结构：底部筹码是否松动、高位是否重新堆积、换手率是否充分、筹码状态(接力换筹/出货/建仓)
6. 资金流向：近5日主力净流入/流出数据、大单占比、资金性质判断(游资/机构)
7. 板块梯队：龙头弹性票+中军趋势票+补涨票三个梯队分别列出，判断板块处于什么阶段(启动/普涨/分化/退潮)
8. 机会判断：区分短线(5日线低吸/追高)和中线(20日线布局)，给出止损参考位

【输出格式】纯JSON，无代码块标记：

{
  "stock_technical": {"name":"股票名","code":"代码","conclusion":"一句话结论","stage":"BOTTOM_REBOUND|MAIN_UPWAVE|HIGH_CONSOLIDATION|PULLBACK|BREAKDOWN","stage_note":"阶段说明","start_price":40.00,"current_price":97.15,"total_gain_pct":139.0,"personality":"ELASTIC_LEADER|TREND_CORE|SLOW_BULL|LAGGARD"},
  "moving_averages": {"ma5":95.60,"ma10":88.00,"ma20":75.00,"price_vs_ma5":"ABOVE|AT|BELOW","price_vs_ma20":"ABOVE|AT|BELOW","pullback_quality":"HEALTHY|WARNING|BREAKDOWN","pullback_note":"回踩性质说明"},
  "volume_analysis": {"today_turnover":2480000000,"recent_avg_turnover":3300000000,"volume_trend":"SHRINKING|EXPANDING|FLAT","volume_note":"量能判断说明","cumulative_turnover_7d":105.0},
  "chip_structure": {"chip_concentration":"HIGHLY_CONCENTRATED|CONCENTRATED|DISPERSED|HIGHLY_DISPERSED","avg_cost":70.00,"profit_ratio":65.2,"bottom_chip_status":"HOLDING|SELLING|GONE","bottom_chip_range":"40-70","new_chip_range":"85-100","chip_status":"RELAY_EXCHANGE|TOP_DISTRIBUTION|BOTTOM_ACCUMULATION","chip_status_note":"筹码状态判断依据"},
  "money_flow": {"main_net_inflow_5d":250000000,"main_net_inflow_today":-126000000,"super_large_net_inflow_today":50000000,"money_nature":"RETAIL_DRIVEN|INSTITUTION_DRIVEN|MIXED","money_note":"资金性质判断"},
  "sector_tiers": {"elastic_leaders":[{"name":"龙头名","code":"代码","note":"定位说明"}],"trend_cores":[{"name":"中军名","code":"代码","note":"定位说明"}],"laggards":[{"name":"补涨票","code":"代码","note":"定位说明"}],"sector_stage":"INITIATING|UNIVERSAL_RISE|DIFFERENTIATING|RETREATING","sector_note":"板块阶段判断"},
  "opportunity": {"short_term_play":"短线策略说明","short_term_trigger":"入场条件","mid_term_play":"中线策略说明","mid_term_entry_zone":"中线建仓区间","stop_loss_reference":"止损参考位","risk_warning":["风险1","风险2"]}
}

规则：数值字段填纯数字不写单位，百分比填数字不写%号。资金和筹码判断要有数据支撑不要臆测。均线价格用小数点后2位。日期用YYYY-MM-DD。数据参考同花顺真实值。不构成投资建议。只输出JSON。"""),

        /**
         * 范式4: 产业链定位 (豆包Q4模式)
         * 对应问题：「AI革命涨价中，功率半导体是中端还是末端？硅片呢？」
         * 豆包思考：梳理半导体全产业链层级→全球涨价时间顺序→利润分配→对比传统工业革命
         * 特点：要回答"你在产业链哪一段"；和工业革命对比是核心洞察
         */
        CHAIN("""
你是半导体产业链分析师，聚焦产业链定位与利润分配。你的分析框架固定如下：

【分析框架】
1. 产业链全景：从最上游(硅片/材料)到最下游(服务器/汽车/手机)画出完整链条，标注每个环节的代表公司和毛利率区间
2. 价格传导顺序：从离需求最近的环节往上游，标注全球涨价的时间顺序和幅度
3. 产业定位：目标公司/赛道位于链条的哪一环，距离终端需求有多远
4. 利润分配：本轮周期中利润最厚的环节是哪些、被挤压的是哪些，和传统工业革命模式的本质区别
5. 范式对比：需求拉动型涨价 vs 供给推动型涨价，两种范式下利润分配天差地别

【输出格式】纯JSON，无代码块标记：

{
  "topic": "分析主题",
  "paradigm_type": "需求拉动型涨价(本轮AI半导体) vs 资源供给型涨价(传统工业革命)",
  "supply_chain": {"layers":[{"level":1,"name":"硅片/衬底材料","representatives":["代表性公司"],"gross_margin_range":"毛利率区间","price_hike_timing":"涨价时间","price_hike_magnitude":"涨幅","profit_elasticity":"LOW|MEDIUM|HIGH","note":"分析说明"},{"level":2,"name":"晶圆代工","representatives":[],"...":"..."},{"level":3,"name":"芯片设计/IDM产品","representatives":[],"note":"功率半导体所处层级"},{"level":4,"name":"封装测试","representatives":[]},{"level":5,"name":"终端整机/应用","representatives":[]}],"total_layers":5},
  "pricing_sequence": [{"wave":1,"wave_name":"第一波","category":"存储芯片(DRAM/NAND/HBM)","timing":"2025Q4-2026Q1","magnitude":"涨幅描述","drivers":["驱动因素"]},{"wave":2,"wave_name":"第二波","category":"模拟芯片+功率半导体","timing":"2026年4月第一轮、7月第二轮","magnitude":"功率涨10%-25%","note":"说明定位"},{"wave":3,"wave_name":"第三波","category":"晶圆代工","timing":"...","magnitude":"..."},{"wave":4,"wave_name":"第四波","category":"硅片/材料","timing":"...","magnitude":"..."}],
  "target_position": {"target":"目标公司/赛道名称","chain_level":2,"chain_role":"说明在链条中的角色","distance_to_end_demand":"CLOSE|MEDIUM|FAR","position_analysis":"详细定位分析"},
  "profit_distribution": {"tier1":{"label":"第一梯队(利润最厚)","who":"哪些环节/公司","logic":"为什么"},"tier2":{"label":"第二梯队","who":"...","logic":"..."},"tier3":{"label":"第三梯队(弹性最小)","who":"...","logic":"..."},"squeezed":"被挤压的环节(最下游整机厂)","key_insight":"核心洞察：稀缺的不是上游材料，而是特定品类的芯片产能"},
  "paradigm_comparison": {"industrial_revolution":"传统工业革命：上游资源稀缺→上游定价权拉满→中下游被挤压","ai_semiconductor":"本轮AI半导体：下游需求爆发→特定芯片产能稀缺→缺口最大的环节吃饱→上游材料只是止跌回升","essential_difference":"本质差异一句话"},
  "summary": "产业链定位一句话总结"
}

规则：数值字段填纯数字不写单位，百分比填数字不写%号。供应链层级不少于5层。价格传导时间线要精确到季度。利润分配逻辑要写清楚为什么。数据参考同花顺真实值。只输出JSON。""");

        final String systemPrompt;

        AnalysisMode(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }
    }

    // ────────── 全局状态 ──────────
    private static UsageTracker tracker;
    private static AnalysisMode mode = AnalysisMode.DEEP;

    // ═══════════════════════════════════════════════════════════
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String stockName = args[0];
        String stockCode = "";

        // 解析参数: <名称> [代码] [--mode <mode>]
        int argIdx = 1;
        while (argIdx < args.length) {
            String arg = args[argIdx];
            if (arg.startsWith("--mode=")) {
                parseMode(arg.substring(7));
            } else if (arg.equals("--mode") || arg.equals("-m")) {
                if (argIdx + 1 < args.length) {
                    parseMode(args[argIdx + 1]);
                    argIdx++;
                }
            } else if (!arg.startsWith("-")) {
                stockCode = arg;
            }
            argIdx++;
        }

        printBanner(stockName, stockCode);

        try {
            // 1. 加载用量统计 & 检查限额
            tracker = UsageTracker.load();
            tracker.printStatus();

            if (!tracker.canCall(LIMIT_5H, WINDOW_5H, ChronoUnit.HOURS)) {
                System.err.println("!!! 5小时限额已达上限，请等待窗口重置。");
                System.exit(3);
            }
            if (!tracker.canCall(LIMIT_7D, 7 * 24, ChronoUnit.HOURS)) {
                System.err.println("!!! 7天限额已达上限，请等待窗口重置。");
                System.exit(3);
            }

            // 2. 构建 AgentScope Agent
            //    使用 OpenAIChatModel 对接 Kimi API（OpenAI 兼容协议）
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

            String sessionId = "kimi-" + safeFilename(stockName) + "-" + System.currentTimeMillis();

            HarnessAgent agent = HarnessAgent.builder()
                    .name("Kimi数据采集智能体")
                    .sysPrompt(mode.systemPrompt)
                    .model(model)
                    .toolkit(toolkit)
                    .workspace(Paths.get(".agentscope/kimi-workspace"))
                    .compaction(CompactionConfig.builder()
                            .triggerMessages(10)
                            .keepMessages(5)
                            .build())
                    .build();

            RuntimeContext ctx = RuntimeContext.builder()
                    .sessionId(sessionId)
                    .userId("kimi-agent")
                    .build();

            // 3. 构建 UserPrompt 并调用 Agent
            String userPrompt = buildUserPrompt(stockName, stockCode);
            System.out.println(">>> 正在调用 Kimi API via AgentScope (模式: " + mode.name() + ") ...");

            long start = System.currentTimeMillis();
            Msg responseMsg = agent.call(new UserMessage(userPrompt), ctx).block();
            long elapsed = System.currentTimeMillis() - start;
            String rawContent = responseMsg.getTextContent();

            System.out.printf(">>> 耗时: %d ms | AgentScope 已处理 Kimi API 调用%n", elapsed);
            System.out.println();

            // 4. 记录用量（AgentScope 内部管理 token，此处按字符估算用于限额追踪）
            int estimatedTokens = estimateTokens(rawContent + mode.systemPrompt + userPrompt);
            tracker.recordCall(estimatedTokens);
            tracker.save();

            // 5. 清洗 & 验证 JSON
            String cleanJson = cleanJson(rawContent);
            System.out.println(">>> 返回 JSON 长度: " + cleanJson.length() + " 字符");

            // 6. 保存（使用 WriteFileTool 风格，但直接写文件以确保兼容性）
            Path outputDir = Paths.get("output");
            Files.createDirectories(outputDir);
            String prefix = mode == AnalysisMode.DEEP ? "" : mode.name().toLowerCase() + "_";
            Path outputFile = outputDir.resolve(prefix + safeFilename(stockName) + "_analysis.json");
            Files.writeString(outputFile, cleanJson);
            System.out.println(">>> 已保存: " + outputFile.toAbsolutePath());

            // 7. 验证 & 摘要
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = MAPPER.readValue(cleanJson, Map.class);
                System.out.println(">>> JSON 解析成功: " + data.keySet());
                printSummary(data);
            } catch (Exception e) {
                System.err.println("!!! JSON 解析失败: " + e.getMessage());
                System.err.println("原始内容前 500 字符:\n" + rawContent.substring(0, Math.min(500, rawContent.length())));
            }

            // 8. 入库
            tryPostToBackend(cleanJson, stockCode);

            // 9. 打印用量报告
            tracker.printStatus();

        } catch (Exception e) {
            System.err.println("!!! 执行失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void parseMode(String modeStr) {
        try {
            mode = AnalysisMode.valueOf(modeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("!!! 未知模式: " + modeStr + "，有效值: deep, sector, tech, chain。使用默认 deep。");
            mode = AnalysisMode.DEEP;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  User Prompt 构建（通过 AgentScope UserMessage 传入）
    // ═══════════════════════════════════════════════════════════
    private static String buildUserPrompt(String stockName, String stockCode) {
        String target = stockCode.isEmpty() ? stockName : stockName + "（" + stockCode + "）";
        return switch (mode) {
            case DEEP   -> String.format("请对 %s 进行个股深度分析，按system prompt指定的框架和JSON格式输出。", target);
            case SECTOR -> String.format("请对 %s 赛道进行全景扫描分析，按system prompt指定的框架和JSON格式输出。", target);
            case TECH   -> String.format("请对 %s 进行技术筹码面分析，按system prompt指定的框架和JSON格式输出。", target);
            case CHAIN  -> String.format("请对 %s 在产业链中的位置进行分析，按system prompt指定的框架和JSON格式输出。", target);
        };
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

    /** Token 估算（用于限额追踪，AgentScope 内部已管理实际 token 消耗） */
    private static int estimateTokens(String text) {
        // 粗略估算：中文约 1.5 字符/token，英文约 4 字符/token
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (int) (chineseChars / 1.5 + otherChars / 4.0);
    }

    @SuppressWarnings("unchecked")
    private static void printSummary(Map<String, Object> data) {
        System.out.println();
        System.out.println("══════════ 分析摘要 [" + mode.name() + "] ══════════");

        switch (mode) {
            case DEEP -> printDeepSummary(data);
            case SECTOR -> printSectorSummary(data);
            case TECH -> printTechSummary(data);
            case CHAIN -> printChainSummary(data);
        }
        System.out.println("══════════════════════════════════════════");
    }

    private static void printDeepSummary(Map<String, Object> data) {
        Map<String, Object> basic = get(data, "basic");
        if (basic != null) {
            System.out.printf("  公司: %s (%s) | %s / %s%n",
                    basic.get("name"), basic.get("code"), basic.get("industry_l1"), basic.get("industry_l2"));
        }
        Map<String, Object> chain = get(data, "industry_chain");
        if (chain != null) {
            System.out.printf("  主营: %s | 位置: %s | 周期: %s%n",
                    chain.get("core_product"), chain.get("industry_position"), chain.get("lifecycle_stage"));
        }
        List<Map<String, Object>> products = (List<Map<String, Object>>) data.get("products");
        if (products != null) {
            System.out.printf("  产品线: %d 条%n", products.size());
            for (Map<String, Object> p : products) {
                System.out.printf("    [%s] %s 占比%.1f%% 毛利率%.1f%%%n",
                        p.get("tier"), p.get("product_name"), p.get("revenue_ratio"), p.get("gross_margin"));
            }
        }
        List<Map<String, Object>> comps = (List<Map<String, Object>>) data.get("competitors");
        if (comps != null) {
            System.out.printf("  竞品: %d 家%n", comps.size());
            for (Map<String, Object> c : comps) {
                System.out.printf("    - %s (%s) 模式:%s 稀缺:%s 护城河:%s%n",
                        c.get("name"), c.get("code"), c.get("business_model"), c.get("scarcity"), c.get("moat"));
            }
        }
        Map<String, Object> mf = get(data, "money_flow");
        if (mf != null) {
            System.out.printf("  资金: 主力净流入 %s (占比%s%%)%n", mf.get("main_net_inflow"), mf.get("main_inflow_ratio"));
        }
        Map<String, Object> cs = get(data, "chip_structure");
        if (cs != null) {
            System.out.printf("  筹码: %s | 获利盘%s%% | 成本%s | 密集区[%s, %s]%n",
                    cs.get("chip_concentration"), cs.get("profit_ratio"), cs.get("avg_cost"),
                    cs.get("chip_dense_low"), cs.get("chip_dense_high"));
        }
        Object risk = data.get("risk");
        if (risk instanceof List<?> rl && !rl.isEmpty()) {
            System.out.printf("  风险: %d 项 (首项: %s)%n", rl.size(), rl.get(0));
        }
        Object summary = data.get("summary");
        if (summary instanceof String s && !s.isEmpty()) {
            System.out.printf("  >> %s%n", s);
        }
    }

    private static void printSectorSummary(Map<String, Object> data) {
        System.out.printf("  赛道: %s%n", data.getOrDefault("sector_name", "N/A"));
        Map<String, Object> trend = get(data, "trend_confirmation");
        if (trend != null) {
            System.out.printf("  趋势: %s | %s | %s%n",
                    trend.get("trend_type"), trend.get("magnitude"), trend.get("sustainability"));
        }
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("company_groups");
        if (groups != null) {
            for (Map<String, Object> g : groups) {
                List<Map<String, Object>> reps = (List<Map<String, Object>>) g.get("representatives");
                System.out.printf("  %s: %d家公司%n", g.get("group_name"), reps != null ? reps.size() : 0);
            }
        }
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) data.get("benefit_ranking");
        if (ranking != null) {
            System.out.println("  受益排序:");
            for (Map<String, Object> r : ranking) {
                System.out.printf("    T%d %s: %s%n", r.get("tier"), r.get("tier_label"), r.get("representatives"));
            }
        }
    }

    private static void printTechSummary(Map<String, Object> data) {
        Map<String, Object> tech = get(data, "stock_technical");
        if (tech != null) {
            System.out.printf("  >> %s%n", tech.get("conclusion"));
            System.out.printf("  走势: %s | 阶段: %s | 涨幅: %s%%%n",
                    tech.get("personality"), tech.get("stage"), tech.get("total_gain_pct"));
        }
        Map<String, Object> vol = get(data, "volume_analysis");
        if (vol != null) {
            System.out.printf("  量能: %s | %s%n", vol.get("volume_trend"), vol.get("volume_note"));
        }
        Map<String, Object> chip = get(data, "chip_structure");
        if (chip != null) {
            System.out.printf("  筹码: %s | %s%n", chip.get("chip_status"), chip.get("chip_status_note"));
        }
        Map<String, Object> mf = get(data, "money_flow");
        if (mf != null) {
            System.out.printf("  资金: %s | 主力5日净流入 %s%n", mf.get("money_nature"), mf.get("main_net_inflow_5d"));
        }
        Map<String, Object> opp = get(data, "opportunity");
        if (opp != null) {
            System.out.printf("  短线: %s%n  中线: %s%n", opp.get("short_term_play"), opp.get("mid_term_play"));
        }
    }

    private static void printChainSummary(Map<String, Object> data) {
        Map<String, Object> pos = get(data, "target_position");
        if (pos != null) {
            System.out.printf("  定位: %s → 第%s层 (%s)%n",
                    pos.get("target"), pos.get("chain_level"), pos.get("chain_role"));
        }
        List<Map<String, Object>> layers = null;
        Map<String, Object> chain = get(data, "supply_chain");
        if (chain != null) {
            layers = (List<Map<String, Object>>) chain.get("layers");
        }
        if (layers != null) {
            System.out.println("  产业链层级:");
            for (Map<String, Object> l : layers) {
                System.out.printf("    L%d %s: %s (毛利率%s 涨价%s)%n",
                        l.get("level"), l.get("name"), l.get("representatives"),
                        l.get("gross_margin_range"), l.get("price_hike_magnitude"));
            }
        }
        Map<String, Object> dist = get(data, "profit_distribution");
        if (dist != null) {
            Map<String, Object> t1 = get(dist, "tier1");
            if (t1 != null) System.out.printf("  利润T1: %s → %s%n", t1.get("who"), t1.get("logic"));
        }
        Object pc = data.get("paradigm_comparison");
        if (pc instanceof Map<?,?> pcMap) {
            Object diff = pcMap.get("essential_difference");
            if (diff instanceof String s) System.out.printf("  >> %s%n", s);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> get(Map<String, Object> data, String key) {
        return (Map<String, Object>) data.get(key);
    }

    // ═══════════════════════════════════════════════════════════
    //  后端入库（内部 REST 调用，非 LLM 调用，使用标准 HttpClient）
    // ═══════════════════════════════════════════════════════════
    private static void tryPostToBackend(String json, String code) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String endpoint = switch (mode) {
                case DEEP, TECH -> "/stocks/" + code + "/kimi-data";
                case SECTOR -> "/sectors/kimi-data";
                case CHAIN  -> "/chain/kimi-data";
            };
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BACKEND_BASE + endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println(">>> 后端入库: HTTP " + resp.statusCode());
        } catch (Exception e) {
            System.out.println(">>> 后端未运行，JSON 已存本地。启动后端后手动导入。");
        }
    }

    private static void printBanner(String name, String code) {
        String label = switch (mode) {
            case DEEP   -> "Deep  个股深度分析";
            case SECTOR -> "Sector 赛道全景扫描";
            case TECH   -> "Tech  技术筹码面";
            case CHAIN  -> "Chain 产业链定位";
        };
        System.out.println("┌─────────────────────────────────────┐");
        System.out.println("│  Kimi 数据采集智能体 v5 (AgentScope) │");
        System.out.printf ("│  范式: %-29s │%n", label);
        System.out.printf ("│  标的: %-28s │%n", name + (code.isEmpty() ? "" : " (" + code + ")"));
        System.out.println("└─────────────────────────────────────┘");
        System.out.println("  框架: HarnessAgent + OpenAIChatModel + Toolkit");
        System.out.println("  工具: ReadFileTool | WriteFileTool | ShellCommandTool");
        System.out.println();
    }

    private static void printUsage() {
        System.out.println("KimiDataAgent v5 (AgentScope) — 4套分析范式");
        System.out.println();
        System.out.println("用法: KimiDataAgent <名称> [代码] [--mode <范式>]");
        System.out.println();
        System.out.println("范式说明:");
        System.out.println("  deep   个股深度分析 (默认)  产品分层/竞品对比/护城河/风险");
        System.out.println("  sector 赛道全景扫描         趋势确认/公司分类/受益排序");
        System.out.println("  tech   技术筹码面           走势/量能/筹码/资金/机会判断");
        System.out.println("  chain  产业链定位           全链条/价格传导/利润分配");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  KimiDataAgent 扬杰科技 300373 --mode deep");
        System.out.println("  KimiDataAgent 功率半导体 --mode sector");
        System.out.println("  KimiDataAgent 富满微 300671 --mode tech");
        System.out.println("  KimiDataAgent 功率半导体 --mode chain");
        System.out.println();
        System.out.println("框架: HarnessAgent + OpenAIChatModel (Kimi API) + Toolkit");
        System.out.println("工具: ReadFileTool | WriteFileTool | ShellCommandTool");
    }

    // ═══════════════════════════════════════════════════════════
    //  Token 用量追踪器（AgentScope 内部管理 API 调用，此处追踪限额）
    // ═══════════════════════════════════════════════════════════
    static class UsageTracker {
        public String subscriptionDay;
        public long totalTokensUsed;
        public List<CallRecord> recentCalls;

        static UsageTracker load() {
            try {
                if (Files.exists(USAGE_FILE)) {
                    return MAPPER.readValue(USAGE_FILE.toFile(), UsageTracker.class);
                }
            } catch (IOException ignored) {}
            UsageTracker t = new UsageTracker();
            t.subscriptionDay = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.now());
            t.totalTokensUsed = 0;
            t.recentCalls = new java.util.ArrayList<>();
            return t;
        }

        void save() {
            try {
                Path parent = USAGE_FILE.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                MAPPER.writeValue(USAGE_FILE.toFile(), this);
            } catch (IOException e) {
                System.err.println("!!! 保存用量文件失败: " + e.getMessage());
            }
        }

        void recordCall(long tokens) {
            totalTokensUsed += tokens;
            recentCalls.add(new CallRecord(Instant.now().toEpochMilli(), tokens));
            if (recentCalls.size() > 200) {
                recentCalls = recentCalls.subList(recentCalls.size() - 200, recentCalls.size());
            }
        }

        boolean canCall(long limit, int windowSize, ChronoUnit unit) {
            Instant cutoff = Instant.now().minus(windowSize, unit);
            long used = recentCalls.stream()
                    .filter(r -> r.time().isAfter(cutoff))
                    .mapToLong(r -> r.tokens)
                    .sum();
            return used < limit;
        }

        long usedInWindow(int windowSize, ChronoUnit unit) {
            Instant cutoff = Instant.now().minus(windowSize, unit);
            return recentCalls.stream()
                    .filter(r -> r.time().isAfter(cutoff))
                    .mapToLong(r -> r.tokens)
                    .sum();
        }

        void printStatus() {
            long used5h = usedInWindow(WINDOW_5H, ChronoUnit.HOURS);
            long used7d = usedInWindow(7 * 24, ChronoUnit.HOURS);
            System.out.println("─── 用量统计 ─────────────────────");
            System.out.printf("  5小时窗口: %s / %s tokens (%.1f%%)%n",
                    fmt(used5h), fmt(LIMIT_5H), used5h * 100.0 / LIMIT_5H);
            System.out.printf("  7天窗口:   %s / %s tokens (%.1f%%)%n",
                    fmt(used7d), fmt(LIMIT_7D), used7d * 100.0 / LIMIT_7D);
            System.out.printf("  累计总量:  %s tokens | 调用 %d 次%n",
                    fmt(totalTokensUsed), recentCalls.size());
            System.out.println("──────────────────────────────────");
        }

        private static String fmt(long n) {
            if (n >= 1_000_000) return String.format("%.2fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }

    record CallRecord(long epochMillis, long tokens) {
        Instant time() { return Instant.ofEpochMilli(epochMillis); }
    }
}
