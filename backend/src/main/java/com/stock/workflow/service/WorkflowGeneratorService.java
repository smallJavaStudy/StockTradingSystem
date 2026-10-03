package com.stock.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.workflow.engine.JsonToBpmnConverter;
import com.stock.workflow.engine.WorkflowJsonDefinition;
import com.stock.workflow.engine.WorkflowJsonNode;
import com.stock.workflow.engine.agent.PromptAgentRunner;
import com.stock.workflow.entity.AgentDef;
import com.stock.workflow.entity.WorkflowDef;
import com.stock.workflow.repository.AgentDefRepository;
import com.stock.workflow.repository.WorkflowDefRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工作流 AI 生成服务：
 * <ul>
 *   <li>{@link #generate(String)}：自然语言描述 → LLM 生成简化 JSON → 三重校验 → 保存为 DRAFT 工作流；
 *       重试仍失败时回退内置模板（fallback=true）。</li>
 *   <li>{@link #aiEdit(Long, String)}：现有 definitionJson + 修改指令 → LLM 输出修改后完整 JSON →
 *       三重校验 → 更新（原 PUBLISHED 置回 DRAFT）。</li>
 * </ul>
 * LLM 调用复用 {@link PromptAgentRunner}（非流式 deepseek-v4，配置来自 application.yml agent.model.deepseek-v4），
 * 通过构造临时 AgentDef（不落库）传入模型参数。
 */
@Service
public class WorkflowGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGeneratorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int LLM_TIMEOUT_SECONDS = 180;
    private static final Set<String> KNOWN_CATEGORIES = Set.of("DEV_PROCESS", "STOCK_ANALYSIS", "CUSTOM");

    private final WorkflowDefRepository workflowDefRepo;
    private final AgentDefRepository agentDefRepo;
    private final JsonToBpmnConverter converter;
    private final PromptAgentRunner promptAgentRunner;

    public WorkflowGeneratorService(WorkflowDefRepository workflowDefRepo,
                                    AgentDefRepository agentDefRepo,
                                    JsonToBpmnConverter converter,
                                    PromptAgentRunner promptAgentRunner) {
        this.workflowDefRepo = workflowDefRepo;
        this.agentDefRepo = agentDefRepo;
        this.converter = converter;
        this.promptAgentRunner = promptAgentRunner;
    }

    // ════════════════════════════ generate ════════════════════════════

    /**
     * 根据自然语言描述生成工作流并保存为 DRAFT（createdBy=AGENT）。
     * LLM 两次尝试均失败时回退内置模板，结果中 fallback=true。
     */
    public WorkflowGenerationResult generate(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        List<AgentDef> agents = agentDefRepo.findAll();
        if (agents.isEmpty()) {
            throw new IllegalStateException("系统中尚无任何智能体定义，无法生成工作流，请先创建 AgentDef");
        }

        String userPrompt = buildGeneratePrompt(description, agents);
        LlmJsonResult result = callWithRetry(userPrompt);

        if (result != null) {
            WorkflowDef saved = saveGenerated(result, description, false, null);
            return new WorkflowGenerationResult(saved, false, "AI 生成成功");
        }

        // 回退内置模板
        log.warn("工作流 AI 生成两次均失败，回退内置模板: description={}", abbreviate(description, 100));
        LlmJsonResult fallback = buildFallbackTemplate(description, agents);
        WorkflowDef saved = saveGenerated(fallback, description, true, null);
        return new WorkflowGenerationResult(saved, true,
                "AI 生成失败（含一次自动重试），已回退到内置三节点模板（规划→执行→复核），请手动编辑完善");
    }

    // ════════════════════════════ aiEdit ════════════════════════════

    /**
     * AI 修改现有工作流定义：更新 definitionJson；原 PUBLISHED 置回 DRAFT（需重新发布）。
     */
    public WorkflowDef aiEdit(Long workflowDefId, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction 不能为空");
        }
        WorkflowDef def = workflowDefRepo.findById(workflowDefId)
                .orElseThrow(() -> new NoSuchElementException("工作流定义不存在: id=" + workflowDefId));
        if (def.getDefinitionJson() == null || def.getDefinitionJson().isBlank()) {
            throw new IllegalArgumentException("工作流 [" + def.getName() + "] 的 definitionJson 为空，无法 AI 编辑");
        }

        List<AgentDef> agents = agentDefRepo.findAll();
        String userPrompt = buildEditPrompt(def.getDefinitionJson(), instruction, agents);
        LlmJsonResult result = callWithRetry(userPrompt);
        if (result == null) {
            // 回退语义：两次重试均失败时不碰 def，直接抛异常，原 definitionJson 保持不变，
            // 非法 JSON 绝不会写入 DB（只有 parseAndValidate 通过的 normalizedJson 才会落库）。
            throw new IllegalArgumentException(
                    "AI 修改失败：已保留原工作流定义，模型输出未通过校验（JSON 解析/环检测/agentId 引用），请调整指令后重试");
        }

        def.setDefinitionJson(result.normalizedJson);
        if ("PUBLISHED".equals(def.getStatus())) {
            def.setStatus("DRAFT"); // 修改后需重新发布
        }
        WorkflowDef saved = workflowDefRepo.save(def);
        log.info("AI 编辑工作流成功: id={}, name={}, status={}", saved.getId(), saved.getName(), saved.getStatus());
        return saved;
    }

    // ════════════════════════════ LLM 调用与三重校验 ════════════════════════════

    /** 调 LLM → 剥围栏 → Jackson 反序列化 → converter.validate；失败带错误信息重试一次，仍失败返回 null */
    private LlmJsonResult callWithRetry(String userPrompt) {
        String lastError = null;
        String prompt = userPrompt;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw;
            try {
                raw = callLlm(prompt);
            } catch (Exception e) {
                lastError = "LLM 调用失败: " + e.getMessage();
                log.warn("工作流生成 LLM 调用失败(第{}次): {}", attempt, e.getMessage());
                prompt = userPrompt + "\n\n【上次尝试失败原因】" + lastError + "\n请重新只输出符合 Schema 的 JSON。";
                continue;
            }
            try {
                return parseAndValidate(raw);
            } catch (IllegalArgumentException e) {
                lastError = e.getMessage();
                log.warn("工作流生成校验失败(第{}次): {}", attempt, lastError);
                prompt = userPrompt + "\n\n【上次输出存在以下错误，请修正后重新只输出 JSON】\n" + lastError
                        + "\n\n【上次的错误输出】\n" + abbreviate(raw, 3000);
            }
        }
        return null;
    }

    /** 用临时 AgentDef 复用 PromptAgentRunner 的非流式调用范式 */
    private String callLlm(String userPrompt) {
        AgentDef temp = new AgentDef();
        temp.setName("workflow-generator");
        temp.setType("PROMPT");
        temp.setModelChoice("deepseek-v4");
        temp.setTemperature(0.2);
        temp.setMaxTokens(4096);
        temp.setSystemPrompt("你是一个专业的工作流编排专家，精通把用户需求转换为 DAG 结构的多智能体工作流 JSON。"
                + "编排原则：1) 把任务拆解为职责单一的多个节点（通常 3-9 个，至少包含规划→执行→复核三环节），"
                + "能并行的分析环节用并行依赖表达；2) 每个节点的 promptTemplate 必须是生产级结构化提示词，"
                + "包含【角色定位】【输入数据】【工作步骤（分步 CoT）】【输出格式（明确的 Markdown 章节结构）】"
                + "【质量红线（如：必须基于给定真实数据、引用具体数字、禁止编造数据、数据缺失时明说）】五部分；"
                + "3) 下游节点用 ${上游节点id.output} 引用上游输出。"
                + "你只输出 JSON，不输出任何解释、markdown 围栏或多余文字。");
        return promptAgentRunner.run(temp, userPrompt, LLM_TIMEOUT_SECONDS);
    }

    /** 三重校验：剥围栏 → Jackson 反序列化 → JsonToBpmnConverter.validate（环/引用/agentId） */
    private LlmJsonResult parseAndValidate(String rawOutput) {
        String json = stripCodeFence(rawOutput);

        JsonNode tree;
        WorkflowJsonDefinition jsonDef;
        try {
            tree = MAPPER.readTree(json);
            jsonDef = MAPPER.treeToValue(tree, WorkflowJsonDefinition.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败: " + e.getMessage());
        }

        Set<Long> referencedAgentIds = jsonDef.getNodes() == null ? Set.of()
                : jsonDef.getNodes().stream()
                        .map(WorkflowJsonNode::getAgentId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Set<Long> existingAgentIds = new HashSet<>();
        agentDefRepo.findAllById(referencedAgentIds).forEach(a -> existingAgentIds.add(a.getId()));

        List<String> errors = converter.validate(jsonDef, existingAgentIds);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("工作流校验失败: " + String.join("; ", errors));
        }

        LlmJsonResult result = new LlmJsonResult();
        result.definition = jsonDef;
        result.normalizedJson = tree.toPrettyString();
        result.category = tree.hasNonNull("category") ? tree.get("category").asText() : null;
        return result;
    }

    /** 剥离 markdown 代码围栏（```json ... ```），并截取首个 '{' 到末个 '}' 之间的内容 */
    static String stripCodeFence(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int fenceEnd = text.lastIndexOf("```");
            if (fenceEnd >= 0) {
                text = text.substring(0, fenceEnd);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text.trim();
    }

    // ════════════════════════════ 保存与回退 ════════════════════════════

    private WorkflowDef saveGenerated(LlmJsonResult result, String description,
                                      boolean fallback, String categoryOverride) {
        String category = categoryOverride != null ? categoryOverride
                : (result.category != null && KNOWN_CATEGORIES.contains(result.category.toUpperCase())
                        ? result.category.toUpperCase() : "CUSTOM");
        String name = result.definition.getName() != null && !result.definition.getName().isBlank()
                ? result.definition.getName()
                : "AI生成工作流-" + System.currentTimeMillis();

        WorkflowDef def = new WorkflowDef();
        def.setName(uniqueName(name));
        def.setDescription(abbreviate(description, 500));
        def.setCategory(category);
        def.setDefinitionJson(result.normalizedJson);
        def.setStatus("DRAFT");
        def.setCreatedBy("AGENT");
        def.setVersion(0);
        WorkflowDef saved = workflowDefRepo.save(def);
        log.info("AI 生成工作流已保存: id={}, name={}, category={}, fallback={}",
                saved.getId(), saved.getName(), category, fallback);
        return saved;
    }

    /** 同名冲突时追加序号，避免语义上的重复 */
    private String uniqueName(String name) {
        if (workflowDefRepo.findByName(name).isEmpty()) {
            return name;
        }
        for (int i = 2; i < 100; i++) {
            String candidate = name + "-" + i;
            if (workflowDefRepo.findByName(candidate).isEmpty()) {
                return candidate;
            }
        }
        return name + "-" + System.currentTimeMillis();
    }

    /** 内置回退模板：三节点串行工作流（规划→执行→复核），选第一个 PROMPT 型智能体（无则任意智能体） */
    private LlmJsonResult buildFallbackTemplate(String description, List<AgentDef> agents) {
        AgentDef agent = agents.stream()
                .filter(a -> "PROMPT".equalsIgnoreCase(a.getType()))
                .findFirst()
                .orElse(agents.get(0));
        try {
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("id", "plan");
            plan.put("name", "任务规划");
            plan.put("agentId", agent.getId());
            plan.put("promptTemplate", "【角色定位】你是任务规划专家，负责把目标拆解为可执行计划。\n"
                    + "【输入数据】任务目标：${goal}\n补充背景：" + description + "\n"
                    + "【工作步骤】1.理解目标与约束；2.拆解为 3-7 个有序子任务；3.为每个子任务定义完成标准。\n"
                    + "【输出格式】Markdown：## 目标理解 / ## 子任务清单（编号+描述+完成标准）/ ## 风险与假设。\n"
                    + "【质量红线】子任务必须可独立验证，禁止空泛表述；信息不足时列出假设。");
            plan.put("timeoutSeconds", 600);
            plan.put("dependsOn", List.of());

            Map<String, Object> execute = new LinkedHashMap<>();
            execute.put("id", "execute");
            execute.put("name", "任务执行");
            execute.put("agentId", agent.getId());
            execute.put("promptTemplate", "【角色定位】你是任务执行专家，负责按计划逐项完成任务并产出交付物。\n"
                    + "【输入数据】任务目标：${goal}\n执行计划：${plan.output}\n"
                    + "【工作步骤】1.按计划逐个子任务执行；2.每个子任务给出具体产出；3.记录偏离计划之处及原因。\n"
                    + "【输出格式】Markdown：## 执行结果（按子任务分节）/ ## 与计划的偏离说明。\n"
                    + "【质量红线】产出必须完整具体，禁止『此处省略』类占位；无法完成的子任务明确说明原因。");
            execute.put("timeoutSeconds", 900);
            execute.put("dependsOn", List.of("plan"));

            Map<String, Object> review = new LinkedHashMap<>();
            review.put("id", "review");
            review.put("name", "结果复核");
            review.put("agentId", agent.getId());
            review.put("promptTemplate", "【角色定位】你是质量复核专家，负责对照计划验收执行结果。\n"
                    + "【输入数据】任务目标：${goal}\n执行计划：${plan.output}\n执行结果：${execute.output}\n"
                    + "【工作步骤】1.逐项对照子任务完成标准验收；2.列出问题清单（严重级别+修改建议）；3.给出修订后的最终交付版本。\n"
                    + "【输出格式】Markdown：## 验收结论（通过/有条件通过/不通过）/ ## 问题清单 / ## 最终交付版本。\n"
                    + "【质量红线】验收必须逐项对照完成标准，禁止只写『无意见』；最终交付版本必须完整。");
            review.put("timeoutSeconds", 600);
            review.put("dependsOn", List.of("execute"));

            Map<String, Object> root = new LinkedHashMap<>();
            root.put("name", "AI生成工作流(内置模板)");
            root.put("category", "CUSTOM");
            root.put("nodes", List.of(plan, execute, review));

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            return parseAndValidate(json);
        } catch (Exception e) {
            throw new IllegalStateException("内置回退模板构建失败: " + e.getMessage(), e);
        }
    }

    // ════════════════════════════ 提示词 ════════════════════════════

    private String buildGeneratePrompt(String description, List<AgentDef> agents) {
        return SCHEMA_DOC
                + "\n\n" + PROMPT_QUALITY_DOC
                + "\n\n" + EXAMPLES_DOC
                + "\n\n## 当前系统可用智能体清单（agentId 必须从中选择，禁止编造）\n" + agentCatalog(agents)
                + "\n\n## 用户需求\n" + description
                + "\n\n## 输出要求\n"
                + "1. 只输出一个 JSON 对象，不要 markdown 围栏、不要解释文字。\n"
                + "2. 顶层字段：name（工作流名称）、category（从 DEV_PROCESS / STOCK_ANALYSIS / CUSTOM 中判定）、nodes。\n"
                + "3. 节点数建议 3-9 个（至少包含规划→执行→复核三环节），每个节点的 agentId 必须取自上方清单中的 id；"
                + "节点 id 只能用字母/数字/下划线/中划线。\n"
                + "4. 每个节点的 promptTemplate 必须按上方【节点提示词质量标准】编写（五段式结构化提示词）。\n"
                + "5. 首节点（无依赖节点）的 promptTemplate 使用 ${goal} 占位符接收启动输入；"
                + "下游节点用 ${上游节点id.output} 引用上游输出。\n"
                + "6. 依赖关系必须是无环 DAG，不允许孤立节点。";
    }

    private String buildEditPrompt(String currentJson, String instruction, List<AgentDef> agents) {
        return SCHEMA_DOC
                + "\n\n## 当前系统可用智能体清单（agentId 必须从中选择，禁止编造）\n" + agentCatalog(agents)
                + "\n\n## 现有工作流定义 JSON\n" + currentJson
                + "\n\n## 修改指令\n" + instruction
                + "\n\n## 输出要求\n"
                + "1. 在现有定义基础上按指令修改，输出修改后的【完整】JSON（含未改动的节点），"
                + "不要 markdown 围栏、不要解释文字。\n"
                + "2. 未被指令涉及的节点保持原样；节点 id 只能用字母/数字/下划线/中划线。\n"
                + "3. 依赖关系必须是无环 DAG，不允许孤立节点；agentId 必须存在于清单中。";
    }

    private String agentCatalog(List<AgentDef> agents) {
        StringBuilder sb = new StringBuilder();
        for (AgentDef a : agents) {
            sb.append("- id=").append(a.getId())
              .append(" | name=").append(a.getName())
              .append(" | type=").append(a.getType())
              .append(" | 职责=").append(abbreviate(firstLine(a.getSystemPrompt()), 80))
              .append('\n');
        }
        return sb.toString();
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int idx = text.indexOf('\n');
        return idx > 0 ? text.substring(0, idx) : text;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }

    // ════════════════════════════ 内嵌文档 ════════════════════════════

    private static final String SCHEMA_DOC = """
            ## 简化工作流 JSON Schema
            {
              "name": "工作流名称(字符串)",
              "category": "DEV_PROCESS | STOCK_ANALYSIS | CUSTOM",
              "nodes": [
                {
                  "id": "节点唯一id，仅字母/数字/下划线/中划线",
                  "name": "节点中文名称",
                  "agentId": 数字，必须是系统中已存在的智能体 id,
                  "promptTemplate": "节点输入模板。支持占位符：${goal} 取启动输入变量；${某节点id.output} 取上游节点输出",
                  "timeoutSeconds": 节点超时秒数(整数，建议 300-900),
                  "dependsOn": ["上游节点id", ...]  // 为空数组表示入口节点
                }
              ]
            }
            规则：nodes 构成无环 DAG；dependsOn 引用必须存在；同一入度>1 的节点会自动并行汇聚。""";

    private static final String PROMPT_QUALITY_DOC = """
            ## 节点提示词质量标准（每个节点的 promptTemplate 必须满足）
            每个 promptTemplate 必须是 15 行以上的生产级结构化提示词，包含以下五段（段名用【】标注）：
            1. 【角色定位】一两句话定义该节点智能体的专业身份与本环节职责。
            2. 【输入数据】显式列出本节点用到的占位符：入口节点用 ${goal}（或业务约定的用户变量），
               下游节点用 ${上游节点id.output} 引用上游产出，多个上游逐条列出并加中文标题说明。
            3. 【工作步骤】3-6 步分步思维链（第一步/第二步/…），每步说明做什么、依据什么、产出什么。
            4. 【输出格式】明确的 Markdown 章节结构（# 与 ## 标题逐个列出），需要表格的地方给出表头。
            5. 【质量红线】3-5 条硬约束，例如：结论必须基于给定输入数据并引用具体内容、禁止编造事实、
               输入缺失时明确声明『该项数据未提供』、评审类节点必须给出明确结论（通过/不通过）。
            反例（禁止）：『请完成以下任务：${goal}』这类单行提示词视为不合格。""";

    private static final String EXAMPLES_DOC = """
            ## 示例一：开发工作流（串行）
            {
              "name": "开发工作流",
              "category": "DEV_PROCESS",
              "nodes": [
                {"id": "req_write", "name": "需求编写", "agentId": 1,
                 "promptTemplate": "根据以下目标编写需求文档：${goal}",
                 "timeoutSeconds": 600, "dependsOn": []},
                {"id": "req_review", "name": "需求评审", "agentId": 2,
                 "promptTemplate": "请评审以下需求文档并输出评审意见与修订版：${req_write.output}",
                 "timeoutSeconds": 600, "dependsOn": ["req_write"]},
                {"id": "dev", "name": "开发实现", "agentId": 3,
                 "promptTemplate": "根据评审后的需求进行开发实现：${req_review.output}",
                 "timeoutSeconds": 900, "dependsOn": ["req_review"]}
              ]
            }

            ## 示例二：股票分析工作流（并行汇聚）
            {
              "name": "股票分析工作流",
              "category": "STOCK_ANALYSIS",
              "nodes": [
                {"id": "fundamental", "name": "基本面分析", "agentId": 4,
                 "promptTemplate": "请对以下股票进行基本面分析：${stockInfo}",
                 "timeoutSeconds": 600, "dependsOn": []},
                {"id": "technical", "name": "技术面分析", "agentId": 5,
                 "promptTemplate": "请对以下股票进行技术面分析：${stockInfo}",
                 "timeoutSeconds": 600, "dependsOn": []},
                {"id": "synthesis", "name": "综合研判", "agentId": 6,
                 "promptTemplate": "股票资料：${stockInfo}\\n基本面结论：${fundamental.output}\\n技术面结论：${technical.output}\\n请给出综合研判。",
                 "timeoutSeconds": 900, "dependsOn": ["fundamental", "technical"]}
              ]
            }
            （示例中的 agentId 仅为示意，实际必须从下方智能体清单选择）""";

    // ════════════════════════════ 内部载体 ════════════════════════════

    private static class LlmJsonResult {
        WorkflowJsonDefinition definition;
        String normalizedJson;
        String category;
    }
}
