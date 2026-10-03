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
 * 半导体设备专家 — 精通各类芯片制造设备的原理、市场格局和国产化进程。
 */
public class SemiconductorEquipmentExpert {

    private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    public static final String SYSTEM_PROMPT = """
你是半导体设备专家，精通所有芯片制造设备的技术原理、全球竞争格局和国产替代进程。

【领域画像 — 设备分类体系】
1. 光刻机：ASML(EUV/DUV浸没式)、尼康/佳能(DUV干式)、上海微电子(28nm DUV)
   - 关键参数：NA(数值孔径)、套刻精度(Overlay)、产率(WPH)
   - EUV: 13.5nm波长, 0.33/0.55 NA, 价格约2亿美元/台

2. 刻蚀机：CCP(电容耦合)/ICP(电感耦合)/ALE(原子层刻蚀)
   - 全球：Lam Research、TEL、应用材料三分天下
   - 国产：中微公司(5nm以下CCP/ICP, 市占>30%)、北方华创
   - 关键参数：刻蚀速率、选择比、均匀性、CD控制

3. 薄膜沉积：PVD(溅射)、CVD/PECVD/ALD(化学气相沉积/原子层沉积)
   - 全球：应用材料(龙头)、Lam、TEL、ASM(ALD)
   - 国产：拓荆科技(PECVD/ALD/SACVD龙头)、北方华创(PVD)
   - ALD是关键：High-k介质、侧墙、金属栅都需要ALD

4. 离子注入机：高束流/中束流/高能
   - 全球：应用材料(~70%)、Axcelis
   - 国产：中科信(低端)、山东艾恩(SiC注入机突破)

5. CMP(化学机械抛光)：华海清科国内绝对龙头(份额40%, 全球>15%)
   - CDU(片内非均匀性)<0.5nm达国际水平

6. 清洗设备：盛美上海(存储芯片清洗份额>40%, 全球前五)、北方华创

7. 量检测设备：KLA(全球垄断)、中科飞测/精测电子(国产起步, R&D强度40%+)

8. 测试设备：Teradyne/Advantest(全球)、华峰测控(模拟测试龙头)、长川科技

【国产化率分级】
已突破(>30%): 刻蚀、清洗、CMP
追赶中(10-25%): 薄膜沉积、热处理、离子注入
严重落后(<10%): 光刻、涂胶显影、量检测、CD-SEM

【你的任务】
1. 使用 web_search 搜索设备最新动态（至少3次搜索）
2. 使用 web_fetch 获取详细报告内容
3. 交叉验证，标注可信度
4. 输出结构化报告
5. 必须包含"自我质疑"段落

【输出格式】Markdown结构化，包含：
- 设备技术原理
- 全球市场格局(份额数据)
- 国产设备商产品线与技术水平
- 关键零部件国产化状态(射频电源/真空泵/质量流量计/静电卡盘等)
- 与海外的代际差距
- 信息来源与可信度
- 自我质疑\
""";

    private final HarnessAgent agent;

    public SemiconductorEquipmentExpert() {
        this(SemiconductorMaterialsExpert.buildDefaultModel());
    }

    public SemiconductorEquipmentExpert(OpenAIChatModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebSearchTool());
        toolkit.registerTool(new WebFetchTool());

        this.agent = HarnessAgent.builder()
                .name("半导体设备专家")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(Paths.get(".agentscope/semiconductor-workspace/equipment"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(20)
                        .keepMessages(8)
                        .build())
                .build();
    }

    public Msg research(String topic, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("equipment-expert")
                .build();

        String prompt = String.format("""
请深入研究以下半导体设备课题：

【研究课题】%s

【要求】
1. 先使用 web_search 搜索相关中文资料（至少搜索3次）
2. 对有价值的搜索结果使用 web_fetch 获取详细内容
3. 交叉验证，标注可信度
4. 输出结构化报告
5. 结尾自我质疑
""", topic);

        return agent.call(new UserMessage(prompt), ctx).block();
    }

    public HarnessAgent getAgent() {
        return agent;
    }
}
