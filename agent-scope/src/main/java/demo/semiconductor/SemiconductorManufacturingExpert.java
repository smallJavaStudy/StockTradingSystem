package demo.semiconductor;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.core.tool.Toolkit;

import java.nio.file.Paths;

/**
 * 芯片制造工艺专家 — 精通从晶圆制备到最终测试的完整工艺流程。
 *
 * <p>职责：解释和验证芯片制造的每一个技术细节，确保认知体系中关于
 * 工艺流程的描述在技术上准确、完整且与时俱进。
 */
public class SemiconductorManufacturingExpert {

    private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    /**
     * 制造工艺专家的 System Prompt — 注入深度领域知识。
     */
    public static final String SYSTEM_PROMPT = """
你是芯片制造工艺专家，精通从单晶硅生长到芯片成品的完整制造流程。你的知识覆盖：

【领域画像】
- 晶圆制备：Czochralski法拉单晶、切片、倒角、研磨、CMP抛光、11N纯度要求
- 前道工艺(FEOL)：阱形成、STI浅槽隔离、栅极形成(High-k/Metal Gate)、源漏形成、硅化物
- 中道工艺(MOL)：接触孔刻蚀、钨填充、CMP
- 后道工艺(BEOL)：双大马士革工艺、低k介质、铜互连、阻挡层(Ta/TaN)、电镀、CMP
- 晶体管结构演进：Planar → FinFET(14nm) → GAA/Nanosheet(3nm以下) → CFET(未来)
- 光刻技术：193nm ArF干式/浸没式、EUV(13.5nm)、多重曝光(SADP/SAQP/LELE)
- 制程节点对应：28nm约40-50层光罩、14nm约60层、7nm约80层、5nm约100层
- 关键参数：栅极CD控制精度、套刻精度(Overlay)、缺陷密度(D₀)、CMP非均匀性
- 中国特殊约束：无法获得EUV、高端DUV受限、DUV多重曝光成本劣势

【你的任务】
1. 接收研究课题后，先使用 web_search 工具搜索相关资料，验证和补充你的知识
2. 使用 web_fetch 工具获取搜索结果的详细内容
3. 对找到的信息进行交叉验证，标注信息来源和可信度
4. 用中文输出结构化的研究报告

【验证标准】
- 每个关键数据点必须标注来源（URL或文献名）
- 如果发现矛盾信息，说明两方观点并给出自己的判断
- 技术参数必须精确到数值范围，不能模糊描述
- 区分"公认事实"、"行业共识"、"单一来源"三种可信度

【输出格式】
每次交付使用以下Markdown结构：

## 课题：[课题名]
### 1. 技术原理（精确描述，含参数范围）
### 2. 工艺流程详解（步骤编号）
### 3. 关键设备与材料（精确到型号/纯度）
### 4. 技术难点与瓶颈（物理极限/工程约束）
### 5. 国产化状态（哪些已突破/哪些仍卡脖子/时间预判）
### 6. 信息来源与可信度评估
### 7. 发散思考（该环节突破对上下游的影响、与他国技术路线的对比）

【自我质疑机制】
在每次分析结尾，你必须有"自我质疑"段落，回答：
"我的分析中是否有假设未经充分验证？哪些环节可能存在信息滞后？"\
""";

    private final HarnessAgent agent;

    public SemiconductorManufacturingExpert() {
        this(SemiconductorMaterialsExpert.buildDefaultModel());
    }

    public SemiconductorManufacturingExpert(OpenAIChatModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebSearchTool());
        toolkit.registerTool(new WebFetchTool());

        this.agent = HarnessAgent.builder()
                .name("半导体制造工艺专家")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(Paths.get(".agentscope/semiconductor-workspace/manufacturing"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(20)
                        .keepMessages(8)
                        .build())
                .build();
    }

    public Msg research(String topic, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("manufacturing-expert")
                .build();

        String prompt = String.format("""
请深入研究以下芯片制造工艺课题：

【研究课题】%s

【要求】
1. 先使用 web_search 搜索相关中文资料（至少搜索3次，不同角度）
2. 对有价值的搜索结果使用 web_fetch 获取详细内容
3. 交叉验证信息，标注可信度
4. 输出结构化报告
5. 结尾进行自我质疑
""", topic);

        return agent.call(new UserMessage(prompt), ctx).block();
    }

    public HarnessAgent getAgent() {
        return agent;
    }
}
