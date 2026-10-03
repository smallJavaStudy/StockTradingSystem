package com.stock.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.workflow.engine.agent.AgentScopeAgentRunner;
import com.stock.workflow.engine.agent.PromptAgentRunner;
import com.stock.workflow.entity.AgentDef;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.AgentDefRepository;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.flowable.bpmn.model.FieldExtension;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 节点执行委托（delegateExpression = ${agentTaskDelegate}）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>FieldExtension 只携带 nodeId / workflowDefId 两个纯文本字段；promptTemplate 等
 *       含 ${} 的节点配置运行时按 nodeId 从 WorkflowDef.definitionJson 反查，避免
 *       Flowable 表达式引擎误解析占位符。</li>
 *   <li>本类为 Spring 单例，FieldExtension 通过 execution.getCurrentFlowElement()
 *       读取（而非字段注入），线程安全。</li>
 *   <li>RunLog 写入走 {@link WorkflowRunLogWriter} 的 REQUIRES_NEW 独立事务，
 *       防止节点失败时流程事务回滚吞掉日志。</li>
 * </ul>
 */
@Component("agentTaskDelegate")
public class AgentTaskDelegate implements JavaDelegate {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskDelegate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int SSE_OUTPUT_TRUNCATE_CHARS = WorkflowTextUtils.OUTPUT_TRUNCATE_CHARS;
    /** ${xxx.output} 或 ${xxx} 占位符 */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{\\s*([A-Za-z0-9_\\-]+)(\\.output)?\\s*}");

    private final WorkflowDefRepository workflowDefRepo;
    private final AgentDefRepository agentDefRepo;
    private final WorkflowRunLogWriter runLogWriter;
    private final WorkflowSseService sseService;
    private final PromptAgentRunner promptAgentRunner;
    private final AgentScopeAgentRunner agentScopeAgentRunner;

    public AgentTaskDelegate(WorkflowDefRepository workflowDefRepo,
                             AgentDefRepository agentDefRepo,
                             WorkflowRunLogWriter runLogWriter,
                             WorkflowSseService sseService,
                             PromptAgentRunner promptAgentRunner,
                             AgentScopeAgentRunner agentScopeAgentRunner) {
        this.workflowDefRepo = workflowDefRepo;
        this.agentDefRepo = agentDefRepo;
        this.runLogWriter = runLogWriter;
        this.sseService = sseService;
        this.promptAgentRunner = promptAgentRunner;
        this.agentScopeAgentRunner = agentScopeAgentRunner;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();

        // ── 1. 读取 FieldExtension（nodeId / workflowDefId）──
        String nodeId = readField(execution, "nodeId");
        Long workflowDefId = parseLong(readField(execution, "workflowDefId"));
        if (workflowDefId == null) {
            // 兜底：启动时 WorkflowRunService 已将 workflowDefId 放入流程变量
            Object var = execution.getVariable("workflowDefId");
            workflowDefId = var instanceof Number n ? n.longValue() : parseLong(String.valueOf(var));
        }
        if (nodeId == null || workflowDefId == null) {
            throw new IllegalStateException("ServiceTask 缺少 nodeId/workflowDefId 字段: activityId="
                    + execution.getCurrentActivityId());
        }

        // ── 2. 从 WorkflowDef.definitionJson 反查节点配置 ──
        WorkflowJsonNode node = loadNodeConfig(workflowDefId, nodeId);
        AgentDef agentDef = agentDefRepo.findById(node.getAgentId())
                .orElseThrow(() -> new NoSuchElementException(
                        "节点 [" + nodeId + "] 引用的 AgentDef 不存在: id=" + node.getAgentId()));
        String nodeName = node.getName() != null ? node.getName() : nodeId;
        int timeoutSeconds = node.getTimeoutSeconds() != null && node.getTimeoutSeconds() > 0
                ? node.getTimeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;

        // ── 3. 组装输入：promptTemplate 占位符插值 ──
        String input = interpolate(node.getPromptTemplate(), execution, nodeId);

        // ── 4. RunLog(RUNNING) + SSE NODE_STARTED ──
        Long runLogId = runLogWriter.logStart(workflowDefId, processInstanceId,
                nodeId, nodeName, agentDef.getId(), input);
        pushSafely(processInstanceId,
                WorkflowEvent.nodeEvent(WorkflowEvent.NODE_STARTED, processInstanceId, nodeId, nodeName));

        try {
            // ── 5. 按类型分发执行 ──
            String type = agentDef.getType() != null ? agentDef.getType().toUpperCase() : "PROMPT";
            String rawOutput = switch (type) {
                case "AGENTSCOPE" -> agentScopeAgentRunner.run(agentDef, input, timeoutSeconds,
                        delta -> pushSafely(processInstanceId,
                                WorkflowEvent.nodeEvent(WorkflowEvent.NODE_DELTA,
                                        processInstanceId, nodeId, nodeName).withDelta(delta)));
                case "PROMPT" -> promptAgentRunner.run(agentDef, input, timeoutSeconds);
                default -> throw new IllegalStateException(
                        "未知的 AgentDef.type: " + agentDef.getType() + "（支持 PROMPT / AGENTSCOPE）");
            };

            // ── 6. 输出写流程变量 + RunLog(COMPLETED) + SSE NODE_COMPLETED ──
            // 入库路径统一净化非 BMP 字符（emoji），防止 MySQL utf8 三字节写 ACT_HI_VARINST 失败
            String output = WorkflowTextUtils.stripNonBmp(rawOutput);
            execution.setVariable(nodeId + "_output", output);
            runLogWriter.logComplete(runLogId, output);
            pushSafely(processInstanceId,
                    WorkflowEvent.nodeEvent(WorkflowEvent.NODE_COMPLETED, processInstanceId, nodeId, nodeName)
                            .withOutput(truncateForSse(output)));
            log.info("节点执行成功: processInstanceId={}, nodeId={}, outputLen={}",
                    processInstanceId, nodeId, output.length());

        } catch (Exception e) {
            // ── 7. 失败：RunLog(FAILED) + SSE NODE_FAILED 后原样抛出（使流程失败）──
            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("节点执行失败: processInstanceId={}, nodeId={}, error={}",
                    processInstanceId, nodeId, errorMessage);
            runLogWriter.logFail(runLogId, errorMessage);
            pushSafely(processInstanceId,
                    WorkflowEvent.nodeEvent(WorkflowEvent.NODE_FAILED, processInstanceId, nodeId, nodeName)
                            .withError(errorMessage));
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(e);
        }
    }

