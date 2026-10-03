package com.stock.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.workflow.engine.JsonToBpmnConverter;
import com.stock.workflow.engine.WorkflowDeployService;
import com.stock.workflow.engine.WorkflowJsonDefinition;
import com.stock.workflow.engine.WorkflowJsonNode;
import com.stock.workflow.entity.AgentDef;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.AgentDefRepository;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link WorkflowSeedService} 单元测试：
 * <ul>
 *   <li>版本化 upsert 机制：新建 / 低版本升级 / 同版本跳过 / null seedVersion 首轮升级 / 用户数据不受影响；</li>
 *   <li>PUBLISHED 工作流升级后自动重新部署，deploy 失败不阻断；</li>
 *   <li>七个种子工作流 JSON 均通过 {@link JsonToBpmnConverter#validate} 结构校验（无环、依赖存在、无孤立节点）。</li>
 * </ul>
 */
class WorkflowSeedServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentDefRepository agentDefRepo;
    private WorkflowDefRepository workflowDefRepo;
    private WorkflowDeployService deployService;
    private WorkflowSeedService service;

    private final AtomicLong idSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        agentDefRepo = mock(AgentDefRepository.class);
        workflowDefRepo = mock(WorkflowDefRepository.class);
        deployService = mock(WorkflowDeployService.class);
        service = new WorkflowSeedService(agentDefRepo, workflowDefRepo, deployService);

        // 默认全新库：任何 name 均不存在；save 回填自增 id
        when(agentDefRepo.findByName(anyString())).thenReturn(Optional.empty());
        when(agentDefRepo.save(any(AgentDef.class))).thenAnswer(inv -> {
            AgentDef a = inv.getArgument(0);
            if (a.getId() == null) a.setId(idSeq.incrementAndGet());
            return a;
        });
        when(workflowDefRepo.findByName(anyString())).thenReturn(Optional.empty());
        when(workflowDefRepo.findAll()).thenReturn(List.of());
        when(workflowDefRepo.save(any(WorkflowDef.class))).thenAnswer(inv -> {
            WorkflowDef w = inv.getArgument(0);
            if (w.getId() == null) w.setId(idSeq.incrementAndGet());
            return w;
        });
    }

    // ════════════════════════════ 种子升级机制 ════════════════════════════

    @Nested
    @DisplayName("版本化 upsert 机制")
    class UpsertMechanism {

        @Test
        @DisplayName("全新库 → 创建全部种子智能体与 7 个工作流，均打上 seedVersion")
        void shouldCreateAllSeedsOnFreshDb() throws Exception {
            service.seedAgentsAndWorkflows();

            ArgumentCaptor<AgentDef> agentCaptor = ArgumentCaptor.forClass(AgentDef.class);
            verify(agentDefRepo, atLeast(30)).save(agentCaptor.capture());
            List<AgentDef> savedAgents = agentCaptor.getAllValues();
            assertThat(savedAgents).allSatisfy(a -> assertThat(a.getSeedVersion()).isNotNull());
            assertThat(savedAgents).extracting(AgentDef::getName)
                    .contains("需求分析师", "代码评审员", "发布验收员", "数据总览分析师",
                            "趋势分析师", "估值分析师", "资金面分析师", "筹码分析师", "风险官",
                            "行业研究员", "舆情分析师", "股票综合分析智能体",
                            "内容策划师", "素材调研员", "资深撰稿人", "责编", "SEO专家",
                            "市场数据分析师", "涨停梯队分析师", "龙虎榜资金分析师",
                            "市场情绪分析师", "复盘总结师", "题材研究员",
                            "题材个股挖掘师", "组合数据汇总师", "组合诊断师",
                            "产品对标调研员", "数据源调研员", "技术资产盘点师", "技术负责人",
                            "数据管道工程师", "前端工程师", "智能体增强工程师",
                            "深度功能工程师", "影响面评审员", "交付负责人");

            ArgumentCaptor<WorkflowDef> wfCaptor = ArgumentCaptor.forClass(WorkflowDef.class);
            verify(workflowDefRepo, times(7)).save(wfCaptor.capture());
            List<WorkflowDef> savedWfs = wfCaptor.getAllValues();
            assertThat(savedWfs).extracting(WorkflowDef::getName)
                    .containsExactlyInAnyOrder(WorkflowSeedService.DEV_WORKFLOW_NAME,
                            WorkflowSeedService.STOCK_WORKFLOW_NAME,
                            WorkflowSeedService.BLOG_WORKFLOW_NAME,
                            WorkflowSeedService.REVIEW_WORKFLOW_NAME,
                            WorkflowSeedService.TOPIC_WORKFLOW_NAME,
                            WorkflowSeedService.PORTFOLIO_WORKFLOW_NAME,
                            WorkflowSeedService.FEATURE_DEV_WORKFLOW_NAME);
            assertThat(savedWfs).allSatisfy(w -> {
                assertThat(w.getSeedVersion()).isNotNull();
                assertThat(w.getStatus()).isEqualTo("DRAFT");
                assertThat(w.getDefinitionJson()).isNotBlank();
            });
            // 新建（非升级）不会触发自动部署
            verify(deployService, never()).deploy(any());
        }

        @Test
        @DisplayName("已存在且 seedVersion 低于目标 → 原地升级（保留 id）并升 seedVersion")
        void shouldUpgradeAgentWithLowerSeedVersion() throws Exception {
            AgentDef existing = new AgentDef();
            existing.setId(100L);
            existing.setName("需求分析师");
            existing.setType("PROMPT");
            existing.setSeedVersion(1); // 低于目标 AGENT_V2=2
            when(agentDefRepo.findByName("需求分析师")).thenReturn(Optional.of(existing));

            service.seedAgentsAndWorkflows();

            ArgumentCaptor<AgentDef> captor = ArgumentCaptor.forClass(AgentDef.class);
            verify(agentDefRepo, atLeastOnce()).save(captor.capture());
            AgentDef upgraded = captor.getAllValues().stream()
                    .filter(a -> "需求分析师".equals(a.getName()))
                    .findFirst().orElseThrow();
            assertThat(upgraded.getId()).isEqualTo(100L); // 保留 id 与运行历史
            assertThat(upgraded.getSeedVersion()).isEqualTo(WorkflowSeedService.AGENT_V2);
            assertThat(upgraded.getSystemPrompt()).isEqualTo(SeedAgentPrompts.REQ_ANALYST);
        }

        @Test
        @DisplayName("已存在且 seedVersion 已到位 → 跳过不保存")
        void shouldSkipAgentWhenVersionUpToDate() throws Exception {
            AgentDef existing = new AgentDef();
            existing.setId(101L);
            existing.setName("需求分析师");
            existing.setType("PROMPT");
            existing.setSystemPrompt("用户自定义修改过的提示词");
            existing.setSeedVersion(WorkflowSeedService.AGENT_V2); // 版本已到位
            when(agentDefRepo.findByName("需求分析师")).thenReturn(Optional.of(existing));

            service.seedAgentsAndWorkflows();

            ArgumentCaptor<AgentDef> captor = ArgumentCaptor.forClass(AgentDef.class);
            verify(agentDefRepo, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues())
                    .noneMatch(a -> "需求分析师".equals(a.getName()));
            // 提示词未被覆盖
            assertThat(existing.getSystemPrompt()).isEqualTo("用户自定义修改过的提示词");
        }

        @Test
        @DisplayName("同名行 seedVersion 为 null（历史种子行）→ 首轮升级原地更新并打上版本")
        void shouldUpgradeLegacySeedRowWithNullVersion() throws Exception {
            AgentDef legacy = new AgentDef();
            legacy.setId(102L);
            legacy.setName("股票基本面分析师");
            legacy.setType("PROMPT");
            legacy.setSystemPrompt("旧版单薄提示词");
            legacy.setSeedVersion(null); // 上一版种子机制未打版本
            when(agentDefRepo.findByName("股票基本面分析师")).thenReturn(Optional.of(legacy));

            service.seedAgentsAndWorkflows();

            ArgumentCaptor<AgentDef> captor = ArgumentCaptor.forClass(AgentDef.class);
            verify(agentDefRepo, atLeastOnce()).save(captor.capture());
            AgentDef upgraded = captor.getAllValues().stream()
                    .filter(a -> "股票基本面分析师".equals(a.getName()))
                    .findFirst().orElseThrow();
            assertThat(upgraded.getId()).isEqualTo(102L);
            assertThat(upgraded.getSeedVersion()).isEqualTo(WorkflowSeedService.AGENT_V2);
            assertThat(upgraded.getSystemPrompt()).isNotEqualTo("旧版单薄提示词");
        }

        @Test
        @DisplayName("用户自建的博客类工作流（非种子名）不删除不修改")
        void shouldNotTouchUserCreatedWorkflows() throws Exception {
            WorkflowDef userWf = new WorkflowDef();
            userWf.setId(200L);
            userWf.setName("博客文章生成流程"); // AI 生成的旧博客工作流，与种子名不同
            userWf.setCategory("CUSTOM");
            userWf.setCreatedBy("AGENT");
            userWf.setStatus("PUBLISHED");
            when(workflowDefRepo.findAll()).thenReturn(List.of(userWf));

            service.seedAgentsAndWorkflows();

            ArgumentCaptor<WorkflowDef> captor = ArgumentCaptor.forClass(WorkflowDef.class);
            verify(workflowDefRepo, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues())
                    .noneMatch(w -> Objects.equals(w.getId(), 200L));
            verify(workflowDefRepo, never()).delete(any());
            verify(workflowDefRepo, never()).deleteById(any());
        }

        @Test
        @DisplayName("PUBLISHED 工作流低版本升级 → 保留 id 并自动重新部署")
        void shouldRedeployPublishedWorkflowAfterUpgrade() throws Exception {
            WorkflowDef existing = new WorkflowDef();
            existing.setId(300L);
            existing.setName(WorkflowSeedService.STOCK_WORKFLOW_NAME);
            existing.setCategory("STOCK_ANALYSIS");
            existing.setStatus("PUBLISHED");
            existing.setSeedVersion(1); // 低于目标 v2
            when(workflowDefRepo.findByName(WorkflowSeedService.STOCK_WORKFLOW_NAME))
                    .thenReturn(Optional.of(existing));

            service.seedAgentsAndWorkflows();

            ArgumentCaptor<WorkflowDef> captor = ArgumentCaptor.forClass(WorkflowDef.class);
            verify(workflowDefRepo, atLeastOnce()).save(captor.capture());
            WorkflowDef upgraded = captor.getAllValues().stream()
                    .filter(w -> WorkflowSeedService.STOCK_WORKFLOW_NAME.equals(w.getName()))
                    .findFirst().orElseThrow();
            assertThat(upgraded.getId()).isEqualTo(300L);
            assertThat(upgraded.getSeedVersion()).isEqualTo(WorkflowSeedService.STOCK_WORKFLOW_VERSION);
            verify(deployService).deploy(300L);
        }

        @Test
        @DisplayName("升级后自动重新部署失败 → 仅记 error，不抛异常不阻断")
        void shouldNotPropagateDeployFailure() {
            WorkflowDef existing = new WorkflowDef();
            existing.setId(301L);
            existing.setName(WorkflowSeedService.DEV_WORKFLOW_NAME);
            existing.setStatus("PUBLISHED");
            existing.setSeedVersion(1);
            when(workflowDefRepo.findByName(WorkflowSeedService.DEV_WORKFLOW_NAME))
                    .thenReturn(Optional.of(existing));
            doThrow(new IllegalStateException("BPMN 校验失败")).when(deployService).deploy(301L);

            assertThatCode(() -> service.seedAgentsAndWorkflows()).doesNotThrowAnyException();
            verify(deployService).deploy(301L);
        }
    }

    // ════════════════════════════ 种子 JSON 结构校验 ════════════════════════════

    @Nested
    @DisplayName("种子工作流 JSON 通过 JsonToBpmnConverter 校验")
    class SeedJsonValidation {

        private final JsonToBpmnConverter converter = new JsonToBpmnConverter();

        private AgentDef agent(long id) {
            AgentDef a = new AgentDef();
            a.setId(id);
            a.setName("agent-" + id);
            a.setType("PROMPT");
            return a;
        }

        /** 解析 JSON 并用真实 converter 校验（agentId 放宽为 JSON 内引用集合本身） */
        private WorkflowJsonDefinition parseAndValidate(String json) throws Exception {
            WorkflowJsonDefinition def = MAPPER.readValue(json, WorkflowJsonDefinition.class);
            Set<Long> agentIds = def.getNodes().stream()
                    .map(WorkflowJsonNode::getAgentId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            List<String> errors = converter.validate(def, agentIds);
            assertThat(errors).isEmpty();
            return def;
        }

        @Test
        @DisplayName("开发工作流 v2：9 节点串行，首节点含 ${goal}")
        void devWorkflowJsonShouldBeValid() throws Exception {
            String json = service.buildDevWorkflowJson(agent(1), agent(2), agent(3), agent(4),
                    agent(5), agent(6), agent(7), agent(8));
            WorkflowJsonDefinition def = parseAndValidate(json);

            assertThat(def.getNodes()).hasSize(9);
            assertThat(def.getNodes()).extracting(WorkflowJsonNode::getId)
                    .containsExactly("req_write", "req_review", "design", "design_review",
                            "develop", "code_review", "test", "release_check", "launch_verify");
            WorkflowJsonNode first = def.getNodes().get(0);
            assertThat(first.getDependsOn()).isEmpty();
            assertThat(first.getPromptTemplate()).contains("${goal}");
            assertThat(json).contains("${code_review.output}").contains("${release_check.output}");
        }

        @Test
        @DisplayName("股票分析工作流 v2：11 节点并行汇聚，契约占位符齐全")
        void stockWorkflowJsonShouldBeValid() throws Exception {
            String json = service.buildStockWorkflowJson(agent(11), agent(12), agent(13),
                    agent(14), agent(15), agent(16), agent(17), agent(18), agent(19),
                    agent(20), agent(21));
            WorkflowJsonDefinition def = parseAndValidate(json);

            assertThat(def.getNodes()).hasSize(11);
            // 入口节点与并行结构
            WorkflowJsonNode overview = def.getNodes().stream()
                    .filter(n -> "data_overview".equals(n.getId())).findFirst().orElseThrow();
            assertThat(overview.getDependsOn()).isEmpty();
            WorkflowJsonNode synthesis = def.getNodes().stream()
                    .filter(n -> "synthesis".equals(n.getId())).findFirst().orElseThrow();
            assertThat(synthesis.getDependsOn()).containsExactlyInAnyOrder(
                    "technical", "trend", "fundamental", "valuation", "capital",
                    "sector", "sentiment", "chip", "risk");
            assertThat(synthesis.getTimeoutSeconds()).isEqualTo(900);
            // 用户占位符与后端预注入变量契约
            assertThat(json).contains("${stockCode}").contains("${stockName}")
                    .contains("${_kline_context}").contains("${_finance_context}")
                    .contains("${_fundflow_context}").contains("${_industry_context}")
                    .contains("${_company_context}")
                    .contains("${data_overview.output}").contains("${risk.output}");
        }

        @Test
        @DisplayName("博客创作工作流 v1：7 节点，draft 并行汇聚 plan+research")
        void blogWorkflowJsonShouldBeValid() throws Exception {
            String json = service.buildBlogWorkflowJson(agent(31), agent(32), agent(33),
                    agent(34), agent(35));
            WorkflowJsonDefinition def = parseAndValidate(json);

            assertThat(def.getNodes()).hasSize(7);
            assertThat(def.getNodes()).extracting(WorkflowJsonNode::getId)
                    .containsExactly("plan", "research", "draft", "polish", "seo",
                            "review", "publish_pack");
            WorkflowJsonNode draft = def.getNodes().stream()
                    .filter(n -> "draft".equals(n.getId())).findFirst().orElseThrow();
            assertThat(draft.getDependsOn()).containsExactlyInAnyOrder("plan", "research");
            assertThat(json).contains("${goal}").contains("${plan.output}")
                    .contains("${research.output}").contains("${review.output}");
        }

        @Test
        @DisplayName("每日复盘工作流 v1：5 节点三路并行汇聚，含 _sentiment_context 预注入契约")
        void reviewWorkflowJsonShouldBeValid() throws Exception {
            String json = service.buildReviewWorkflowJson(agent(41), agent(42), agent(43),
                    agent(44), agent(45));
            WorkflowJsonDefinition def = parseAndValidate(json);

            assertThat(def.getNodes()).hasSize(5);
            assertThat(def.getNodes()).extracting(WorkflowJsonNode::getId)
                    .containsExactly("review_data", "zt_ladder", "lhb_capital",
                            "margin_sentiment", "review_synthesis");
            WorkflowJsonNode entry = def.getNodes().get(0);
            assertThat(entry.getDependsOn()).isEmpty();
            WorkflowJsonNode synthesis = def.getNodes().stream()
                    .filter(n -> "review_synthesis".equals(n.getId())).findFirst().orElseThrow();
            assertThat(synthesis.getDependsOn()).containsExactlyInAnyOrder(
                    "zt_ladder", "lhb_capital", "margin_sentiment");
            assertThat(synthesis.getTimeoutSeconds()).isEqualTo(900);
            assertThat(json).contains("${_sentiment_context}")
                    .contains("${review_data.output}").contains("${zt_ladder.output}")
                    .contains("${lhb_capital.output}").contains("${margin_sentiment.output}");
        }

        @Test
        @DisplayName("题材挖掘工作流 v1：5 节点，首节点含 ${goal}，报告汇聚双路")
        void topicWorkflowJsonShouldBeValid() throws Exception {
            String json = service.buildTopicWorkflowJson(agent(51), agent(52), agent(53));
            WorkflowJsonDefinition def = parseAndValidate(json);

            assertThat(def.getNodes()).hasSize(5);
            assertThat(def.getNodes()).extracting(WorkflowJsonNode::getId)
                    .containsExactly("topic_define", "topic_stocks", "chain_analysis",
                            "leader_review", "topic_report");
            WorkflowJsonNode first = def.getNodes().get(0);
            assertThat(first.getDependsOn()).isEmpty();
            assertThat(first.getPromptTemplate()).contains("${goal}");
            WorkflowJsonNode leaderReview = def.getNodes().stream()
                    .filter(n -> "leader_review".equals(n.getId())).findFirst().orElseThrow();
            assertThat(leaderReview.getDependsOn()).containsExactlyInAnyOrder(
                    "topic_stocks", "chain_analysis");
            assertThat(json).contains("${topic_define.output}").contains("${topic_stocks.output}")
                    .contains("${chain_analysis.output}").contains("${leader_review.output}");
        }

        @Test
        @DisplayName("组合诊断工作流 v1：3 节点串行，首节点含 ${goal}")
        void portfolioWorkflowJsonShouldBeValid() throws Exception {
            String json = service.buildPortfolioWorkflowJson(agent(61), agent(62));
            WorkflowJsonDefinition def = parseAndValidate(json);

            assertThat(def.getNodes()).hasSize(3);
            assertThat(def.getNodes()).extracting(WorkflowJsonNode::getId)
                    .containsExactly("portfolio_data", "portfolio_compare", "portfolio_advice");
            WorkflowJsonNode first = def.getNodes().get(0);
            assertThat(first.getDependsOn()).isEmpty();
            assertThat(first.getPromptTemplate()).contains("${goal}");
            assertThat(json).contains("${portfolio_data.output}").contains("${portfolio_compare.output}");
        }

        @Test
        @DisplayName("功能开发协作工作流 v1：14 节点三处并行汇聚，入口含 ${requirement}")
        void featureDevWorkflowJsonShouldBeValid() throws Exception {
            String json = service.buildFeatureDevWorkflowJson(agent(71), agent(72), agent(73),
                    agent(74), agent(75), agent(76), agent(77), agent(78), agent(79),
                    agent(80), agent(81), agent(82), agent(83));
            WorkflowJsonDefinition def = parseAndValidate(json);

            assertThat(def.getNodes()).hasSize(14);
            assertThat(def.getNodes()).extracting(WorkflowJsonNode::getId)
                    .containsExactly("research_product", "research_data", "research_asset",
                            "blueprint", "dev_pipeline", "dev_frontend", "dev_backend",
                            "dev_agent", "dev_deep", "test_plan",
                            "review_complete", "review_correct", "review_impact", "delivery");

            // 三视角调研为并行入口（无依赖）
            assertThat(def.getNodes().stream()
                    .filter(n -> n.getId().startsWith("research_"))
                    .toList())
                    .hasSize(3)
                    .allSatisfy(n -> assertThat(n.getDependsOn()).isEmpty());

            // 汇聚点一：蓝图规划汇聚三路调研
            WorkflowJsonNode blueprint = node(def, "blueprint");
            assertThat(blueprint.getDependsOn()).containsExactlyInAnyOrder(
                    "research_product", "research_data", "research_asset");

            // 一期三路并行开发均只依赖蓝图
            assertThat(node(def, "dev_pipeline").getDependsOn()).containsExactly("blueprint");
            assertThat(node(def, "dev_frontend").getDependsOn()).containsExactly("blueprint");
            assertThat(node(def, "dev_backend").getDependsOn()).containsExactly("blueprint");

            // 二期两路并行开发依赖一期产出
            assertThat(node(def, "dev_agent").getDependsOn())
                    .containsExactlyInAnyOrder("dev_pipeline", "dev_backend");
            assertThat(node(def, "dev_deep").getDependsOn())
                    .containsExactlyInAnyOrder("dev_backend", "dev_frontend");

            // 汇聚点二：测试回归汇聚二期两路
            assertThat(node(def, "test_plan").getDependsOn())
                    .containsExactlyInAnyOrder("dev_agent", "dev_deep");

            // 三维评审并行展开于测试之后
            assertThat(node(def, "review_complete").getDependsOn()).containsExactly("test_plan");
            assertThat(node(def, "review_correct").getDependsOn()).containsExactly("test_plan");
            assertThat(node(def, "review_impact").getDependsOn()).containsExactly("test_plan");

            // 汇聚点三：交付总结汇聚三维评审
            WorkflowJsonNode delivery = node(def, "delivery");
            assertThat(delivery.getDependsOn()).containsExactlyInAnyOrder(
                    "review_complete", "review_correct", "review_impact");
            assertThat(delivery.getTimeoutSeconds()).isEqualTo(900);

            // 变量契约：入口用户输入 + 各阶段上游输出引用
            assertThat(json).contains("${requirement}")
                    .contains("${research_product.output}").contains("${research_data.output}")
                    .contains("${research_asset.output}").contains("${blueprint.output}")
                    .contains("${dev_pipeline.output}").contains("${dev_frontend.output}")
                    .contains("${dev_backend.output}").contains("${dev_agent.output}")
                    .contains("${dev_deep.output}").contains("${test_plan.output}")
                    .contains("${review_complete.output}").contains("${review_correct.output}")
                    .contains("${review_impact.output}");
            // 入口三节点提示词均注入用户需求
            assertThat(node(def, "research_product").getPromptTemplate()).contains("${requirement}");
            assertThat(node(def, "research_data").getPromptTemplate()).contains("${requirement}");
            assertThat(node(def, "research_asset").getPromptTemplate()).contains("${requirement}");
        }

        private WorkflowJsonNode node(WorkflowJsonDefinition def, String id) {
            return def.getNodes().stream()
                    .filter(n -> id.equals(n.getId())).findFirst().orElseThrow();
        }
    }
}
