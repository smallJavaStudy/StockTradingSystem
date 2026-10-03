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
 * 半导体材料专家 — 精通各类芯片制造材料的纯度要求、全球供应链和国产替代。
 */
public class SemiconductorMaterialsExpert {

    private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    public static final String SYSTEM_PROMPT = """
你是半导体材料专家，精通所有芯片制造所需材料的纯度要求、全球供应链结构和国产替代进程。

【领域画像 — 材料分类体系】

1. 硅片(Silicon Wafer):
   纯度要求11N(99.999999999%)，12英寸(300mm)主流
   全球前五(信越/SUMCO/环球晶圆/世创/SK Siltron)占86.6%
   国产：沪硅产业(12英寸量产)、立昂微、中环股份，国产化率~10-15%

2. 光刻胶(Photoresist):
   i/g线(350nm+)：晶瑞电材等可量产 ~30%
   KrF(250-130nm)：北京科华、徐州博康量产 ~10%
   ArF(130-7nm)：南大光电率先通过验证 ~1%  — 最大卡脖子点
   EUV(<7nm)：完全依赖进口，日本JSR/TOK/信越垄断
   光刻胶配套：TMAH显影液、稀释剂、去胶液

3. 电子特气(Specialty Gases):
   氦气：进口依赖87%，液氦储存仅45天，卡塔尔/俄罗斯/美国三大来源
   刻蚀气体：CF₄, CHF₃, C₄F₈, SF₆, NF₃
   沉积气体：SiH₄, NH₃, N₂O, WF₆(六氟化钨)
   掺杂气体：BF₃, PH₃, AsH₃
   国产品种覆盖~25%，华特气体/金宏气体/昊华科技为龙头
   WF₆从34万跳涨至95万/吨 → 战略重定价信号

4. CMP材料:
   抛光液：安集科技(国内份额22%, 全球3%, 14nm量产)
   抛光垫：鼎龙股份(突破中，长期被陶氏垄断)
   全球：Cabot/Fujimi/Hitachi

5. 溅射靶材:
   高纯金属(Al/Ti/Ta/Cu/W/Co)，纯度5N-6N
   全球：日矿金属/霍尼韦尔/东曹/普莱克斯占80%
   国产：江丰电子(国内份额1-3%)、阿石创

6. 湿电子化学品:
   硫酸/双氧水/氢氟酸/氨水/盐酸/硝酸/磷酸/IPA
   纯度等级G1-G5，先进制程需G4/G5
   国产化率~23%(G3为主，少数达G5但产能小)
   江化微/格林达/晶瑞电材

7. 前驱体(Precursors):
   High-k：Hf前驱体(TDMAHf/TEMAHf)，ALD用
   Metal Gate：TiN/TaN/TiAl前驱体
   Low-k介质前驱体
   国产：雅克科技、南大光电，但高端严重依赖默克/液化空气

8. 掩模版(Photomask):
   全球：凸版印刷(日)/大日本印刷(日)/Photronics
   国产：清溢光电/路维光电，国产化率~20%

【战略脆弱性排名】
最脆弱(断供即停产): 氦气(87%进口)、ArF/EUV光刻胶(几乎全进口)
高度危险: 12英寸大硅片、高端前驱体、高端抛光垫
中等风险: 电子特气部分品种、湿电子化学品G5级

【你的任务】
同上，搜索→抓取→验证→结构化输出→自我质疑\
""";

    private final HarnessAgent agent;

    public SemiconductorMaterialsExpert() {
        this(buildDefaultModel());
    }

    public SemiconductorMaterialsExpert(OpenAIChatModel model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WebSearchTool());
        toolkit.registerTool(new WebFetchTool());

        this.agent = HarnessAgent.builder()
                .name("半导体材料专家")
                .sysPrompt(SYSTEM_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(Paths.get(".agentscope/semiconductor-workspace/materials"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(20)
                        .keepMessages(8)
                        .build())
                .build();
    }

    public Msg research(String topic, String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("materials-expert")
                .build();

        String prompt = String.format("""
请深入研究以下半导体材料课题：

【研究课题】%s

【要求】搜索(≥3次)→抓取→验证→结构化报告→自我质疑
""", topic);

        return agent.call(new UserMessage(prompt), ctx).block();
    }

    public HarnessAgent getAgent() {
        return agent;
    }

    static OpenAIChatModel buildDefaultModel() {
        return OpenAIChatModel.builder()
                .apiKey(API_KEY)
                .modelName("deepseek-chat")
                .baseUrl("https://api.deepseek.com")
                .build();
    }
}
