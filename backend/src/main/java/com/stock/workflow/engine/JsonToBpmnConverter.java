package com.stock.workflow.engine;

import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 简化工作流 JSON → Flowable BpmnModel 转换器。
 * <p>
 * 结构规则：
 * <ul>
 *   <li>StartEvent → 所有入度为 0 的节点（多个根节点时先插分叉 ParallelGateway）</li>
 *   <li>每个 JSON 节点 → 一个异步 ServiceTask（delegateExpression = ${agentTaskDelegate}）</li>
 *   <li>入度 &gt; 1 的节点前插汇聚 ParallelGateway；出度 &gt; 1 的节点后插分叉 ParallelGateway</li>
 *   <li>所有出度为 0 的节点 →（多个则先汇聚 ParallelGateway）→ EndEvent</li>
 * </ul>
 * <p>
 * <b>关于 promptTemplate 的安全处理</b>：promptTemplate 中含 ${...} 占位符，若写入
 * ServiceTask FieldExtension，Flowable 表达式引擎在读取 Field 时会把 ${} 当作 JUEL
 * 表达式解析导致报错或误替换。因此 FieldExtension 中<b>只写入 nodeId 与 workflowDefId</b>
 * 两个纯文本字段，promptTemplate / agentId / timeoutSeconds / nodeName 等节点配置由
 * {@link AgentTaskDelegate} 运行时按 nodeId 从 WorkflowDef.definitionJson 反查。
 */
@Component
public class JsonToBpmnConverter {

    /** ServiceTask 元素 id 前缀（BPMN id 需以字母/下划线开头，加前缀保证合法） */
    public static final String TASK_ID_PREFIX = "task_";

    // ════════════════════════════════ 校验 ════════════════════════════════

