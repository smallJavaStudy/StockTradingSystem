package com.stock.workflow.controller;

import com.stock.workflow.engine.WorkflowDeployService;
import com.stock.workflow.engine.WorkflowRunService;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.WorkflowDefRepository;
import com.stock.workflow.service.WorkflowGenerationResult;
import com.stock.workflow.service.WorkflowGeneratorService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 工作流定义管理：CRUD / 发布 / AI 生成 / AI 编辑 / 执行。
 * 返回风格与现有 AnalysisAgentController 保持一致：直接返回实体或 Map，异常交由
 * {@link WorkflowControllerAdvice} 统一转 4xx。CORS 由 StockApplication 全局 /api/** 配置覆盖。
 */
@RestController
@RequestMapping("/api/v1/workflow")
public class WorkflowController {

    private final WorkflowDefRepository workflowDefRepo;
    private final WorkflowDeployService deployService;
    private final WorkflowRunService runService;
    private final WorkflowGeneratorService generatorService;

    public WorkflowController(WorkflowDefRepository workflowDefRepo,
                              WorkflowDeployService deployService,
                              WorkflowRunService runService,
                              WorkflowGeneratorService generatorService) {
        this.workflowDefRepo = workflowDefRepo;
        this.deployService = deployService;
        this.runService = runService;
        this.generatorService = generatorService;
    }

    /** 全部工作流，按 updatedAt 倒序 */
    @GetMapping("/list")
    public List<WorkflowDef> list() {
        return workflowDefRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    @GetMapping("/{id}")
    public WorkflowDef get(@PathVariable Long id) {
        return workflowDefRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("工作流定义不存在: id=" + id));
    }

    /** 创建工作流（DRAFT，createdBy=USER） */
    @PostMapping("/create")
    public WorkflowDef create(@RequestBody Map<String, Object> body) {
        String name = asText(body.get("name"));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (workflowDefRepo.findByName(name).isPresent()) {
            throw new IllegalArgumentException("工作流名称已存在: " + name);
        }
        WorkflowDef def = new WorkflowDef();
        def.setName(name);
        def.setDescription(asText(body.get("description")));
        def.setCategory(body.get("category") != null ? asText(body.get("category")) : "CUSTOM");
        def.setDefinitionJson(asText(body.get("definitionJson")));
        def.setStatus("DRAFT");
        def.setCreatedBy("USER");
        def.setVersion(0);
        return workflowDefRepo.save(def);
    }

    /** 更新工作流；原 PUBLISHED 置回 DRAFT（需重新发布） */
    @PutMapping("/{id}")
    public WorkflowDef update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        WorkflowDef def = workflowDefRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("工作流定义不存在: id=" + id));

        String name = asText(body.get("name"));
        if (name != null && !name.isBlank() && !name.equals(def.getName())) {
            if (workflowDefRepo.findByName(name).isPresent()) {
                throw new IllegalArgumentException("工作流名称已存在: " + name);
            }
            def.setName(name);
        }
        if (body.containsKey("description")) {
            def.setDescription(asText(body.get("description")));
        }
        if (body.containsKey("category")) {
            def.setCategory(asText(body.get("category")));
        }
        if (body.containsKey("definitionJson")) {
            def.setDefinitionJson(asText(body.get("definitionJson")));
        }
        if ("PUBLISHED".equals(def.getStatus())) {
            def.setStatus("DRAFT");
        }
        return workflowDefRepo.save(def);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        WorkflowDef def = workflowDefRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("工作流定义不存在: id=" + id));
        workflowDefRepo.delete(def);
        return Map.of("deleted", true, "id", id);
    }

    /** 发布：简化 JSON → 校验 → BPMN → Flowable 部署，status=PUBLISHED */
    @PostMapping("/{id}/publish")
    public WorkflowDef publish(@PathVariable Long id) {
        return deployService.deploy(id);
    }

    /** AI 生成工作流（body: {description}），失败自动重试一次，仍失败回退内置模板（fallback=true） */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body) {
        String description = body.get("description");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        WorkflowGenerationResult result = generatorService.generate(description);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("workflow", result.getWorkflow());
        resp.put("fallback", result.isFallback());
        resp.put("message", result.getMessage());
        return resp;
    }

    /** AI 编辑工作流（body: {instruction}），原 PUBLISHED 置回 DRAFT */
    @PostMapping("/{id}/ai-edit")
    public WorkflowDef aiEdit(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String instruction = body.get("instruction");
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction is required");
        }
        return generatorService.aiEdit(id, instruction);
    }

    /** 执行工作流（body: {input: {k:v}}，需 PUBLISHED），返回 {processInstanceId} */
    @PostMapping("/{id}/execute")
    public Map<String, String> execute(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> input = body != null && body.get("input") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String processInstanceId = runService.start(id, input);
        return Map.of("processInstanceId", processInstanceId);
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
