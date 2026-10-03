package com.stock.workflow.service;

import com.stock.workflow.engine.JsonToBpmnConverter;
import com.stock.workflow.engine.agent.PromptAgentRunner;
import com.stock.workflow.entity.AgentDef;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.AgentDefRepository;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link WorkflowGeneratorService} 单元测试：mock {@link PromptAgentRunner}（不调真实 LLM），
 * 使用真实 {@link JsonToBpmnConverter} 验证三重校验（剥围栏 → JSON 解析 → 结构校验）+ 重试 + 回退逻辑。
 */
class WorkflowGeneratorServiceTest {

    private static final long AGENT_ID = 1L;

    private WorkflowDefRepository workflowDefRepo;
    private AgentDefRepository agentDefRepo;
    private PromptAgentRunner promptAgentRunner;
    private WorkflowGeneratorService service;

    private AgentDef agent;

    // ─────────────────── LLM 输出样例 ───────────────────

    /** 合法单节点 JSON（带 ```json 围栏） */
    private static final String VALID_JSON_FENCED = """
            ```json
            {
              "name": "开发工作流",
              "category": "DEV_PROCESS",
              "nodes": [
                {"id": "write", "name": "编写", "agentId": 1,
                 "promptTemplate": "完成 ${goal}", "timeoutSeconds": 300, "dependsOn": []}
              ]
            }
            ```""";

    /** 含环的 JSON（a→b→a，结构校验必失败） */
    private static final String CYCLIC_JSON = """
            {
              "name": "环工作流",
              "nodes": [
                {"id": "a", "agentId": 1, "promptTemplate": "x", "dependsOn": ["b"]},
                {"id": "b", "agentId": 1, "promptTemplate": "y", "dependsOn": ["a"]}
              ]
            }""";

    private static final String GARBAGE = "抱歉，我无法生成有效的工作流。";

    @BeforeEach
    void setUp() {
        workflowDefRepo = mock(WorkflowDefRepository.class);
        agentDefRepo = mock(AgentDefRepository.class);
        promptAgentRunner = mock(PromptAgentRunner.class);
        service = new WorkflowGeneratorService(
                workflowDefRepo, agentDefRepo, new JsonToBpmnConverter(), promptAgentRunner);

        agent = new AgentDef();
        agent.setId(AGENT_ID);
        agent.setName("分析师");
        agent.setType("PROMPT");
        agent.setSystemPrompt("你是分析师");

        // agentDefRepo：findAll 返回一个智能体；findAllById 按 id 过滤返回
        lenient().when(agentDefRepo.findAll()).thenReturn(List.of(agent));
        lenient().when(agentDefRepo.findAllById(any())).thenAnswer(inv -> {
            Iterable<Long> ids = inv.getArgument(0);
            List<AgentDef> found = new ArrayList<>();
            for (Long id : ids) {
                if (Objects.equals(id, AGENT_ID)) {
                    found.add(agent);
                }
            }
            return found;
        });
        // workflowDefRepo：无重名；save 回填 id
        lenient().when(workflowDefRepo.findByName(anyString())).thenReturn(Optional.empty());
        lenient().when(workflowDefRepo.save(any(WorkflowDef.class))).thenAnswer(inv -> {
            WorkflowDef d = inv.getArgument(0);
            if (d.getId() == null) {
                d.setId(100L);
            }
            return d;
        });
    }

    // ═══════════════════ generate ═══════════════════

    @Nested
    @DisplayName("generate：AI 生成")
    class GenerateTests {

        @Test
        @DisplayName("首次返回带 ```json 围栏的合法 JSON → 解析成功，保存 DRAFT，fallback=false")
        void shouldSaveDraftWhenFirstAttemptValid() {
            when(promptAgentRunner.run(any(AgentDef.class), anyString(), anyInt()))
                    .thenReturn(VALID_JSON_FENCED);

            WorkflowGenerationResult result = service.generate("帮我生成一个开发工作流");

            assertThat(result.isFallback()).isFalse();
            WorkflowDef saved = result.getWorkflow();
            assertThat(saved.getId()).isEqualTo(100L);
            assertThat(saved.getName()).isEqualTo("开发工作流");
            assertThat(saved.getStatus()).isEqualTo("DRAFT");
            assertThat(saved.getCreatedBy()).isEqualTo("AGENT");
            assertThat(saved.getCategory()).isEqualTo("DEV_PROCESS");
            // definitionJson 是剥围栏后的规范化 JSON
            assertThat(saved.getDefinitionJson()).contains("\"write\"").doesNotContain("```");

            verify(promptAgentRunner, times(1)).run(any(AgentDef.class), anyString(), anyInt());
            verify(workflowDefRepo, times(1)).save(any(WorkflowDef.class));
        }

