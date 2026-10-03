package com.stock.workflow.controller;

import com.stock.workflow.entity.AgentDef;
import com.stock.workflow.repository.AgentDefRepository;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 工作流智能体定义管理：CRUD + 可用工具组查询。
 */
@RestController
@RequestMapping("/api/v1/workflow-agent")
public class AgentDefController {

    /** AGENTSCOPE 型可用工具白名单：Harness 内置工具组 + 自定义股票数据工具 */
    private static final Set<String> AVAILABLE_TOOLS = Set.of(
            "filesystem", "shell", "memory",
            "stock_kline", "stock_finance", "stock_fundflow", "stock_industry", "data_enrich",
            "market_zt", "market_lhb", "web_search");
    private static final Set<String> VALID_TYPES = Set.of("PROMPT", "AGENTSCOPE");

    private final AgentDefRepository agentDefRepo;

    public AgentDefController(AgentDefRepository agentDefRepo) {
        this.agentDefRepo = agentDefRepo;
    }

    @GetMapping("/list")
    public List<AgentDef> list() {
        return agentDefRepo.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    @GetMapping("/{id}")
    public AgentDef get(@PathVariable Long id) {
        return agentDefRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("智能体定义不存在: id=" + id));
    }

    @PostMapping
    public AgentDef create(@RequestBody AgentDef body) {
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (agentDefRepo.findByName(body.getName()).isPresent()) {
            throw new IllegalArgumentException("智能体名称已存在: " + body.getName());
        }
        validate(body);
        body.setId(null); // 防止客户端指定 id 覆盖已有记录
        return agentDefRepo.save(body);
    }

    @PutMapping("/{id}")
    public AgentDef update(@PathVariable Long id, @RequestBody AgentDef body) {
        AgentDef agent = agentDefRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("智能体定义不存在: id=" + id));

        if (body.getName() != null && !body.getName().isBlank()
                && !body.getName().equals(agent.getName())) {
            if (agentDefRepo.findByName(body.getName()).isPresent()) {
                throw new IllegalArgumentException("智能体名称已存在: " + body.getName());
            }
            agent.setName(body.getName());
        }
        if (body.getType() != null) agent.setType(body.getType());
        if (body.getSystemPrompt() != null) agent.setSystemPrompt(body.getSystemPrompt());
        if (body.getModelChoice() != null) agent.setModelChoice(body.getModelChoice());
        if (body.getTemperature() != null) agent.setTemperature(body.getTemperature());
        if (body.getMaxTokens() != null) agent.setMaxTokens(body.getMaxTokens());
        if (body.getToolsJson() != null) agent.setToolsJson(body.getToolsJson());
        if (body.getMaxIterations() != null) agent.setMaxIterations(body.getMaxIterations());
        if (body.getLoopDepth() != null) agent.setLoopDepth(body.getLoopDepth());
        if (body.getEventsJson() != null) agent.setEventsJson(body.getEventsJson());

        validate(agent);
        return agentDefRepo.save(agent);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        AgentDef agent = agentDefRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("智能体定义不存在: id=" + id));
        agentDefRepo.delete(agent);
        return Map.of("deleted", true, "id", id);
    }

    /** AGENTSCOPE 型可用工具清单及说明（内置工具组 + 自定义股票数据工具） */
    @GetMapping("/available-tools")
    public Map<String, Object> availableTools() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("tools", List.of("filesystem", "shell", "memory",
                "stock_kline", "stock_finance", "stock_fundflow", "stock_industry", "data_enrich",
                "market_zt", "market_lhb", "web_search"));
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("filesystem", "文件系统工具组：读写文件、目录浏览");
        descriptions.put("shell", "命令行工具组：执行 shell 命令");
        descriptions.put("memory", "记忆工具组：跨轮次沉淀与回取关键信息");
        descriptions.put("stock_kline", "股票K线工具：查询个股日K线、MA5/20/60均线与量价特征（参数 stockCode, days）");
        descriptions.put("stock_finance", "股票财务工具：查询最近多期 EPS/ROE/营收/净利及同比（参数 stockCode）");
        descriptions.put("stock_fundflow", "股票资金流工具：查询近N日主力净流入序列与趋势（参数 stockCode, days）");
        descriptions.put("stock_industry", "股票行业工具：查询行业/产业链定位、公司主营与竞品对比（参数 stockCode）");
        descriptions.put("data_enrich", "数据补充工具：优先 Serper 网络搜索，再由外部LLM级联（DeepSeek→Kimi）提炼舆情/估值等数据，耗时较长带缓存（参数 stockCode, topic）");
        descriptions.put("market_zt", "涨停池工具：按交易日查涨停家数、连板梯队、炸板率、行业分布与个股明细（含涨停统计ztStat）（参数 date 可选yyyy-MM-dd, poolType 可选TODAY/PREVIOUS）");
        descriptions.put("market_lhb", "龙虎榜工具：按日期查当日上榜个股（净买额降序）或按代码查个股近期上榜记录，含上榜原因与机构解读（参数 date 可选, stockCode 可选）");
        descriptions.put("web_search", "实时网络搜索（Google/Serper，系统默认优先数据源）：秒级返回真实搜索结果，适合最新新闻公告/行情动态/行业政策/舆情（参数 query, type 可选 search/news）");
        resp.put("descriptions", descriptions);
        resp.put("usage", "AGENTSCOPE 型智能体的 toolsJson 填工具名 JSON 数组，如 [\"memory\",\"stock_kline\"]，按白名单裁剪/注册");
        return resp;
    }

    private void validate(AgentDef agent) {
        if (agent.getType() != null && !VALID_TYPES.contains(agent.getType().toUpperCase())) {
            throw new IllegalArgumentException("type 仅支持 PROMPT / AGENTSCOPE，实际: " + agent.getType());
        }
        String toolsJson = agent.getToolsJson();
        if (toolsJson != null && !toolsJson.isBlank()) {
            // 轻量校验：工具名必须在白名单内
            String stripped = toolsJson.replaceAll("[\\[\\]\"\\s]", "");
            if (!stripped.isEmpty()) {
                for (String tool : stripped.split(",")) {
                    if (!AVAILABLE_TOOLS.contains(tool.toLowerCase())) {
                        throw new IllegalArgumentException(
                                "toolsJson 含不支持的工具组: " + tool + "（可用: " + AVAILABLE_TOOLS + "）");
                    }
                }
            }
        }
    }
}
