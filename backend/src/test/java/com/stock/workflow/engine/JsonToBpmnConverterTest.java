package com.stock.workflow.engine;

import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.*;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ValidationError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JsonToBpmnConverter} 纯单元测试（无 Spring 上下文）。
 * 覆盖 validate（环/引用/agentId/id 唯一/孤立断链）与 convert（Start/ServiceTask/ParallelGateway/End 结构）。
 */
class JsonToBpmnConverterTest {

    private final JsonToBpmnConverter converter = new JsonToBpmnConverter();

    /** 测试用统一存在的 agentId 集合 */
    private static final Set<Long> AGENTS = Set.of(1L, 2L, 3L);

    // ─────────────────── 构造辅助 ───────────────────

    private static WorkflowJsonNode node(String id, Long agentId, String... deps) {
        WorkflowJsonNode n = new WorkflowJsonNode();
        n.setId(id);
        n.setName("节点-" + id);
        n.setAgentId(agentId);
        n.setPromptTemplate("执行 ${goal}");
        n.setTimeoutSeconds(300);
        n.setDependsOn(new ArrayList<>(List.of(deps)));
        return n;
    }

    private static WorkflowJsonDefinition def(String name, WorkflowJsonNode... nodes) {
        WorkflowJsonDefinition d = new WorkflowJsonDefinition();
        d.setName(name);
        d.setNodes(new ArrayList<>(List.of(nodes)));
        return d;
    }

