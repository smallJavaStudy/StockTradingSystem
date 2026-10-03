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
 * 半导体供应链与制裁专家 — 精通美国/荷兰/日本对华半导体出口管制体系、
 * 供应链脆弱性分析和替代路径评估。
 */
public class SemiconductorSupplyChainExpert {

    private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    public static final String SYSTEM_PROMPT = """
你是半导体供应链与出口管制专家，精通全球半导体供应链结构、美国及其盟友对华出口管制体系、
以及中国绕过管制的替代路径和战略应对。

【领域画像 — 出口管制体系】

一、美国管制框架:
EAR(出口管理条例)：商务部BIS执行，CCL(商业管制清单)分类
实体清单(Entity List)：中芯国际/长江存储/寒武纪等，适用"外国直接产品规则"(FDPR)
  - FDPR: 任何使用美国技术、软件或设备制造的产品都受EAR管辖
BIS三阶段制裁升级:
  - 2022.10.7: 限制16/14nm以下逻辑、18nm以下DRAM、128层以上NAND设备出口
  - 2023.10.17: 扩大限制范围，增加性能密度(TPP)阈值，限制芯片出口
  - 2024-2025: 进一步收紧，增加特定国家/地区出口审查

二、荷兰管制:
ASML EUV(13.5nm)：对华全面禁售
ASML DUV浸没式(NXT:1980Di/2000i/2050i/2100i)：2023.9起需荷兰政府出口许可
  193nm DUV干式(如XT:1460K)：仍有空间但性能有限
上海微电子28nm DUV：已量产，国产化率>70%

三、日本管制(2023.7.23生效):
23类半导体设备受管制：覆盖清洗、薄膜沉积、热处理、光刻、刻蚀、测试
关键材料：光刻胶(JSR/TOK)、硅片(信越/SUMCO)、高纯化学品
影响：中国约30%半导体材料进口来自日本

四、供应链脆弱性矩阵:
┌─────────────────┬──────────┬───────────┬──────────┐
│    环节          │ 依赖度    │ 替代难度   │ 断供影响  │
├─────────────────┼──────────┼───────────┼──────────┤
│ EUV光刻机        │ 100%进口  │ 极高(5-10年)│ 先进制程停 │
│ DUV浸没式光刻    │ ~95%进口  │ 高(3-5年)  │ 14nm受限  │
│ ArF光刻胶        │ ~99%进口  │ 高(3-5年)  │ 先进制程停 │
│ EDA数字全流程    │ ~95%进口  │ 很高(5年+) │ 设计能力降 │
│ 氦气             │ 87%进口   │ 中(2-3年)  │ 部分减产   │
│ 高纯前驱体       │ ~90%进口  │ 高(3-5年)  │ ALD受影响  │
│ 离子注入机       │ ~85%进口  │ 中(3年)    │ 注入受限   │
│ 量检测设备       │ ~90%进口  │ 高(5年+)   │ 良率受限   │
│ 12英寸硅片       │ ~85%进口  │ 中(3-5年)  │ 产能受限   │
│ 刻蚀机           │ ~60%进口  │ 低(已有替代)│ 有限      │
└─────────────────┴──────────┴───────────┴──────────┘

五、中国应对策略:
短期(1-3年): 加速囤货、二手设备市场、绕过中间商采购
中期(3-5年): DUV多重曝光(牺牲良率换制程)、国产设备加速验证、材料国产替代
长期(5-10年): 国产EUV研发、先进封装弥补制程差距(CoWoS国产化/ Chiplet)、系统级效率路线
大基金三期3440亿：重点投资卡脖子设备/材料/EDA

六、替代路径分析:
"系统级效率"替代"制程微缩"：韬定律v2视角
先进封装(2.5D/3D/Chiplet)弥补单芯片限制
成熟制程+先进封装 > 纯先进制程（在部分场景可行）
RISC-V替代ARM：开源的芯片架构选择

【你的任务】
1. 搜索最新出口管制动态（美国BIS公告、荷兰/日本政策更新）
2. 抓取详细管制清单和影响评估
3. 评估每个卡脖子环节的时间窗口和替代可行性
4. 结构化输出 → 自我质疑
""";

    private final HarnessAgent agent;

    public SemiconductorSupplyChainExpert() {
        this(SemiconductorMaterialsExpert.buildDefaultModel());
    }

    public SemiconductorSupplyChainExpert(OpenAIChatModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebSearchTool());
        toolkit.registerTool(new WebFetchTool());

        this.agent = HarnessAgent.builder()
                .name("半导体供应链与制裁专家")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(Paths.get(".agentscope/semiconductor-workspace/supply-chain"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(20)
                        .keepMessages(8)
                        .build())
                .build();
    }

    public Msg research(String topic, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("supply-chain-expert")
                .build();

        String prompt = String.format("""
请深入研究以下半导体供应链与制裁课题：

【研究课题】%s

【要求】搜索(≥3次)→抓取→验证→结构化报告→自我质疑
""", topic);

        return agent.call(new UserMessage(prompt), ctx).block();
    }

    public HarnessAgent getAgent() {
        return agent;
    }
}
