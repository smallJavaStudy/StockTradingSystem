package demo.semiconductor;

import io.agentscope.core.message.Msg;

/**
 * 快速测试：验证单个专家智能体的研究能力。
 *
 * <p>用法:
 * <pre>
 * mvn exec:java -Dexec.mainClass="demo.semiconductor.QuickTest"
 * </pre>
 */
public class QuickTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 半导体专家智能体快速测试 ===");
        System.out.println();

        // 测试1: 制造工艺专家
        System.out.println("--- 测试1: 制造工艺专家 ---");
        SemiconductorManufacturingExpert mfgExpert = new SemiconductorManufacturingExpert();
        Msg result1 = mfgExpert.research(
                "光刻技术的原理和国产化现状：193nm DUV vs 13.5nm EUV，上海微电子28nm DUV光刻机的技术水平",
                "quicktest-mfg-" + System.currentTimeMillis());
        System.out.println("制造工艺专家报告长度: " + result1.getTextContent().length() + " 字符");
        System.out.println(result1.getTextContent().substring(0, Math.min(500, result1.getTextContent().length())));
        System.out.println();

        // 测试2: 产业生态专家
        System.out.println("--- 测试2: 产业生态专家 ---");
        ChineseSemiconductorEcosystemExpert ecoExpert = new ChineseSemiconductorEcosystemExpert();
        Msg result2 = ecoExpert.research(
                "中芯国际当前的制程节点和产能布局：14nm、N+1等效7nm、28nm产能扩张计划",
                "quicktest-eco-" + System.currentTimeMillis());
        System.out.println("产业生态专家报告长度: " + result2.getTextContent().length() + " 字符");
        System.out.println(result2.getTextContent().substring(0, Math.min(500, result2.getTextContent().length())));
        System.out.println();

        System.out.println("=== 测试完成 ===");
    }
}