        @Test
        @DisplayName("首次返回含环 JSON、重试返回合法 → 成功且 LLM 被调用两次")
        void shouldRetryOnceWhenFirstAttemptCyclic() {
            when(promptAgentRunner.run(any(AgentDef.class), anyString(), anyInt()))
                    .thenReturn(CYCLIC_JSON)
                    .thenReturn(VALID_JSON_FENCED);

            WorkflowGenerationResult result = service.generate("生成工作流");

            assertThat(result.isFallback()).isFalse();
            assertThat(result.getWorkflow().getName()).isEqualTo("开发工作流");
            verify(promptAgentRunner, times(2)).run(any(AgentDef.class), anyString(), anyInt());

            // 重试提示词应携带上次错误信息（循环依赖）
            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(promptAgentRunner, times(2)).run(any(AgentDef.class), promptCaptor.capture(), anyInt());
            assertThat(promptCaptor.getAllValues().get(1)).contains("循环依赖");
        }

        @Test
        @DisplayName("两次输出均非法 → 回退内置模板，fallback=true")
        void shouldFallbackWhenBothAttemptsInvalid() {
            when(promptAgentRunner.run(any(AgentDef.class), anyString(), anyInt()))
                    .thenReturn(GARBAGE);

            WorkflowGenerationResult result = service.generate("生成工作流");

            assertThat(result.isFallback()).isTrue();
            assertThat(result.getMessage()).contains("回退");
            WorkflowDef saved = result.getWorkflow();
            assertThat(saved.getStatus()).isEqualTo("DRAFT");
            assertThat(saved.getCategory()).isEqualTo("CUSTOM");
            // 回退模板为三节点（规划→执行→复核）串行 DAG
            assertThat(saved.getDefinitionJson()).contains("\"plan\"").contains("\"execute\"").contains("\"review\"");
            assertThat(saved.getDefinitionJson()).contains("${plan.output}").contains("${execute.output}");
            verify(promptAgentRunner, times(2)).run(any(AgentDef.class), anyString(), anyInt());
        }

        @Test
        @DisplayName("首次 LLM 调用抛异常、重试成功 → 成功且调用两次")
        void shouldRecoverWhenFirstCallThrows() {
            when(promptAgentRunner.run(any(AgentDef.class), anyString(), anyInt()))
                    .thenThrow(new IllegalStateException("模型超时"))
                    .thenReturn(VALID_JSON_FENCED);

            WorkflowGenerationResult result = service.generate("生成工作流");

            assertThat(result.isFallback()).isFalse();
            verify(promptAgentRunner, times(2)).run(any(AgentDef.class), anyString(), anyInt());
        }