    /** 收集模型中所有 SequenceFlow 的 "source->target" 表示 */
    private static Set<String> flowPairs(BpmnModel model) {
        return model.getMainProcess().getFlowElements().stream()
                .filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast)
                .map(f -> f.getSourceRef() + "->" + f.getTargetRef())
                .collect(Collectors.toSet());
    }

    private static <T extends FlowElement> List<T> elementsOf(BpmnModel model, Class<T> type) {
        return model.getMainProcess().getFlowElements().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    // ═══════════════════ validate ═══════════════════

    @Nested
    @DisplayName("validate：结构校验")
    class ValidateTests {

        @Test
        @DisplayName("合法串行链 → 无错误")
        void shouldPassWhenSerialChainValid() {
            WorkflowJsonDefinition d = def("串行",
                    node("n1", 1L),
                    node("n2", 2L, "n1"),
                    node("n3", 3L, "n2"));
            assertThat(converter.validate(d, AGENTS)).isEmpty();
        }

        @Test
        @DisplayName("空定义 → 报错")
        void shouldFailWhenDefinitionEmpty() {
            assertThat(converter.validate(null, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("至少需要一个节点"));
            assertThat(converter.validate(new WorkflowJsonDefinition(), AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("至少需要一个节点"));
        }

        @Test
        @DisplayName("环 n1→n2→n1 → 报循环依赖")
        void shouldFailWhenCycleExists() {
            WorkflowJsonDefinition d = def("环",
                    node("n1", 1L, "n2"),
                    node("n2", 2L, "n1"));
            assertThat(converter.validate(d, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("循环依赖").contains("n1").contains("n2"));
        }

        @Test
        @DisplayName("dependsOn 引用不存在节点 → 报错")
        void shouldFailWhenDependsOnMissingNode() {
            WorkflowJsonDefinition d = def("坏引用",
                    node("n1", 1L),
                    node("n2", 2L, "ghost"));
            assertThat(converter.validate(d, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("引用了不存在的节点").contains("ghost"));
        }

        @Test
        @DisplayName("节点依赖自身 → 报错")
        void shouldFailWhenSelfDependency() {
            WorkflowJsonDefinition d = def("自依赖", node("n1", 1L, "n1"));
            assertThat(converter.validate(d, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("不能依赖自身"));
        }

        @Test
        @DisplayName("agentId 不在存在集合 → 报错")
        void shouldFailWhenAgentIdNotExists() {
            WorkflowJsonDefinition d = def("坏agent", node("n1", 999L));
            assertThat(converter.validate(d, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("agentId=999").contains("不存在"));
        }

        @Test
        @DisplayName("agentId 缺失 → 报错")
        void shouldFailWhenAgentIdMissing() {
            WorkflowJsonDefinition d = def("无agent", node("n1", null));
            assertThat(converter.validate(d, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("缺少 agentId"));
        }

        @Test
        @DisplayName("节点 id 重复 → 报错")
        void shouldFailWhenDuplicateNodeId() {
            WorkflowJsonDefinition d = def("重复id", node("n1", 1L), node("n1", 2L));
            assertThat(converter.validate(d, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("节点 id 重复").contains("n1"));
        }

        @Test
        @DisplayName("节点 id 含非法字符 → 报错")
        void shouldFailWhenNodeIdIllegal() {
            WorkflowJsonDefinition d = def("非法id", node("n 1!", 1L));
            assertThat(converter.validate(d, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("非法字符"));
        }

        @Test
        @DisplayName("多节点中存在孤立节点（断链）→ 报错")
        void shouldFailWhenIsolatedNodeExists() {
            WorkflowJsonDefinition d = def("孤立",
                    node("n1", 1L),
                    node("n2", 2L, "n1"),
                    node("lonely", 3L));
            assertThat(converter.validate(d, AGENTS))
                    .anySatisfy(e -> assertThat(e).contains("lonely").contains("孤立节点"));
        }

        @Test
        @DisplayName("单节点无依赖 → 不视为孤立，校验通过")
        void shouldPassWhenSingleNodeWithoutDeps() {
            WorkflowJsonDefinition d = def("单节点", node("only", 1L));
            assertThat(converter.validate(d, AGENTS)).isEmpty();
        }
    }

    // ═══════════════════ convert ═══════════════════

    @Nested
    @DisplayName("convert：BpmnModel 结构")
    class ConvertTests {

        @Test
        @DisplayName("串行链 3 节点 → Start/End 各 1、ServiceTask 3、顺序连线正确")
        void shouldConvertSerialChain() {
            WorkflowJsonDefinition d = def("串行链",
                    node("n1", 1L),
                    node("n2", 2L, "n1"),
                    node("n3", 3L, "n2"));
            BpmnModel model = converter.convert(d, "wf_serial", 100L);

            Process process = model.getMainProcess();
            assertThat(process.getId()).isEqualTo("wf_serial");
            assertThat(process.isExecutable()).isTrue();

            assertThat(elementsOf(model, StartEvent.class)).hasSize(1);
            assertThat(elementsOf(model, EndEvent.class)).hasSize(1);
            assertThat(elementsOf(model, ServiceTask.class)).hasSize(3);
            assertThat(elementsOf(model, ParallelGateway.class)).isEmpty();

            assertThat(flowPairs(model)).containsExactlyInAnyOrder(
                    "startEvent->task_n1",
                    "task_n1->task_n2",
                    "task_n2->task_n3",
                    "task_n3->endEvent");
        }

        @Test
        @DisplayName("ServiceTask 为 delegateExpression=${agentTaskDelegate}、async=true、携带 nodeId/workflowDefId 字段")
        void shouldBuildServiceTaskWithDelegateAndFields() {
            WorkflowJsonDefinition d = def("单节点", node("n1", 1L));
            BpmnModel model = converter.convert(d, "wf_single", 42L);

            List<ServiceTask> tasks = elementsOf(model, ServiceTask.class);
            assertThat(tasks).hasSize(1);
            ServiceTask task = tasks.get(0);

            assertThat(task.getId()).isEqualTo("task_n1");
            assertThat(task.getImplementationType())
                    .isEqualTo(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
            assertThat(task.getImplementation()).isEqualTo("${agentTaskDelegate}");
            assertThat(task.isAsynchronous()).isTrue();

            Map<String, String> fields = task.getFieldExtensions().stream()
                    .collect(Collectors.toMap(FieldExtension::getFieldName, FieldExtension::getStringValue));
            assertThat(fields)
                    .containsEntry("nodeId", "n1")
                    .containsEntry("workflowDefId", "42");
            // promptTemplate 等含 ${} 的配置不得写入 FieldExtension（避免 JUEL 误解析）
            assertThat(fields).doesNotContainKey("promptTemplate");
        }

        @Test
        @DisplayName("菱形 1→(2,3)→4 → 分叉/汇聚 ParallelGateway 插入正确")
        void shouldInsertParallelGatewaysForDiamond() {
            WorkflowJsonDefinition d = def("菱形",
                    node("n1", 1L),
                    node("n2", 2L, "n1"),
                    node("n3", 3L, "n1"),
                    node("n4", 1L, "n2", "n3"));
            BpmnModel model = converter.convert(d, "wf_diamond", 100L);

            List<ParallelGateway> gateways = elementsOf(model, ParallelGateway.class);
            assertThat(gateways).extracting(ParallelGateway::getId)
                    .containsExactlyInAnyOrder("fork_n1", "join_n4");
            assertThat(elementsOf(model, ServiceTask.class)).hasSize(4);

            assertThat(flowPairs(model)).containsExactlyInAnyOrder(
                    "startEvent->task_n1",
                    "task_n1->fork_n1",       // 出度>1：task 后插分叉
                    "fork_n1->task_n2",
                    "fork_n1->task_n3",
                    "task_n2->join_n4",       // 入度>1：task 前插汇聚
                    "task_n3->join_n4",
                    "join_n4->task_n4",
                    "task_n4->endEvent");
        }

        @Test
        @DisplayName("多根多叶 → fork_start / join_end 网关插入正确")
        void shouldInsertStartForkAndEndJoinForMultiRootsLeaves() {
            // 两个根节点 a、b 汇入 c；c 再分出两个叶 d、e
            WorkflowJsonDefinition d = def("多根多叶",
                    node("a", 1L),
                    node("b", 2L),
                    node("c", 3L, "a", "b"),
                    node("d", 1L, "c"),
                    node("e", 2L, "c"));
            BpmnModel model = converter.convert(d, "wf_multi", 100L);

            assertThat(elementsOf(model, ParallelGateway.class))
                    .extracting(ParallelGateway::getId)
                    .containsExactlyInAnyOrder("fork_start", "join_c", "fork_c", "join_end");

            Set<String> flows = flowPairs(model);
            assertThat(flows).contains(
                    "startEvent->fork_start",
                    "fork_start->task_a",
                    "fork_start->task_b",
                    "task_a->join_c",
                    "task_b->join_c",
                    "join_c->task_c",
                    "task_c->fork_c",
                    "fork_c->task_d",
                    "fork_c->task_e",
                    "task_d->join_end",
                    "task_e->join_end",
                    "join_end->endEvent");
        }

        @Test
        @DisplayName("convert 产物通过 Flowable ProcessValidator 零致命错误")
        void shouldPassFlowableProcessValidator() {
            ProcessValidator validator = new ProcessValidatorFactory().createDefaultProcessValidator();

            BpmnModel serial = converter.convert(def("串行",
                    node("n1", 1L), node("n2", 2L, "n1"), node("n3", 3L, "n2")), "wf_v1", 1L);
            BpmnModel diamond = converter.convert(def("菱形",
                    node("n1", 1L), node("n2", 2L, "n1"),
                    node("n3", 3L, "n1"), node("n4", 1L, "n2", "n3")), "wf_v2", 2L);

            for (BpmnModel model : List.of(serial, diamond)) {
                List<ValidationError> fatal = validator.validate(model).stream()
                        .filter(e -> !e.isWarning())
                        .toList();
                assertThat(fatal)
                        .as("Flowable ProcessValidator 不应有致命错误: %s", fatal)
                        .isEmpty();
            }
        }
    }
}
