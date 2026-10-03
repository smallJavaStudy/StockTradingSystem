package com.stock.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.workflow.engine.WorkflowDeployService;
import com.stock.workflow.entity.AgentDef;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.AgentDefRepository;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 工作流种子数据初始化与版本化升级（幂等、绝不清库、绝不删除用户数据）。
 * <p>
 * <b>种子升级机制</b>（seedVersion 字段，null=用户创建的行）：
 * <ul>
 *   <li>不存在 → 创建并记 seedVersion=目标版本；</li>
 *   <li>存在且 seedVersion 非空且 &lt; 目标版本 → 原地更新（保留 id 与运行历史）并升 seedVersion；</li>
 *   <li>存在但 seedVersion 为 null 且 name 与种子名相同 → 视为种子历史行，首轮升级原地更新并打上 seedVersion；</li>
 *   <li>版本已到位 → 跳过。</li>
 * </ul>
 * 工作流升级后若原状态为 PUBLISHED，自动调用 {@link WorkflowDeployService#deploy(Long)} 重新部署，
 * 部署失败仅记 error，不阻断启动。
 * <p>
 * <b>种子内容</b>：
 * <ul>
 *   <li>开发域 8 个 AgentDef（6 个 v2 深度化 + 代码评审员/发布验收员 v1）；</li>
 *   <li>股票分析域 10 个 AgentDef（基本面/技术面/综合分析 v2 深度化 + 7 个新分析师 v1，
 *       其中行业研究员/舆情分析师为 AGENTSCOPE 并配置 stock_industry / data_enrich 工具）；</li>
 *   <li>博客创作域 5 个 AgentDef（v1）；</li>
 *   <li>功能开发协作域 10 个 AgentDef（v1，其中数据源调研员/技术资产盘点师为 AGENTSCOPE 并配置 memory 工具）；</li>
 *   <li>7 个 WorkflowDef：开发工作流 v2（9 节点串行）、股票分析工作流 v2（11 节点 9 维并行汇聚）、
 *       博客文章创作工作流 v1（7 节点串行）、每日复盘 v1、题材挖掘 v1、组合诊断 v1、
 *       功能开发协作工作流 v1（14 节点，3 处并行汇聚）。已有 AI 生成的旧博客类工作流不删除不修改。</li>
 * </ul>
 */
@Component
public class WorkflowSeedService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowSeedService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 种子目标版本常量 ──
    static final int DEV_WORKFLOW_VERSION = 2;
    static final int STOCK_WORKFLOW_VERSION = 2;
    static final int BLOG_WORKFLOW_VERSION = 1;
    static final int REVIEW_WORKFLOW_VERSION = 1;
    static final int TOPIC_WORKFLOW_VERSION = 1;
    static final int PORTFOLIO_WORKFLOW_VERSION = 1;
    static final int FEATURE_DEV_WORKFLOW_VERSION = 1;
    /** 首批 9 个智能体本轮 systemPrompt 深度化 → v2 */
    static final int AGENT_V2 = 2;
    /** 本轮新增智能体 → v1 */
    static final int AGENT_V1 = 1;

    static final String DEV_WORKFLOW_NAME = "开发工作流";
    static final String STOCK_WORKFLOW_NAME = "股票分析工作流";
    static final String BLOG_WORKFLOW_NAME = "博客文章创作工作流";
    static final String REVIEW_WORKFLOW_NAME = "每日复盘工作流";
    static final String TOPIC_WORKFLOW_NAME = "题材挖掘工作流";
    static final String PORTFOLIO_WORKFLOW_NAME = "组合诊断工作流";
    static final String FEATURE_DEV_WORKFLOW_NAME = "功能开发协作工作流";

    private final AgentDefRepository agentDefRepo;
    private final WorkflowDefRepository workflowDefRepo;
    private final WorkflowDeployService deployService;

    public WorkflowSeedService(AgentDefRepository agentDefRepo,
                               WorkflowDefRepository workflowDefRepo,
                               WorkflowDeployService deployService) {
        this.agentDefRepo = agentDefRepo;
        this.workflowDefRepo = workflowDefRepo;
        this.deployService = deployService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seedAgentsAndWorkflows();
        } catch (Exception e) {
            // 种子失败不阻断应用启动
            log.error("工作流种子数据初始化失败（不影响应用启动）: {}", e.getMessage(), e);
        }
    }

    void seedAgentsAndWorkflows() throws Exception {
        // ── 1. 开发域智能体 ──
        AgentDef reqAnalyst = seedPromptAgent("需求分析师", AGENT_V2, SeedAgentPrompts.REQ_ANALYST);
        AgentDef reqReviewer = seedPromptAgent("需求评审员", AGENT_V2, SeedAgentPrompts.REQ_REVIEWER);
        AgentDef sysDesigner = seedPromptAgent("系统设计师", AGENT_V2, SeedAgentPrompts.SYS_DESIGNER);
        AgentDef designReviewer = seedPromptAgent("设计评审员", AGENT_V2, SeedAgentPrompts.DESIGN_REVIEWER);
        AgentDef developer = seedPromptAgent("开发工程师", AGENT_V2, SeedAgentPrompts.DEVELOPER);
        AgentDef tester = seedPromptAgent("测试工程师", AGENT_V2, SeedAgentPrompts.TESTER);
        AgentDef codeReviewer = seedPromptAgent("代码评审员", AGENT_V1, SeedAgentPrompts.CODE_REVIEWER);
        AgentDef releaseVerifier = seedPromptAgent("发布验收员", AGENT_V1, SeedAgentPrompts.RELEASE_VERIFIER);

        // ── 2. 股票分析域智能体 ──
        AgentDef dataOverviewAnalyst = seedPromptAgent("数据总览分析师", AGENT_V1, SeedAgentPrompts.DATA_OVERVIEW_ANALYST);
        AgentDef fundamentalAnalyst = seedPromptAgent("股票基本面分析师", AGENT_V2, SeedAgentPrompts.FUNDAMENTAL_ANALYST);
        AgentDef technicalAnalyst = seedPromptAgent("股票技术面分析师", AGENT_V2, SeedAgentPrompts.TECHNICAL_ANALYST);
        AgentDef trendAnalyst = seedPromptAgent("趋势分析师", AGENT_V1, SeedAgentPrompts.TREND_ANALYST);
        AgentDef valuationAnalyst = seedPromptAgent("估值分析师", AGENT_V1, SeedAgentPrompts.VALUATION_ANALYST);
        AgentDef capitalAnalyst = seedPromptAgent("资金面分析师", AGENT_V1, SeedAgentPrompts.CAPITAL_ANALYST);
        AgentDef chipAnalyst = seedPromptAgent("筹码分析师", AGENT_V1, SeedAgentPrompts.CHIP_ANALYST);
        AgentDef riskOfficer = seedPromptAgent("风险官", AGENT_V1, SeedAgentPrompts.RISK_OFFICER);
        AgentDef sectorResearcher = seedAgentScopeAgent("行业研究员", AGENT_V1,
                SeedAgentPrompts.SECTOR_RESEARCHER, "[\"memory\",\"stock_industry\",\"data_enrich\"]");
        AgentDef sentimentAnalyst = seedAgentScopeAgent("舆情分析师", AGENT_V1,
                SeedAgentPrompts.SENTIMENT_ANALYST, "[\"memory\",\"data_enrich\"]");
        AgentDef stockSynthesizer = seedAgentScopeAgent("股票综合分析智能体", AGENT_V2,
                SeedAgentPrompts.CHIEF_SYNTHESIZER, "[\"memory\"]");

        // ── 3. 博客创作域智能体 ──
        AgentDef contentPlanner = seedPromptAgent("内容策划师", AGENT_V1, SeedAgentPrompts.CONTENT_PLANNER);
        AgentDef researchAgent = seedAgentScopeAgent("素材调研员", AGENT_V1,
                SeedAgentPrompts.RESEARCH_AGENT, "[\"memory\",\"data_enrich\"]");
        AgentDef seniorWriter = seedPromptAgent("资深撰稿人", AGENT_V1, SeedAgentPrompts.SENIOR_WRITER);
        AgentDef editor = seedPromptAgent("责编", AGENT_V1, SeedAgentPrompts.EDITOR);
        AgentDef seoSpecialist = seedPromptAgent("SEO专家", AGENT_V1, SeedAgentPrompts.SEO_SPECIALIST);

        // ── 4. 开发工作流 v2（9 节点串行）──
        seedWorkflow(DEV_WORKFLOW_NAME, DEV_WORKFLOW_VERSION,
                "标准软件开发流程 v2：需求编写→需求评审→详细设计→设计评审→开发→代码评审→测试→上线准备→发布验收",
                "DEV_PROCESS",
                buildDevWorkflowJson(reqAnalyst, reqReviewer, sysDesigner, designReviewer,
                        developer, codeReviewer, tester, releaseVerifier));

        // ── 5. 股票分析工作流 v2（11 节点 9 维并行汇聚）──
        seedWorkflow(STOCK_WORKFLOW_NAME, STOCK_WORKFLOW_VERSION,
                "九维专业股票研究流程 v2：数据总览→技术/趋势/基本面/估值/资金/行业/舆情/筹码并行分析→风险预警→首席综合研判",
                "STOCK_ANALYSIS",
                buildStockWorkflowJson(dataOverviewAnalyst, technicalAnalyst, trendAnalyst,
                        fundamentalAnalyst, valuationAnalyst, capitalAnalyst, sectorResearcher,
                        sentimentAnalyst, chipAnalyst, riskOfficer, stockSynthesizer));

        // ── 6. 博客文章创作工作流 v1（7 节点串行）；已有 AI 生成的旧博客类工作流不删除不修改 ──
        logLegacyBlogWorkflows();
        seedWorkflow(BLOG_WORKFLOW_NAME, BLOG_WORKFLOW_VERSION,
                "博客文章创作流程 v1：选题与大纲→素材调研→正文创作→润色→SEO优化→质量终审→发布包整理",
                "CUSTOM",
                buildBlogWorkflowJson(contentPlanner, researchAgent, seniorWriter, editor, seoSpecialist));

        // ── 7. 市场域智能体（9 个 v1）──
        AgentDef marketDataAnalyst = seedAgentScopeAgent("市场数据分析师", AGENT_V1,
                SeedMarketPrompts.MARKET_DATA_ANALYST, "[\"memory\",\"market_zt\",\"market_lhb\"]");
        AgentDef ztLadderAnalyst = seedAgentScopeAgent("涨停梯队分析师", AGENT_V1,
                SeedMarketPrompts.ZT_LADDER_ANALYST, "[\"memory\",\"market_zt\"]");
        AgentDef lhbCapitalAnalyst = seedAgentScopeAgent("龙虎榜资金分析师", AGENT_V1,
                SeedMarketPrompts.LHB_CAPITAL_ANALYST, "[\"memory\",\"market_lhb\"]");
        AgentDef marketSentimentAnalyst = seedPromptAgent("市场情绪分析师", AGENT_V1,
                SeedMarketPrompts.MARKET_SENTIMENT_ANALYST);
        AgentDef reviewSynthesizer = seedPromptAgent("复盘总结师", AGENT_V1,
                SeedMarketPrompts.REVIEW_SYNTHESIZER);
        AgentDef topicResearcher = seedPromptAgent("题材研究员", AGENT_V1,
                SeedMarketPrompts.TOPIC_RESEARCHER);
        AgentDef topicStockMiner = seedAgentScopeAgent("题材个股挖掘师", AGENT_V1,
                SeedMarketPrompts.TOPIC_STOCK_MINER, "[\"memory\",\"market_zt\",\"data_enrich\"]");
        // 组合数据汇总需逐只调用 3 个工具，迭代上限提高到 8
        AgentDef portfolioCollector = seedAgentScopeAgent("组合数据汇总师", AGENT_V1,
                SeedMarketPrompts.PORTFOLIO_DATA_COLLECTOR,
                "[\"memory\",\"stock_kline\",\"stock_finance\",\"stock_fundflow\"]", 8);
        AgentDef portfolioDoctor = seedPromptAgent("组合诊断师", AGENT_V1,
                SeedMarketPrompts.PORTFOLIO_DOCTOR);

        // ── 8. 每日复盘工作流 v1（5 节点：总览→三路并行→综合复盘）──
        seedWorkflow(REVIEW_WORKFLOW_NAME, REVIEW_WORKFLOW_VERSION,
                "每日复盘流程 v1：市场数据总览→涨停梯队/龙虎榜资金/两融情绪三路并行→综合复盘（≥1500字，含明日关注方向）",
                "MARKET_REVIEW",
                buildReviewWorkflowJson(marketDataAnalyst, ztLadderAnalyst, lhbCapitalAnalyst,
                        marketSentimentAnalyst, reviewSynthesizer));

        // ── 9. 题材挖掘工作流 v1（5 节点串行+汇聚）──
        seedWorkflow(TOPIC_WORKFLOW_NAME, TOPIC_WORKFLOW_VERSION,
                "题材挖掘流程 v1：题材界定→关联个股挖掘(market_zt+data_enrich)→产业链穿透→龙头梳理→综合报告",
                "CUSTOM",
                buildTopicWorkflowJson(topicResearcher, topicStockMiner, sectorResearcher));

        // ── 10. 组合诊断工作流 v1（3 节点串行）──
        seedWorkflow(PORTFOLIO_WORKFLOW_NAME, PORTFOLIO_WORKFLOW_VERSION,
                "组合诊断流程 v1：多股数据汇总→三维对比分析→分层排序建议（输入逗号分隔股票代码列表）",
                "CUSTOM",
                buildPortfolioWorkflowJson(portfolioCollector, portfolioDoctor));

        // ── 11. 功能开发协作域智能体（10 个 v1）──
        AgentDef productResearcher = seedPromptAgent("产品对标调研员", AGENT_V1,
                SeedDevTeamPrompts.PRODUCT_BENCHMARK_RESEARCHER);
        AgentDef dataSourceSurveyor = seedAgentScopeAgent("数据源调研员", AGENT_V1,
                SeedDevTeamPrompts.DATA_SOURCE_SURVEYOR, "[\"memory\"]");
        AgentDef techAssetAuditor = seedAgentScopeAgent("技术资产盘点师", AGENT_V1,
                SeedDevTeamPrompts.TECH_ASSET_AUDITOR, "[\"memory\"]");
        AgentDef techLead = seedPromptAgent("技术负责人", AGENT_V1, SeedDevTeamPrompts.TECH_LEAD);
        AgentDef dataPipelineEngineer = seedPromptAgent("数据管道工程师", AGENT_V1,
                SeedDevTeamPrompts.DATA_PIPELINE_ENGINEER);
        AgentDef frontendEngineer = seedPromptAgent("前端工程师", AGENT_V1,
                SeedDevTeamPrompts.FRONTEND_ENGINEER);
        AgentDef agentEnhanceEngineer = seedPromptAgent("智能体增强工程师", AGENT_V1,
                SeedDevTeamPrompts.AGENT_ENHANCE_ENGINEER);
        AgentDef deepFeatureEngineer = seedPromptAgent("深度功能工程师", AGENT_V1,
                SeedDevTeamPrompts.DEEP_FEATURE_ENGINEER);
        AgentDef impactReviewer = seedPromptAgent("影响面评审员", AGENT_V1,
                SeedDevTeamPrompts.IMPACT_REVIEWER);
        AgentDef deliveryLead = seedPromptAgent("交付负责人", AGENT_V1,
                SeedDevTeamPrompts.DELIVERY_LEAD);

        // ── 12. 功能开发协作工作流 v1（14 节点，3 处并行汇聚；复用开发工程师/测试工程师/代码评审员）──
        seedWorkflow(FEATURE_DEV_WORKFLOW_NAME, FEATURE_DEV_WORKFLOW_VERSION,
                "功能开发协作流程 v1：三视角并行调研（产品对标/数据源实证/技术资产）→蓝图规划→"
                        + "一期三路并行开发（数据管道/前端/后端）→二期两路并行开发（智能体增强/深度功能）→"
                        + "测试回归→三维并行代码评审（完整性/正确性/影响面）→文档同步与交付总结",
                "DEV_PROCESS",
                buildFeatureDevWorkflowJson(productResearcher, dataSourceSurveyor, techAssetAuditor,
                        techLead, dataPipelineEngineer, frontendEngineer, developer,
                        agentEnhanceEngineer, deepFeatureEngineer, tester, codeReviewer,
                        impactReviewer, deliveryLead));
    }

    // ════════════════════════════ Agent 种子（版本化 upsert） ════════════════════════════

    private AgentDef seedPromptAgent(String name, int targetVersion, String systemPrompt) {
        return upsertAgent(name, targetVersion, agent -> {
            agent.setType("PROMPT");
            agent.setSystemPrompt(systemPrompt);
            agent.setModelChoice("deepseek-v4");
            agent.setTemperature(0.7);
            agent.setMaxTokens(4096);
        });
    }

    private AgentDef seedAgentScopeAgent(String name, int targetVersion, String systemPrompt, String toolsJson) {
        return seedAgentScopeAgent(name, targetVersion, systemPrompt, toolsJson, 5);
    }

    private AgentDef seedAgentScopeAgent(String name, int targetVersion, String systemPrompt,
                                         String toolsJson, int maxIterations) {
        return upsertAgent(name, targetVersion, agent -> {
            agent.setType("AGENTSCOPE");
            agent.setSystemPrompt(systemPrompt);
            agent.setModelChoice("deepseek-v4");
            agent.setTemperature(0.7);
            agent.setMaxTokens(4096);
            agent.setToolsJson(toolsJson);
            agent.setMaxIterations(maxIterations);
            agent.setLoopDepth(2);
        });
    }

    /**
     * 版本化 upsert：不存在则创建；存在且（seedVersion 为 null 按 name 匹配视为种子历史行，
     * 或 seedVersion &lt; 目标版本）则原地更新；版本到位则跳过。
     */
    private AgentDef upsertAgent(String name, int targetVersion, Consumer<AgentDef> configurer) {
        Optional<AgentDef> existing = agentDefRepo.findByName(name);
        if (existing.isEmpty()) {
            AgentDef agent = new AgentDef();
            agent.setName(name);
            configurer.accept(agent);
            agent.setSeedVersion(targetVersion);
            AgentDef saved = agentDefRepo.save(agent);
            log.info("种子智能体已创建: id={}, name={}, type={}, seedVersion={}",
                    saved.getId(), name, saved.getType(), targetVersion);
            return saved;
        }
        AgentDef agent = existing.get();
        if (agent.getSeedVersion() != null && agent.getSeedVersion() >= targetVersion) {
            return agent; // 版本已到位
        }
        Integer fromVersion = agent.getSeedVersion();
        configurer.accept(agent);
        agent.setSeedVersion(targetVersion);
        AgentDef saved = agentDefRepo.save(agent);
        log.info("种子智能体已升级: id={}, name={}, seedVersion: {} -> {}",
                saved.getId(), name, fromVersion, targetVersion);
        return saved;
    }

    // ════════════════════════════ Workflow 种子（版本化 upsert） ════════════════════════════

    private void seedWorkflow(String name, int targetVersion, String description,
                              String category, String definitionJson) {
        Optional<WorkflowDef> existing = workflowDefRepo.findByName(name);
        if (existing.isEmpty()) {
            WorkflowDef def = new WorkflowDef();
            def.setName(name);
            def.setDescription(description);
            def.setCategory(category);
            def.setDefinitionJson(definitionJson);
            def.setStatus("DRAFT");
            def.setCreatedBy("USER");
            def.setVersion(0);
            def.setSeedVersion(targetVersion);
            WorkflowDef saved = workflowDefRepo.save(def);
            log.info("种子工作流已创建: id={}, name={}, category={}, seedVersion={}",
                    saved.getId(), name, category, targetVersion);
            return;
        }
        WorkflowDef def = existing.get();
        if (def.getSeedVersion() != null && def.getSeedVersion() >= targetVersion) {
            return; // 版本已到位
        }
        Integer fromVersion = def.getSeedVersion();
        boolean wasPublished = "PUBLISHED".equals(def.getStatus());
        def.setDescription(description);
        def.setCategory(category);
        def.setDefinitionJson(definitionJson);
        def.setSeedVersion(targetVersion);
        WorkflowDef saved = workflowDefRepo.save(def);
        log.info("种子工作流已升级: id={}, name={}, seedVersion: {} -> {}, 原状态={}",
                saved.getId(), name, fromVersion, targetVersion, saved.getStatus());

        // 原为 PUBLISHED 的工作流自动重新部署使新定义生效；失败仅记 error 不阻断启动
        if (wasPublished) {
            try {
                deployService.deploy(saved.getId());
                log.info("种子工作流升级后已自动重新发布: id={}, name={}", saved.getId(), name);
            } catch (Exception e) {
                log.error("种子工作流升级后自动重新发布失败（不阻断启动，可手动重新发布）: id={}, name={}, 原因: {}",
                        saved.getId(), name, e.getMessage(), e);
            }
        }
    }

    /** 盘点 DB 中已有的 AI 生成博客类工作流（category=CUSTOM 或名称含"博客"），仅记录、不删除不修改 */
    private void logLegacyBlogWorkflows() {
        try {
            List<String> legacy = workflowDefRepo.findAll().stream()
                    .filter(w -> !BLOG_WORKFLOW_NAME.equals(w.getName()))
                    .filter(w -> (w.getName() != null && w.getName().contains("博客"))
                            || ("CUSTOM".equals(w.getCategory()) && "AGENT".equals(w.getCreatedBy())))
                    .map(w -> "id=" + w.getId() + " name=" + w.getName())
                    .toList();
            if (!legacy.isEmpty()) {
                log.info("检测到已有 AI 生成的博客/CUSTOM 类工作流 {} 个（保持原样，不删除不修改）: {}",
                        legacy.size(), legacy);
            }
        } catch (Exception e) {
            log.warn("盘点旧博客类工作流失败（忽略）: {}", e.getMessage());
        }
    }

    // ════════════════════════════ 三个工作流 definitionJson ════════════════════════════

    /** 开发工作流 v2：9 节点串行 */
    String buildDevWorkflowJson(AgentDef reqAnalyst, AgentDef reqReviewer,
                                AgentDef sysDesigner, AgentDef designReviewer,
                                AgentDef developer, AgentDef codeReviewer,
                                AgentDef tester, AgentDef releaseVerifier) throws Exception {
        List<Map<String, Object>> nodes = List.of(
                node("req_write", "需求编写", reqAnalyst.getId(),
                        SeedNodePrompts.DEV_REQ_WRITE, 600, List.of()),
                node("req_review", "需求评审", reqReviewer.getId(),
                        SeedNodePrompts.DEV_REQ_REVIEW, 600, List.of("req_write")),
                node("design", "详细设计", sysDesigner.getId(),
                        SeedNodePrompts.DEV_DESIGN, 900, List.of("req_review")),
                node("design_review", "详细设计评审", designReviewer.getId(),
                        SeedNodePrompts.DEV_DESIGN_REVIEW, 600, List.of("design")),
                node("develop", "开发", developer.getId(),
                        SeedNodePrompts.DEV_DEVELOP, 900, List.of("design_review")),
                node("code_review", "代码评审", codeReviewer.getId(),
                        SeedNodePrompts.DEV_CODE_REVIEW, 600, List.of("develop")),
                node("test", "测试", tester.getId(),
                        SeedNodePrompts.DEV_TEST, 900, List.of("code_review")),
                node("release_check", "上线准备清单", tester.getId(),
                        SeedNodePrompts.DEV_RELEASE_CHECK, 600, List.of("test")),
                node("launch_verify", "发布验收", releaseVerifier.getId(),
                        SeedNodePrompts.DEV_LAUNCH_VERIFY, 600, List.of("release_check")));
        return toJson(DEV_WORKFLOW_NAME, nodes);
    }

    /** 股票分析工作流 v2：数据总览 → 8 维并行分析（chip 依赖 technical）→ 风险 → 综合研判，共 11 节点 */
    String buildStockWorkflowJson(AgentDef dataOverviewAnalyst, AgentDef technicalAnalyst,
                                  AgentDef trendAnalyst, AgentDef fundamentalAnalyst,
                                  AgentDef valuationAnalyst, AgentDef capitalAnalyst,
                                  AgentDef sectorResearcher, AgentDef sentimentAnalyst,
                                  AgentDef chipAnalyst, AgentDef riskOfficer,
                                  AgentDef stockSynthesizer) throws Exception {
        List<Map<String, Object>> nodes = List.of(
                node("data_overview", "数据总览", dataOverviewAnalyst.getId(),
                        SeedNodePrompts.STOCK_DATA_OVERVIEW, 600, List.of()),
                node("technical", "技术面分析", technicalAnalyst.getId(),
                        SeedNodePrompts.STOCK_TECHNICAL, 600, List.of("data_overview")),
                node("trend", "趋势研判", trendAnalyst.getId(),
                        SeedNodePrompts.STOCK_TREND, 600, List.of("data_overview")),
                node("fundamental", "基本面分析", fundamentalAnalyst.getId(),
                        SeedNodePrompts.STOCK_FUNDAMENTAL, 600, List.of("data_overview")),
                node("valuation", "估值分析", valuationAnalyst.getId(),
                        SeedNodePrompts.STOCK_VALUATION, 600, List.of("data_overview")),
                node("capital", "资金面分析", capitalAnalyst.getId(),
                        SeedNodePrompts.STOCK_CAPITAL, 600, List.of("data_overview")),
                node("sector", "行业与产业链分析", sectorResearcher.getId(),
                        SeedNodePrompts.STOCK_SECTOR, 600, List.of("data_overview")),
                node("sentiment", "舆情与催化分析", sentimentAnalyst.getId(),
                        SeedNodePrompts.STOCK_SENTIMENT, 600, List.of("data_overview")),
                node("chip", "筹码结构分析", chipAnalyst.getId(),
                        SeedNodePrompts.STOCK_CHIP, 600, List.of("technical")),
                node("risk", "风险预警", riskOfficer.getId(),
                        SeedNodePrompts.STOCK_RISK, 600, List.of("fundamental", "valuation")),
                node("synthesis", "综合研判与投资建议", stockSynthesizer.getId(),
                        SeedNodePrompts.STOCK_SYNTHESIS, 900,
                        List.of("technical", "trend", "fundamental", "valuation", "capital",
                                "sector", "sentiment", "chip", "risk")));
        return toJson(STOCK_WORKFLOW_NAME, nodes);
    }

    /** 博客文章创作工作流 v1：7 节点串行（draft 依赖 plan+research） */
    String buildBlogWorkflowJson(AgentDef contentPlanner, AgentDef researchAgent,
                                 AgentDef seniorWriter, AgentDef editor,
                                 AgentDef seoSpecialist) throws Exception {
        List<Map<String, Object>> nodes = List.of(
                node("plan", "选题与大纲", contentPlanner.getId(),
                        SeedNodePrompts.BLOG_PLAN, 600, List.of()),
                node("research", "素材调研", researchAgent.getId(),
                        SeedNodePrompts.BLOG_RESEARCH, 600, List.of("plan")),
                node("draft", "正文创作", seniorWriter.getId(),
                        SeedNodePrompts.BLOG_DRAFT, 900, List.of("plan", "research")),
                node("polish", "结构与文笔润色", editor.getId(),
                        SeedNodePrompts.BLOG_POLISH, 600, List.of("draft")),
                node("seo", "SEO优化", seoSpecialist.getId(),
                        SeedNodePrompts.BLOG_SEO, 600, List.of("polish")),
                node("review", "质量终审", editor.getId(),
                        SeedNodePrompts.BLOG_REVIEW, 600, List.of("seo")),
                node("publish_pack", "发布包整理", editor.getId(),
                        SeedNodePrompts.BLOG_PUBLISH_PACK, 600, List.of("review")));
        return toJson(BLOG_WORKFLOW_NAME, nodes);
    }

    /** 每日复盘工作流 v1：数据总览 → 涨停梯队/龙虎榜资金/两融情绪三路并行 → 综合复盘，共 5 节点 */
    String buildReviewWorkflowJson(AgentDef marketDataAnalyst, AgentDef ztLadderAnalyst,
                                   AgentDef lhbCapitalAnalyst, AgentDef marketSentimentAnalyst,
                                   AgentDef reviewSynthesizer) throws Exception {
        List<Map<String, Object>> nodes = List.of(
                node("review_data", "市场数据总览", marketDataAnalyst.getId(),
                        SeedMarketPrompts.REVIEW_DATA, 600, List.of()),
                node("zt_ladder", "涨停梯队分析", ztLadderAnalyst.getId(),
                        SeedMarketPrompts.REVIEW_ZT_LADDER, 600, List.of("review_data")),
                node("lhb_capital", "龙虎榜资金分析", lhbCapitalAnalyst.getId(),
                        SeedMarketPrompts.REVIEW_LHB, 600, List.of("review_data")),
                node("margin_sentiment", "两融与情绪分析", marketSentimentAnalyst.getId(),
                        SeedMarketPrompts.REVIEW_MARGIN_SENTIMENT, 600, List.of("review_data")),
                node("review_synthesis", "综合复盘", reviewSynthesizer.getId(),
                        SeedMarketPrompts.REVIEW_SYNTHESIS, 900,
                        List.of("zt_ladder", "lhb_capital", "margin_sentiment")));
        return toJson(REVIEW_WORKFLOW_NAME, nodes);
    }

    /** 题材挖掘工作流 v1：界定→个股挖掘→产业链穿透→龙头梳理（汇聚）→综合报告，共 5 节点 */
    String buildTopicWorkflowJson(AgentDef topicResearcher, AgentDef topicStockMiner,
                                  AgentDef sectorResearcher) throws Exception {
        List<Map<String, Object>> nodes = List.of(
                node("topic_define", "题材界定", topicResearcher.getId(),
                        SeedMarketPrompts.TOPIC_DEFINE, 600, List.of()),
                node("topic_stocks", "关联个股挖掘", topicStockMiner.getId(),
                        SeedMarketPrompts.TOPIC_STOCKS, 900, List.of("topic_define")),
                node("chain_analysis", "产业链穿透", sectorResearcher.getId(),
                        SeedMarketPrompts.TOPIC_CHAIN, 900, List.of("topic_stocks")),
                node("leader_review", "龙头梳理", topicResearcher.getId(),
                        SeedMarketPrompts.TOPIC_LEADER, 600, List.of("topic_stocks", "chain_analysis")),
                node("topic_report", "综合报告", topicResearcher.getId(),
                        SeedMarketPrompts.TOPIC_REPORT, 900, List.of("leader_review")));
        return toJson(TOPIC_WORKFLOW_NAME, nodes);
    }

    /** 组合诊断工作流 v1：数据汇总→对比分析→排序建议，共 3 节点串行 */
    String buildPortfolioWorkflowJson(AgentDef portfolioCollector, AgentDef portfolioDoctor) throws Exception {
        List<Map<String, Object>> nodes = List.of(
                node("portfolio_data", "组合数据汇总", portfolioCollector.getId(),
                        SeedMarketPrompts.PORTFOLIO_DATA, 900, List.of()),
                node("portfolio_compare", "多股对比分析", portfolioDoctor.getId(),
                        SeedMarketPrompts.PORTFOLIO_COMPARE, 900, List.of("portfolio_data")),
                node("portfolio_advice", "排序建议", portfolioDoctor.getId(),
                        SeedMarketPrompts.PORTFOLIO_ADVICE, 600, List.of("portfolio_compare")));
        return toJson(PORTFOLIO_WORKFLOW_NAME, nodes);
    }

    /**
     * 功能开发协作工作流 v1：三视角并行调研 → 蓝图规划（三路汇聚）→ 一期三路并行开发 →
     * 二期两路并行开发 → 测试回归（两路汇聚）→ 三维并行评审 → 交付总结（三路汇聚），共 14 节点。
     * 入口变量 ${requirement}；开发工程师/测试工程师/代码评审员复用开发域既有种子智能体。
     */
    String buildFeatureDevWorkflowJson(AgentDef productResearcher, AgentDef dataSourceSurveyor,
                                       AgentDef techAssetAuditor, AgentDef techLead,
                                       AgentDef dataPipelineEngineer, AgentDef frontendEngineer,
                                       AgentDef developer, AgentDef agentEnhanceEngineer,
                                       AgentDef deepFeatureEngineer, AgentDef tester,
                                       AgentDef codeReviewer, AgentDef impactReviewer,
                                       AgentDef deliveryLead) throws Exception {
        List<Map<String, Object>> nodes = List.of(
                node("research_product", "产品对标调研", productResearcher.getId(),
                        SeedDevTeamPrompts.FD_RESEARCH_PRODUCT, 600, List.of()),
                node("research_data", "数据源实证调研", dataSourceSurveyor.getId(),
                        SeedDevTeamPrompts.FD_RESEARCH_DATA, 600, List.of()),
                node("research_asset", "技术资产盘点", techAssetAuditor.getId(),
                        SeedDevTeamPrompts.FD_RESEARCH_ASSET, 600, List.of()),
                node("blueprint", "蓝图规划", techLead.getId(),
                        SeedDevTeamPrompts.FD_BLUEPRINT, 900,
                        List.of("research_product", "research_data", "research_asset")),
                node("dev_pipeline", "一期-数据管道开发", dataPipelineEngineer.getId(),
                        SeedDevTeamPrompts.FD_DEV_PIPELINE, 900, List.of("blueprint")),
                node("dev_frontend", "一期-前端开发", frontendEngineer.getId(),
                        SeedDevTeamPrompts.FD_DEV_FRONTEND, 900, List.of("blueprint")),
                node("dev_backend", "一期-后端开发", developer.getId(),
                        SeedDevTeamPrompts.FD_DEV_BACKEND, 900, List.of("blueprint")),
                node("dev_agent", "二期-智能体增强开发", agentEnhanceEngineer.getId(),
                        SeedDevTeamPrompts.FD_DEV_AGENT, 900,
                        List.of("dev_pipeline", "dev_backend")),
                node("dev_deep", "二期-深度功能开发", deepFeatureEngineer.getId(),
                        SeedDevTeamPrompts.FD_DEV_DEEP, 900,
                        List.of("dev_backend", "dev_frontend")),
                node("test_plan", "测试与回归方案", tester.getId(),
                        SeedDevTeamPrompts.FD_TEST_PLAN, 900,
                        List.of("dev_agent", "dev_deep")),
                node("review_complete", "完整性评审", codeReviewer.getId(),
                        SeedDevTeamPrompts.FD_REVIEW_COMPLETE, 600, List.of("test_plan")),
                node("review_correct", "正确性评审", codeReviewer.getId(),
                        SeedDevTeamPrompts.FD_REVIEW_CORRECT, 600, List.of("test_plan")),
                node("review_impact", "影响面评审", impactReviewer.getId(),
                        SeedDevTeamPrompts.FD_REVIEW_IMPACT, 600, List.of("test_plan")),
                node("delivery", "文档同步与交付总结", deliveryLead.getId(),
                        SeedDevTeamPrompts.FD_DELIVERY, 900,
                        List.of("review_complete", "review_correct", "review_impact")));
        return toJson(FEATURE_DEV_WORKFLOW_NAME, nodes);
    }

    // ════════════════════════════ 内部工具 ════════════════════════════

    private String toJson(String name, List<Map<String, Object>> nodes) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", name);
        root.put("nodes", nodes);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private Map<String, Object> node(String id, String name, Long agentId,
                                     String promptTemplate, int timeoutSeconds, List<String> dependsOn) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("name", name);
        node.put("agentId", agentId);
        node.put("promptTemplate", promptTemplate);
        node.put("timeoutSeconds", timeoutSeconds);
        node.put("dependsOn", dependsOn);
        return node;
    }
}
