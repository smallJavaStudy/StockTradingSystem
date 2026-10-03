package com.stock.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.workflow.entity.AgentDef;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.AgentDefRepository;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.validation.ProcessValidator;
import org.flowable.validation.ProcessValidatorFactory;
import org.flowable.validation.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流部署服务：简化 JSON → 校验 → BpmnModel → Flowable 部署 → 回写元数据。
 */
@Service
public class WorkflowDeployService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDeployService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowDefRepository workflowDefRepo;
    private final AgentDefRepository agentDefRepo;
    private final JsonToBpmnConverter converter;
    private final RepositoryService repositoryService;

    public WorkflowDeployService(WorkflowDefRepository workflowDefRepo,
                                 AgentDefRepository agentDefRepo,
                                 JsonToBpmnConverter converter,
                                 RepositoryService repositoryService) {
        this.workflowDefRepo = workflowDefRepo;
        this.agentDefRepo = agentDefRepo;
        this.converter = converter;
        this.repositoryService = repositoryService;
    }

    /**
     * 部署工作流：成功后回写 processDefinitionKey / deploymentId / status=PUBLISHED / version+1。
     *
     * @return 部署后的 WorkflowDef
     * @throws IllegalArgumentException 校验失败（消息含错误明细）
     */
    @Transactional
    public WorkflowDef deploy(Long workflowDefId) {
        WorkflowDef def = workflowDefRepo.findById(workflowDefId)
                .orElseThrow(() -> new NoSuchElementException("工作流定义不存在: id=" + workflowDefId));
        if (def.getDefinitionJson() == null || def.getDefinitionJson().isBlank()) {
            throw new IllegalArgumentException("工作流 [" + def.getName() + "] 的 definitionJson 为空，无法部署");
        }

        // 1. 反序列化
        WorkflowJsonDefinition jsonDef;
        try {
            jsonDef = MAPPER.readValue(def.getDefinitionJson(), WorkflowJsonDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("definitionJson 解析失败: " + e.getMessage(), e);
        }

        // 2. 业务校验（环 / 引用 / agentId / 孤立节点）
        Set<Long> referencedAgentIds = jsonDef.getNodes() == null ? Set.of()
                : jsonDef.getNodes().stream()
                        .map(WorkflowJsonNode::getAgentId)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());
        Set<Long> existingAgentIds = new HashSet<>();
        agentDefRepo.findAllById(referencedAgentIds)
                .forEach(a -> existingAgentIds.add(((AgentDef) a).getId()));

        List<String> errors = converter.validate(jsonDef, existingAgentIds);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("工作流校验失败: " + String.join("; ", errors));
        }

        // 3. 转换 BpmnModel
        String processKey = "wf_" + def.getId();
        BpmnModel bpmnModel = converter.convert(jsonDef, processKey, def.getId());

        // 4. Flowable 结构校验
        ProcessValidator processValidator = new ProcessValidatorFactory().createDefaultProcessValidator();
        List<ValidationError> validationErrors = processValidator.validate(bpmnModel);
        List<String> fatal = validationErrors.stream()
                .filter(e -> !e.isWarning())
                .map(e -> "[" + e.getActivityId() + "] " + e.getProblem() + ": " + e.getDefaultDescription())
                .toList();
        if (!fatal.isEmpty()) {
            throw new IllegalArgumentException("BPMN 模型校验失败: " + String.join("; ", fatal));
        }

        // 5. 部署
        Deployment deployment = repositoryService.createDeployment()
                .name(def.getName())
                .addBpmnModel(processKey + ".bpmn20.xml", bpmnModel)
                .deploy();

        // 6. 回写元数据
        def.setProcessDefinitionKey(processKey);
        def.setDeploymentId(deployment.getId());
        def.setStatus("PUBLISHED");
        def.setVersion(def.getVersion() == null ? 1 : def.getVersion() + 1);
        WorkflowDef saved = workflowDefRepo.save(def);

        log.info("工作流部署成功: id={}, name={}, processKey={}, deploymentId={}, version={}",
                saved.getId(), saved.getName(), processKey, deployment.getId(), saved.getVersion());
        return saved;
    }
}
