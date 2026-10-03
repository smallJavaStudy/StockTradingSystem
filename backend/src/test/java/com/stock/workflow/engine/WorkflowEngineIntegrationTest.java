package com.stock.workflow.engine;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.*;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.job.service.impl.asyncexecutor.AsyncExecutor;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流引擎集成测试：独立 Flowable 内存引擎（H2 in-memory），不依赖 Spring 上下文 / MySQL / 外部 LLM。
 * <p>
 * 说明：
 * <ul>
 *   <li>通过 {@code ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration()} 手工建引擎，
 *       用 {@code setBeans(Map)} 注入名为 <b>agentTaskDelegate</b> 的测试假 Delegate（{@link RecordingDelegate}），
 *       与生产 delegateExpression=${agentTaskDelegate} 一致，记录执行顺序并写 {@code <nodeId>_output} 变量模拟输出。</li>
 *   <li>ServiceTask 保持转换器产出的 <b>async=true</b>（与生产一致），引擎开启 AsyncExecutor，
 *       通过 HistoryService 轮询等待流程结束（超时 30s）。</li>
 *   <li>失败用例将 Job 重试次数设为 1（生产默认 3），失败后立即进死信队列，避免 10s 重试等待造成的
 *       用例超时与 flaky。</li>
 *   <li>取消用例通过临时停掉 AsyncExecutor 制造确定性的"运行中（Job 排队）"状态后取消，
 *       避免 H2 单连接锁与执行中 Job 事务互相等待。</li>
 *   <li>类名不用 *IT 后缀：surefire 默认 includes 不含 *IT，用 *IntegrationTest 保证 mvn test 执行。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowEngineIntegrationTest {

    private static final long WAIT_TIMEOUT_MS = 30_000;
    private static final Set<Long> AGENTS = Set.of(1L);

    private ProcessEngine engine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private HistoryService historyService;
    private ManagementService managementService;

    private final JsonToBpmnConverter converter = new JsonToBpmnConverter();
    private final RecordingDelegate delegate = new RecordingDelegate();

    // ─────────────────── 引擎生命周期 ───────────────────

    @BeforeAll
    void setUpEngine() {
        ProcessEngineConfigurationImpl cfg = (ProcessEngineConfigurationImpl)
                ProcessEngineConfiguration.createStandaloneInMemProcessEngineConfiguration();
        cfg.setJdbcUrl("jdbc:h2:mem:wf-engine-it;DB_CLOSE_DELAY=-1");
        cfg.setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE);
        cfg.setAsyncExecutorActivate(true);        // 执行 async ServiceTask 的 Job
        cfg.setAsyncExecutorNumberOfRetries(1);    // 测试专用：失败 1 次即进死信（生产默认 3 次）
        Map<Object, Object> beans = new HashMap<>();
        beans.put("agentTaskDelegate", delegate);  // standalone 引擎经 beans map 解析 ${agentTaskDelegate}
        cfg.setBeans(beans);
        engine = cfg.buildProcessEngine();

        repositoryService = engine.getRepositoryService();
        runtimeService = engine.getRuntimeService();
        historyService = engine.getHistoryService();
        managementService = engine.getManagementService();
    }

    @AfterAll
    void tearDownEngine() {
        if (engine != null) {
            engine.close();
        }
    }

    @BeforeEach
    void resetDelegate() {
        delegate.reset();
    }

    // ═══════════════════ 用例 ═══════════════════

    @Test
    @DisplayName("串行链：部署成功→启动→节点按依赖顺序执行→变量传递→流程完成")
    void shouldRunSerialChainInOrderWithVariablePassing() {
        WorkflowJsonDefinition def = def("串行链",
                node("n1", "根据 ${goal} 编写需求"),
                node("n2", "评审 ${n1.output}", "n1"),
                node("n3", "开发 ${n2.output}", "n2"));
        String processKey = deploy(def, "wf_it_serial");

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                processKey, Map.of("goal", "测试目标"));
        assertThat(instance).isNotNull();

        waitUntil(() -> isFinished(instance.getId()), "流程应在 30s 内完成");

        // 执行顺序严格 n1 → n2 → n3
        assertThat(delegate.order).containsExactly("n1", "n2", "n3");

        // 变量传递：n2 启动时能读到 n1_output；n3 能读到 n1/n2 的输出
        assertThat(delegate.observedOutputs.get("n2"))
                .containsEntry("n1_output", "OUT_n1");
        assertThat(delegate.observedOutputs.get("n3"))
                .containsEntry("n1_output", "OUT_n1")
                .containsEntry("n2_output", "OUT_n2");

        // 历史变量中保留末节点输出
        Object n3Output = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId())
                .variableName("n3_output")
                .singleResult()
                .getValue();
        assertThat(n3Output).isEqualTo("OUT_n3");
    }

    @Test
    @DisplayName("菱形并行：n1→(n2,n3)→n4，两个并行节点都执行且顺序约束满足")
    void shouldRunDiamondWithParallelBranches() {
        WorkflowJsonDefinition def = def("菱形",
                node("n1", "分析 ${goal}"),
                node("n2", "基本面 ${n1.output}", "n1"),
                node("n3", "技术面 ${n1.output}", "n1"),
                node("n4", "综合 ${n2.output} ${n3.output}", "n2", "n3"));
        String processKey = deploy(def, "wf_it_diamond");

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                processKey, Map.of("goal", "并行目标"));

        waitUntil(() -> isFinished(instance.getId()), "并行流程应在 30s 内完成");

        List<String> order = List.copyOf(delegate.order);
        assertThat(order).hasSize(4).containsExactlyInAnyOrder("n1", "n2", "n3", "n4");
        // 依赖顺序约束：n1 最先，n4 最后（n2/n3 之间顺序不做约束）
        assertThat(order.get(0)).isEqualTo("n1");
        assertThat(order.get(3)).isEqualTo("n4");

        // 汇聚节点能同时读到两条并行分支的输出
        assertThat(delegate.observedOutputs.get("n4"))
                .containsEntry("n2_output", "OUT_n2")
                .containsEntry("n3_output", "OUT_n3");
    }

    @Test
    @DisplayName("取消：运行中实例 deleteProcessInstance 成功，历史记录含取消原因")
    void shouldCancelRunningInstance() {
        WorkflowJsonDefinition def = def("可取消", node("blk", "长任务 ${goal}"));
        String processKey = deploy(def, "wf_it_cancel");

        // 暂停 AsyncExecutor：首节点为 async ServiceTask，启动后 Job 排队不执行，
        // 实例停留在"运行中"等待态，取消操作具备确定性（无 Job 线程与 H2 锁竞争）
        AsyncExecutor asyncExecutor = engine.getProcessEngineConfiguration().getAsyncExecutor();
        asyncExecutor.shutdown();
        try {
            ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                    processKey, Map.of("goal", "取消目标"));

            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instance.getId()).count())
                    .as("启动后运行时实例应存在").isEqualTo(1);
            assertThat(managementService.createJobQuery()
                    .processInstanceId(instance.getId()).count())
                    .as("async 节点的 Job 应处于排队状态").isEqualTo(1);
            assertThat(delegate.order).as("节点尚未真正执行").isEmpty();

            runtimeService.deleteProcessInstance(instance.getId(), "测试取消");

            assertThat(runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instance.getId()).count())
                    .as("取消后运行时实例应不存在").isZero();
            HistoricProcessInstance hist = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(instance.getId()).singleResult();
            assertThat(hist.getDeleteReason()).contains("测试取消");
        } finally {
            asyncExecutor.start(); // 恢复执行器，避免影响其他用例
        }
    }

    @Test
    @DisplayName("失败：节点抛异常→重试耗尽进入死信队列，流程实例保持未完成")
    void shouldMoveJobToDeadLetterWhenNodeFails() {
        WorkflowJsonDefinition def = def("会失败", node("boom", "必炸 ${goal}"));
        String processKey = deploy(def, "wf_it_fail");

        delegate.failNode("boom");
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                processKey, Map.of("goal", "失败目标"));

        waitUntil(() -> managementService.createDeadLetterJobQuery()
                        .processInstanceId(instance.getId()).count() > 0,
                "失败 Job 重试耗尽后应进入死信队列");

        // 节点被执行过（引擎重试次数已配置为 1，执行 1 次即进入死信）
        assertThat(delegate.order).contains("boom");
        // 流程未完成：运行时实例仍存在
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isEqualTo(1);
        assertThat(isFinished(instance.getId())).isFalse();
    }

    @Test
    @DisplayName("部署：converter 产物可被 Flowable 引擎部署且流程定义可查询")
    void shouldDeployConverterOutputSuccessfully() {
        WorkflowJsonDefinition def = def("部署检查",
                node("a", "步骤A ${goal}"),
                node("b", "步骤B ${a.output}", "a"));
        String processKey = deploy(def, "wf_it_deploy");

        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(processKey).count()).isEqualTo(1);
    }

    // ─────────────────── 辅助 ───────────────────

    private static WorkflowJsonNode node(String id, String promptTemplate, String... deps) {
        WorkflowJsonNode n = new WorkflowJsonNode();
        n.setId(id);
        n.setName("节点-" + id);
        n.setAgentId(1L);
        n.setPromptTemplate(promptTemplate);
        n.setTimeoutSeconds(60);
        n.setDependsOn(new ArrayList<>(List.of(deps)));
        return n;
    }

    private static WorkflowJsonDefinition def(String name, WorkflowJsonNode... nodes) {
        WorkflowJsonDefinition d = new WorkflowJsonDefinition();
        d.setName(name);
        d.setNodes(new ArrayList<>(List.of(nodes)));
        return d;
    }

    /** 校验 → 转换 → 部署，返回 processKey */
    private String deploy(WorkflowJsonDefinition def, String processKey) {
        List<String> errors = converter.validate(def, AGENTS);
        assertThat(errors).as("测试用定义应通过校验").isEmpty();
        BpmnModel model = converter.convert(def, processKey, 999L);
        repositoryService.createDeployment()
                .name(def.getName())
                .addBpmnModel(processKey + ".bpmn20.xml", model)
                .deploy();
        return processKey;
    }

    private boolean isFinished(String processInstanceId) {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .count() > 0;
    }

    private void waitUntil(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断", e);
            }
        }
        Assertions.fail(message + "（超时 " + WAIT_TIMEOUT_MS + "ms）");
    }

    // ─────────────────── 测试假 Delegate ───────────────────

    /**
     * 测试专用假 Delegate（模拟生产 AgentTaskDelegate，不调真实 LLM）：
     * 按生产方式从 ServiceTask FieldExtension 读取 nodeId，记录执行顺序、
     * 快照上游 *_output 变量（验证变量传递），并写入 {@code <nodeId>_output = OUT_<nodeId>}。
     */
    static class RecordingDelegate implements JavaDelegate {

        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Map<String, Object>> observedOutputs = new ConcurrentHashMap<>();
        private final Set<String> failNodes = ConcurrentHashMap.newKeySet();

        @Override
        public void execute(DelegateExecution execution) {
            String nodeId = readNodeId(execution);
            order.add(nodeId);

            Map<String, Object> outputs = new HashMap<>();
            execution.getVariables().forEach((k, v) -> {
                if (k.endsWith("_output")) {
                    outputs.put(k, v);
                }
            });
            observedOutputs.put(nodeId, outputs);

            if (failNodes.contains(nodeId)) {
                throw new RuntimeException("模拟节点失败: " + nodeId);
            }
            execution.setVariable(nodeId + "_output", "OUT_" + nodeId);
        }

        void failNode(String nodeId) { failNodes.add(nodeId); }

        void reset() {
            order.clear();
            observedOutputs.clear();
            failNodes.clear();
        }

        /** 与生产 AgentTaskDelegate 相同方式读取 FieldExtension（线程安全，不用字段注入） */
        private String readNodeId(DelegateExecution execution) {
            FlowElement element = execution.getCurrentFlowElement();
            if (element instanceof ServiceTask serviceTask) {
                for (FieldExtension field : serviceTask.getFieldExtensions()) {
                    if ("nodeId".equals(field.getFieldName())) {
                        return field.getStringValue();
                    }
                }
            }
            throw new IllegalStateException("ServiceTask 缺少 nodeId 字段: " + execution.getCurrentActivityId());
        }
    }
}