    /**
     * 校验简化工作流定义。
     *
     * @param def              工作流 JSON 定义
     * @param existingAgentIds 数据库中实际存在的 AgentDef id 集合
     * @return 错误明细列表（为空表示校验通过）
     */
    public List<String> validate(WorkflowJsonDefinition def, Set<Long> existingAgentIds) {
        List<String> errors = new ArrayList<>();
        if (def == null || def.getNodes() == null || def.getNodes().isEmpty()) {
            errors.add("工作流定义为空：至少需要一个节点");
            return errors;
        }

        List<WorkflowJsonNode> nodes = def.getNodes();

        // 1. 节点 id 唯一 + 非空 + BPMN id 合法性
        Set<String> ids = new HashSet<>();
        for (WorkflowJsonNode node : nodes) {
            String id = node.getId();
            if (id == null || id.isBlank()) {
                errors.add("存在节点缺少 id");
                continue;
            }
            if (!id.matches("[A-Za-z0-9_\\-]+")) {
                errors.add("节点 id [" + id + "] 含非法字符（仅允许字母/数字/下划线/中划线）");
            }
            if (!ids.add(id)) {
                errors.add("节点 id 重复: " + id);
            }
        }
        if (!errors.isEmpty()) {
            return errors; // id 层面有问题时后续图检查无意义
        }

        // 2. dependsOn 引用存在性 + 自依赖
        for (WorkflowJsonNode node : nodes) {
            for (String dep : safeDeps(node)) {
                if (!ids.contains(dep)) {
                    errors.add("节点 [" + node.getId() + "] 的 dependsOn 引用了不存在的节点: " + dep);
                } else if (dep.equals(node.getId())) {
                    errors.add("节点 [" + node.getId() + "] 不能依赖自身");
                }
            }
        }

        // 3. agentId 存在性
        for (WorkflowJsonNode node : nodes) {
            if (node.getAgentId() == null) {
                errors.add("节点 [" + node.getId() + "] 缺少 agentId");
            } else if (existingAgentIds == null || !existingAgentIds.contains(node.getAgentId())) {
                errors.add("节点 [" + node.getId() + "] 引用的 agentId=" + node.getAgentId() + " 不存在");
            }
        }

        // 4. 环检测（Kahn 拓扑排序）
        if (errors.isEmpty()) {
            Map<String, Integer> inDegree = new HashMap<>();
            Map<String, List<String>> successors = new HashMap<>();
            for (WorkflowJsonNode node : nodes) {
                inDegree.putIfAbsent(node.getId(), 0);
                for (String dep : safeDeps(node)) {
                    inDegree.merge(node.getId(), 1, Integer::sum);
                    successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.getId());
                }
            }
            Deque<String> queue = new ArrayDeque<>();
            inDegree.forEach((k, v) -> { if (v == 0) queue.add(k); });
            int visited = 0;
            Map<String, Integer> degree = new HashMap<>(inDegree);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                visited++;
                for (String next : successors.getOrDefault(cur, List.of())) {
                    if (degree.merge(next, -1, Integer::sum) == 0) {
                        queue.add(next);
                    }
                }
            }
            if (visited != nodes.size()) {
                List<String> cycleNodes = degree.entrySet().stream()
                        .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).sorted().toList();
                errors.add("工作流存在循环依赖，涉及节点: " + cycleNodes);
            }
        }

        // 5. 孤立断链检测：多节点工作流中，完全无依赖关系（既无 dependsOn 也无被依赖）的节点视为孤立
        if (errors.isEmpty() && nodes.size() > 1) {
            Set<String> connected = new HashSet<>();
            for (WorkflowJsonNode node : nodes) {
                List<String> deps = safeDeps(node);
                if (!deps.isEmpty()) {
                    connected.add(node.getId());
                    connected.addAll(deps);
                }
            }
            for (WorkflowJsonNode node : nodes) {
                if (!connected.contains(node.getId())) {
                    errors.add("节点 [" + node.getId() + "] 为孤立节点（与其他节点无任何依赖关系），请检查是否断链");
                }
            }
        }

        return errors;
    }

    // ════════════════════════════════ 转换 ════════════════════════════════

    /**
     * 转换为 BpmnModel。
     *
     * @param def           工作流 JSON 定义（应先通过 validate）
     * @param processKey    流程定义 key（"wf_" + workflowDefId）
     * @param workflowDefId 工作流定义主键（写入每个 ServiceTask 的 FieldExtension，
     *                      供 Delegate 运行时反查节点配置）
     */
    public BpmnModel convert(WorkflowJsonDefinition def, String processKey, Long workflowDefId) {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId(processKey);
        process.setName(def.getName() != null ? def.getName() : processKey);
        process.setExecutable(true);
        model.addProcess(process);

        List<WorkflowJsonNode> nodes = def.getNodes();

        // ── 图结构 ──
        Map<String, List<String>> successors = new LinkedHashMap<>();
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        for (WorkflowJsonNode node : nodes) {
            successors.putIfAbsent(node.getId(), new ArrayList<>());
            predecessors.putIfAbsent(node.getId(), new ArrayList<>());
        }
        for (WorkflowJsonNode node : nodes) {
            for (String dep : safeDeps(node)) {
                successors.get(dep).add(node.getId());
                predecessors.get(node.getId()).add(dep);
            }
        }

        // ── Start / End ──
        StartEvent startEvent = new StartEvent();
        startEvent.setId("startEvent");
        process.addFlowElement(startEvent);

        EndEvent endEvent = new EndEvent();
        endEvent.setId("endEvent");
        process.addFlowElement(endEvent);

        // ── ServiceTask ──
        for (WorkflowJsonNode node : nodes) {
            process.addFlowElement(buildServiceTask(node, workflowDefId));
        }

        // ── 网关（入度>1 前插汇聚；出度>1 后插分叉）──
        Map<String, String> entryPoint = new HashMap<>(); // nodeId → 该节点的入口元素 id
        Map<String, String> exitPoint = new HashMap<>();  // nodeId → 该节点的出口元素 id
        for (WorkflowJsonNode node : nodes) {
            String taskId = TASK_ID_PREFIX + node.getId();
            entryPoint.put(node.getId(), taskId);
            exitPoint.put(node.getId(), taskId);

            if (predecessors.get(node.getId()).size() > 1) {
                ParallelGateway join = new ParallelGateway();
                join.setId("join_" + node.getId());
                process.addFlowElement(join);
                entryPoint.put(node.getId(), join.getId());
            }
            if (successors.get(node.getId()).size() > 1) {
                ParallelGateway fork = new ParallelGateway();
                fork.setId("fork_" + node.getId());
                process.addFlowElement(fork);
                exitPoint.put(node.getId(), fork.getId());
            }
        }

        int[] flowSeq = {0};

        // 网关与节点自身的连线
        for (WorkflowJsonNode node : nodes) {
            String taskId = TASK_ID_PREFIX + node.getId();
            String entry = entryPoint.get(node.getId());
            String exit = exitPoint.get(node.getId());
            if (!entry.equals(taskId)) {
                addFlow(process, flowSeq, entry, taskId); // join → task
            }
            if (!exit.equals(taskId)) {
                addFlow(process, flowSeq, taskId, exit);  // task → fork
            }
        }

        // DAG 依赖边：exit(前驱) → entry(后继)
        for (WorkflowJsonNode node : nodes) {
            for (String dep : safeDeps(node)) {
                addFlow(process, flowSeq, exitPoint.get(dep), entryPoint.get(node.getId()));
            }
        }

        // Start → 入度 0 节点
        List<String> roots = nodes.stream()
                .map(WorkflowJsonNode::getId)
                .filter(id -> predecessors.get(id).isEmpty())
                .toList();
        if (roots.size() > 1) {
            ParallelGateway startFork = new ParallelGateway();
            startFork.setId("fork_start");
            process.addFlowElement(startFork);
            addFlow(process, flowSeq, startEvent.getId(), startFork.getId());
            for (String root : roots) {
                addFlow(process, flowSeq, startFork.getId(), entryPoint.get(root));
            }
        } else if (roots.size() == 1) {
            addFlow(process, flowSeq, startEvent.getId(), entryPoint.get(roots.get(0)));
        }

        // 出度 0 节点 → End
        List<String> leaves = nodes.stream()
                .map(WorkflowJsonNode::getId)
                .filter(id -> successors.get(id).isEmpty())
                .toList();
        if (leaves.size() > 1) {
            ParallelGateway endJoin = new ParallelGateway();
            endJoin.setId("join_end");
            process.addFlowElement(endJoin);
            for (String leaf : leaves) {
                addFlow(process, flowSeq, exitPoint.get(leaf), endJoin.getId());
            }
            addFlow(process, flowSeq, endJoin.getId(), endEvent.getId());
        } else if (leaves.size() == 1) {
            addFlow(process, flowSeq, exitPoint.get(leaves.get(0)), endEvent.getId());
        }

        return model;
    }

    // ════════════════════════════════ 内部 ════════════════════════════════

    private ServiceTask buildServiceTask(WorkflowJsonNode node, Long workflowDefId) {
        ServiceTask task = new ServiceTask();
        task.setId(TASK_ID_PREFIX + node.getId());
        task.setName(node.getName() != null ? node.getName() : node.getId());
        task.setImplementationType(ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION);
        task.setImplementation("${agentTaskDelegate}");
        task.setAsynchronous(true);

        // 仅写入纯文本字段，promptTemplate 等含 ${} 的配置由 Delegate 运行时从 DB 反查
        FieldExtension nodeIdField = new FieldExtension();
        nodeIdField.setFieldName("nodeId");
        nodeIdField.setStringValue(node.getId());
        task.getFieldExtensions().add(nodeIdField);

        FieldExtension defIdField = new FieldExtension();
        defIdField.setFieldName("workflowDefId");
        defIdField.setStringValue(String.valueOf(workflowDefId));
        task.getFieldExtensions().add(defIdField);

        return task;
    }

    private void addFlow(Process process, int[] seq, String sourceRef, String targetRef) {
        SequenceFlow flow = new SequenceFlow(sourceRef, targetRef);
        flow.setId("flow_" + (++seq[0]));
        process.addFlowElement(flow);
    }

    private List<String> safeDeps(WorkflowJsonNode node) {
        return node.getDependsOn() != null ? node.getDependsOn() : List.of();
    }
}
