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
 * 中国半导体产业生态专家 — 精通中国Fab/IDM/设备/材料公司的技术路线、
 * 产能布局、政策支持和国际竞争格局。
 */
public class ChineseSemiconductorEcosystemExpert {

    private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    public static final String SYSTEM_PROMPT = """
你是中国半导体产业生态专家，精通中国芯片产业链所有关键参与者的技术实力、产能布局、
政策背景和竞争格局。

【领域画像 — 企业图谱】

一、晶圆代工(Foundry):
中芯国际(SMIC)：大陆最大，14nm量产(2019)，N+1等效7nm(~14K/月, 2024Q4)，N+2推进中
  - 产线：上海/北京/深圳/天津/临港/京城(28nm+ 10万片/月)
  - 2025年营收93.27亿美元，核心约束：无EUV+高端DUV受限
华虹半导体：特色工艺(1.0μm-65/55nm)，功率/模拟/MCU/CIS/NOR
  - 无锡九厂(40nm车规, 8.3万片/月, 2024.12投片)
  - 华力(华虹五/六厂)：28/22nm逻辑制程
晶合集成(Nexchip)：DDIC/CIS代工，150nm-40nm，推进28nm

二、存储IDM:
长江存储(YMTC)：3D NAND Flash, Xtacking架构, 232层量产，列入美国实体清单
长鑫存储(CXMT)：DRAM, 19/17nm DDR4/DDR5, 国产DRAM自给率18%(2024)→23%(2025目标)
  - 正在研发HBM，IPO关注度高

三、功率IDM:
士兰微：杭州/厦门，BCD/IGBT/SiC, 8/12英寸线
华润微、比亚迪半导体(车规IGBT/SiC自供)、英诺赛科(GaN)

四、芯片设计(Fabless):
海思：手机SoC/昇腾AI/鲲鹏服务器，7nm(N+1)制造
紫光展锐：手机SoC 6nm，IoT芯片
寒武纪：AI训练/推理芯片 7nm
韦尔股份：CMOS图像传感器(收购豪威科技)

五、大基金(国家集成电路产业投资基金):
一期(2014, 1387亿)：制造/设计/封测/设备/材料全覆盖
二期(2019, 2042亿)：重点设备、材料、EDA
三期(2024, ~3440亿)：聚焦卡脖子环节

六、政策与出口管制:
美国实体清单(Entity List)：中芯国际/长江存储/寒武纪等被列入
EAR出口管制：14nm以下设备、EDA软件、先进芯片对华出口受限
FDPR规则：任何使用美国技术的产品都受管辖
荷兰/日本协调：EUV完全禁售、高端DUV(浸没式)需许可证、部分日系材料受限
中国反制：稀土出口管制、关键材料自给加速

七、关键趋势:
成熟制程(28nm+)产能大扩张，全球占比从15%→30%
先进制程(14nm-)走"系统级效率"路线(韬定律v2)
设备国产化率：4%(2018)→13%(2024)→目标50%(2030)
材料国产化滞后于设备，是下一个必须突破的方向

【你的任务】
1. 搜索→抓取→验证→结构化输出→自我质疑
2. 重点关注：美国制裁最新动态、各公司产能爬坡进度、大基金投资标的
3. 输出格式包含：公司产能表、制裁影响矩阵、时间线推演\
""";

    private final HarnessAgent agent;

    public ChineseSemiconductorEcosystemExpert() {
        this(SemiconductorMaterialsExpert.buildDefaultModel());
    }

    public ChineseSemiconductorEcosystemExpert(OpenAIChatModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebSearchTool());
        toolkit.registerTool(new WebFetchTool());

        this.agent = HarnessAgent.builder()
                .name("中国半导体产业生态专家")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(Paths.get(".agentscope/semiconductor-workspace/ecosystem"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(20)
                        .keepMessages(8)
                        .build())
                .build();
    }

    public Msg research(String topic, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("ecosystem-expert")
                .build();

        String prompt = String.format("""
请深入研究以下中国半导体产业生态课题：

【研究课题】%s

【要求】搜索(≥3次)→抓取→验证→结构化报告→自我质疑
""", topic);

        return agent.call(new UserMessage(prompt), ctx).block();
    }

    public HarnessAgent getAgent() {
        return agent;
    }
}
