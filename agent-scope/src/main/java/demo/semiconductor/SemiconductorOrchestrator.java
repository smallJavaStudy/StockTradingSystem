package demo.semiconductor;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;

import java.nio.file.Paths;

/**
 * 半导体研究协调者 — 负责将研究课题拆解后分派给5个专家智能体，
 * 收集各自产出后进行综合、质疑、交叉验证，最终生成统一的知识体系报告。
 *
 * <p>这是整个半导体专家团的"大脑"，决定谁研究什么、如何保证输出的
 * 一致性、以及如何整合为一个完整的认知框架。
 */
public class SemiconductorOrchestrator {

    private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    public static final String SYSTEM_PROMPT = """
你是半导体研究协调者，负责统筹5个半导体专家智能体完成系统化研究。

【你的专家团队】
1. 芯片制造工艺专家 — 精通FEOL/BEOL/光刻/刻蚀/沉积/制程节点
2. 半导体设备专家 — 精通所有制造设备的技术和市场格局
3. 半导体材料专家 — 精通硅片/光刻胶/特气/靶材/CMP等材料
4. 中国半导体产业生态专家 — 精通中国Fab/IDM/设计公司的技术路线和产能
5. 半导体供应链与制裁专家 — 精通美国/荷兰/日本出口管制和供应链脆弱性

【你的职责】
1. 收到研究总目标后，将其拆解为5-8个子课题
2. 为每个子课题指定最合适的专家(可指定1-2个专家同时研究以交叉验证)
3. 接收各专家的研究报告
4. 识别专家报告之间的不一致，要求相关专家重新核实
5. 将所有报告整合为一个统一的知识体系
6. 标注整个体系中的"高度确定"、"中等确定"、"存疑"信息

【你的工作流程】
Phase 1 — 课题拆解:
  分析总目标，拆解为具体子课题，分配给指定专家
  确保子课题之间有交叉但不过度重叠
  输出：课题分配表

Phase 2 — 研究执行(专家自主):
  各专家独立使用 web_search 和 web_fetch 工具研究
  输出：各专家的结构化研究报告

Phase 3 — 交叉验证:
  对比不同专家的报告，找出冲突点
  对冲突点安排二次研究
  输出：验证报告

Phase 4 — 知识整合:
  将所有验证后的报告整合为统一的知识体系
  包含：技术流程、设备清单、材料清单、公司对应关系、制裁影响、时间线
  输出：最终知识体系报告

【验证标准】
- 不同专家的报告中对同一事实的描述必须一致
- 发现矛盾时，优先采信有明确来源的一方
- 如果双方都有来源但数据矛盾，标注为"存疑"并说明两方观点
- 每个关键数据点必须能追溯到某个专家的报告

【输出格式】
最终产出使用Markdown标准格式，包含：
- 课题总览
- 分章节详细报告（每章标注贡献专家和可信度）
- 争议点与存疑之处
- 知识体系总图
- 进一步研究方向建议\
""";

    private final HarnessAgent agent;

    public SemiconductorOrchestrator() {
        this(SemiconductorMaterialsExpert.buildDefaultModel());
    }

    public SemiconductorOrchestrator(OpenAIChatModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReadFileTool());
        toolkit.registerTool(new WriteFileTool());

        this.agent = HarnessAgent.builder()
                .name("半导体研究协调者")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(Paths.get(".agentscope/semiconductor-workspace/orchestrator"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(25)
                        .keepMessages(10)
                        .build())
                .build();
    }

    /**
     * Phase 1: 拆解课题并分配给专家
     */
    public Msg decomposeAndAssign(String mainGoal, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("orchestrator")
                .build();

        String prompt = String.format("""
请执行 Phase 1 — 课题拆解：

【总研究目标】%s

请将总目标拆解为5-8个具体子课题，每个子课题指定1-2个专家负责研究。
输出格式：

## 课题拆解表
| 序号 | 子课题 | 负责专家 | 研究重点 | 预期产出 |
|------|--------|---------|---------|---------|
| 1 | ... | 制造工艺专家 + 设备专家 | ... | ... |
...
""", mainGoal);

        return agent.call(new UserMessage(prompt), ctx).block();
    }

    /**
     * Phase 3: 交叉验证 — 接收所有专家报告，找出矛盾
     */
    public Msg crossValidate(String allReports, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("orchestrator")
                .build();

        String prompt = String.format("""
请执行 Phase 3 — 交叉验证：

以下是各专家的研究报告，请仔细阅读并找出：
1. 不同报告中矛盾的事实描述
2. 缺少来源支持的关键数据
3. 可能存在时效性问题的信息
4. 需要补充研究的盲区

【所有专家报告】
%s

输出格式：
## 交叉验证报告
### 一致确认的事实（多专家验证）
### 矛盾与冲突（需重新核实）
### 信息盲区（需要补充研究）
### 存疑数据（来源薄弱或时效性存疑）
""", allReports);

        return agent.call(new UserMessage(prompt), ctx).block();
    }

    /**
     * Phase 4: 知识整合 — 生成最终报告
     */
    public Msg synthesize(String allReports, String validationReport, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("orchestrator")
                .build();

        String prompt = String.format("""
请执行 Phase 4 — 知识整合，生成最终的知识体系报告：

将以下专家研究报告和交叉验证结果整合为完整的认知体系。

【专家报告】
%s

【交叉验证报告】
%s

最终报告结构：
## 一、芯片制造全流程总览（制造专家 + 设备专家 + 材料专家）
## 二、各环节的国产化现状（所有专家贡献）
## 三、关键公司与技术路线（产业生态专家）
## 四、制裁约束与供应链脆弱性（供应链专家）
## 五、认知体系整体评估
### 高度确定的认知
### 需要持续跟踪的认知
### 存疑待验证的认知
## 六、进一步研究建议
""", allReports, validationReport);

        return agent.call(new UserMessage(prompt), ctx).block();
    }

    public HarnessAgent getAgent() {
        return agent;
    }
}