    // ────────── 内部 ──────────

    /** 从当前 ServiceTask 的 FieldExtension 读取纯文本字段（单例线程安全，不使用字段注入） */
    private String readField(DelegateExecution execution, String fieldName) {
        FlowElement element = execution.getCurrentFlowElement();
        if (element instanceof ServiceTask serviceTask) {
            for (FieldExtension field : serviceTask.getFieldExtensions()) {
                if (fieldName.equals(field.getFieldName())) {
                    return field.getStringValue();
                }
            }
        }
        return null;
    }

    private WorkflowJsonNode loadNodeConfig(Long workflowDefId, String nodeId) {
        WorkflowDef def = workflowDefRepo.findById(workflowDefId)
                .orElseThrow(() -> new NoSuchElementException("工作流定义不存在: id=" + workflowDefId));
        try {
            WorkflowJsonDefinition jsonDef =
                    MAPPER.readValue(def.getDefinitionJson(), WorkflowJsonDefinition.class);
            return jsonDef.getNodes().stream()
                    .filter(n -> nodeId.equals(n.getId()))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException(
                            "definitionJson 中不存在节点: " + nodeId + " (workflowDefId=" + workflowDefId + ")"));
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("definitionJson 解析失败: workflowDefId=" + workflowDefId, e);
        }
    }

    /**
     * 占位符插值：${xxx.output} → 流程变量 xxx_output；${其他} → 同名流程变量。
     * 缺失变量替换为空串并记警告。
     */
    private String interpolate(String template, DelegateExecution execution, String nodeId) {
        if (template == null || template.isBlank()) {
            log.warn("节点 [{}] 的 promptTemplate 为空", nodeId);
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(2) != null
                    ? matcher.group(1) + "_output"  // ${n1.output} → n1_output
                    : matcher.group(1);             // ${goal} → goal
            Object value = execution.getVariable(varName);
            if (value == null) {
                log.warn("节点 [{}] promptTemplate 占位符 ${{{}}} 对应的流程变量 [{}] 不存在，已替换为空串",
                        nodeId, matcher.group(0).substring(2, matcher.group(0).length() - 1), varName);
                value = "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** SSE 推送不能影响节点执行主流程 */
    private void pushSafely(String processInstanceId, WorkflowEvent event) {
        try {
            sseService.push(processInstanceId, event);
        } catch (Exception e) {
            log.warn("SSE 推送失败（不影响执行）: {}", e.getMessage());
        }
    }

    private String truncateForSse(String output) {
        return WorkflowTextUtils.truncate(output, SSE_OUTPUT_TRUNCATE_CHARS);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