        @Test
        @DisplayName("description 为空 → IllegalArgumentException，不触发 LLM")
        void shouldRejectBlankDescription() {
            assertThatThrownBy(() -> service.generate("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("description");
            verifyNoInteractions(promptAgentRunner);
        }

        @Test
        @DisplayName("系统无任何 AgentDef → IllegalStateException")
        void shouldRejectWhenNoAgents() {
            when(agentDefRepo.findAll()).thenReturn(List.of());
            assertThatThrownBy(() -> service.generate("生成工作流"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("智能体");
            verifyNoInteractions(promptAgentRunner);
        }

        @Test
        @DisplayName("同名工作流已存在 → 保存名称自动追加序号")
        void shouldAppendSuffixWhenNameConflicts() {
            when(promptAgentRunner.run(any(AgentDef.class), anyString(), anyInt()))
                    .thenReturn(VALID_JSON_FENCED);
            WorkflowDef existing = new WorkflowDef();
            existing.setId(9L);
            when(workflowDefRepo.findByName("开发工作流")).thenReturn(Optional.of(existing));

            WorkflowGenerationResult result = service.generate("生成工作流");

            assertThat(result.getWorkflow().getName()).isEqualTo("开发工作流-2");
        }
    }

    // ═══════════════════ aiEdit ═══════════════════

    @Nested
    @DisplayName("aiEdit：AI 修改")
    class AiEditTests {

        private WorkflowDef existingDef() {
            WorkflowDef def = new WorkflowDef();
            def.setId(5L);
            def.setName("旧工作流");
            def.setStatus("PUBLISHED");
            def.setDefinitionJson("""
                    {"name":"旧工作流","nodes":[
                      {"id":"old","agentId":1,"promptTemplate":"旧 ${goal}","dependsOn":[]}]}""");
            return def;
        }

        @Test
        @DisplayName("修改成功 → definitionJson 更新，PUBLISHED 置回 DRAFT")
        void shouldUpdateJsonAndResetStatusToDraft() {
            WorkflowDef def = existingDef();
            when(workflowDefRepo.findById(5L)).thenReturn(Optional.of(def));
            when(promptAgentRunner.run(any(AgentDef.class), anyString(), anyInt()))
                    .thenReturn(VALID_JSON_FENCED);

            WorkflowDef updated = service.aiEdit(5L, "把节点改成编写节点");

            assertThat(updated.getStatus()).isEqualTo("DRAFT");
            assertThat(updated.getDefinitionJson()).contains("\"write\"");
            verify(workflowDefRepo).save(def);
        }

        @Test
        @DisplayName("两次输出均非法 → 抛 IllegalArgumentException 且不保存")
        void shouldThrowWhenBothAttemptsInvalid() {
            when(workflowDefRepo.findById(5L)).thenReturn(Optional.of(existingDef()));
            when(promptAgentRunner.run(any(AgentDef.class), anyString(), anyInt()))
                    .thenReturn(GARBAGE);

            assertThatThrownBy(() -> service.aiEdit(5L, "随便改改"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("AI 修改失败");
            verify(promptAgentRunner, times(2)).run(any(AgentDef.class), anyString(), anyInt());
            verify(workflowDefRepo, never()).save(any(WorkflowDef.class));
        }

        @Test
        @DisplayName("工作流不存在 → NoSuchElementException")
        void shouldThrowWhenWorkflowNotFound() {
            when(workflowDefRepo.findById(404L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.aiEdit(404L, "改"))
                    .isInstanceOf(NoSuchElementException.class);
        }

        @Test
        @DisplayName("instruction 为空 → IllegalArgumentException")
        void shouldRejectBlankInstruction() {
            assertThatThrownBy(() -> service.aiEdit(5L, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("instruction");
            verifyNoInteractions(promptAgentRunner);
        }
    }

    // ═══════════════════ stripCodeFence ═══════════════════

    @Nested
    @DisplayName("stripCodeFence：围栏剥离")
    class StripCodeFenceTests {

        @Test
        @DisplayName("```json 围栏 → 提取内部 JSON")
        void shouldStripJsonFence() {
            assertThat(WorkflowGeneratorService.stripCodeFence("```json\n{\"a\":1}\n```"))
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("无语言标记围栏 → 提取内部 JSON")
        void shouldStripPlainFence() {
            assertThat(WorkflowGeneratorService.stripCodeFence("```\n{\"a\":1}\n```"))
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("JSON 前后夹杂解释文字 → 截取首 { 到末 }")
        void shouldExtractBracesFromSurroundingText() {
            assertThat(WorkflowGeneratorService.stripCodeFence("以下是结果：{\"a\":1} 希望满意"))
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("null / 纯文本 → 安全返回")
        void shouldHandleNullAndPlainText() {
            assertThat(WorkflowGeneratorService.stripCodeFence(null)).isEmpty();
            assertThat(WorkflowGeneratorService.stripCodeFence("没有大括号的文本"))
                    .isEqualTo("没有大括号的文本");
        }
    }
}
