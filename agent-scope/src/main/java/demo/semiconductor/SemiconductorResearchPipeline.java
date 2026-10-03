package demo.semiconductor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.agentscope.core.message.Msg;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 半导体研究主流水线 — 驱动5个专家智能体+协调者完成完整的认知体系建设。
 *
 * <h3>流水线架构</h3>
 * <pre>
 * Phase 1: Orchestrator 拆解总目标 → 子课题分配表
 * Phase 2: 5个Expert并发执行 → 各自研究报告（串行多轮：搜索→抓取→分析→自我质疑）
 * Phase 3: Orchestrator 交叉验证 → 识别矛盾，安排补研
 * Phase 4: Orchestrator 知识整合 → 最终认知体系报告
 * Phase 5: 输出保存 → docs/knowledge/semiconductor-manufacturing/
 * </pre>
 *
 * <h3>用法</h3>
 * <pre>
 * mvn exec:java -Dexec.mainClass="demo.semiconductor.SemiconductorResearchPipeline" \
 *     -Dexec.args="芯片制造全流程 国产化现状 关键卡脖子环节"
 * </pre>
 *
 * <h3>5个专家并发，每个专家内的多轮工具调用由AgentScope自动管理</h3>
 */
public class SemiconductorResearchPipeline {

    private static final Path OUTPUT_DIR = Paths.get(
            "D:\\code\\StockTradingSystem\\docs\\knowledge\\semiconductor-manufacturing");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(5);

    // 默认子课题（在主目标拆解前使用）
    private static final String[] DEFAULT_TOPICS = {
            "一颗芯片从设计到量产的完整制造流程：FEOL前道工艺（晶体管制造：阱形成/STI/栅极/源漏）的详细步骤、关键设备和材料",
            "一颗芯片从设计到量产的完整制造流程：BEOL后道工艺（多层金属互连：双大马士革工艺/铜电镀/CMP）的详细步骤、关键设备和材料",
            "光刻技术全栈：DUV(193nm干式/浸没式) vs EUV(13.5nm)、多重曝光(SADP/SAQP/LELE)、国产光刻机现状（上海微电子28nm DUV）",
            "半导体制造核心设备国产化现状：刻蚀机/薄膜沉积/离子注入/CMP/清洗/量检测 各设备的国产化率和代表公司",
            "半导体材料国产化现状：硅片/光刻胶/电子特气/CMP/靶材/湿化学品/前驱体 各材料的国产化率和供应链脆弱性",
            "中国晶圆代工格局：中芯国际/华虹/晶合集成的制程节点、产能布局和技术路线",
            "美国对华半导体出口管制体系：实体清单/FDPR规则/荷兰日本协同管制 对各环节的影响评估",
            "先进封装(2.5D/3D/Chiplet/CoWoS)技术详解和中国封装厂的布局"
    };

    public static void main(String[] args) throws Exception {
        String mainGoal = args.length > 0 ? String.join(" ", args) :
                "构建国产芯片从设计到量产的完整认知体系，覆盖制造流程、设备、材料、产业生态、供应链制裁五大维度";

        System.out.println("┌──────────────────────────────────────────────────┐");
        System.out.println("│        半导体研究流水线 v1.0                      │");
        System.out.println("│        5个专家智能体 + 1个协调者                  │");
        System.out.println("│        AgentScope HarnessAgent + Toolkit          │");
        System.out.println("└──────────────────────────────────────────────────┘");
        System.out.println();
        System.out.println("总目标: " + mainGoal);
        System.out.println("工具: WebSearchTool (DuckDuckGo) + WebFetchTool");
        System.out.println("模型: DeepSeek V4 (deepseek-chat)");
        System.out.println();

        Instant start = Instant.now();

        // ───── Phase 1: 拆解课题 ─────
        System.out.println("═══ Phase 1: 课题拆解 ═══");
        SemiconductorOrchestrator orchestrator = new SemiconductorOrchestrator();
        String sessionPrefix = "sr-" + System.currentTimeMillis();

        String decomposeResult = orchestrator.decomposeAndAssign(mainGoal, sessionPrefix + "-phase1").getTextContent();
        System.out.println(decomposeResult);
        System.out.println();

        // ───── Phase 2: 专家并发研究 ─────
        System.out.println("═══ Phase 2: 5个专家并发研究 ═══");

        // 确定子课题（优先用默认课题，确保覆盖面）
        String[] topics = DEFAULT_TOPICS;
        // 映射：topic_index → expert_type
        int[] expertMap = {0, 0, 1, 1, 2, 3, 4, 0};
        // 0=Manufacturing, 1=Equipment, 2=Materials, 3=Ecosystem, 4=SupplyChain

        // 初始化所有专家
        SemiconductorManufacturingExpert manufacturingExpert = new SemiconductorManufacturingExpert();
        SemiconductorEquipmentExpert equipmentExpert = new SemiconductorEquipmentExpert();
        SemiconductorMaterialsExpert materialsExpert = new SemiconductorMaterialsExpert();
        ChineseSemiconductorEcosystemExpert ecosystemExpert = new ChineseSemiconductorEcosystemExpert();
        SemiconductorSupplyChainExpert supplyChainExpert = new SemiconductorSupplyChainExpert();

        List<CompletableFuture<ResearchResult>> futures = new ArrayList<>();
        for (int i = 0; i < topics.length; i++) {
            final int idx = i;
            final String topic = topics[i];
            final int expertType = expertMap[i];

            CompletableFuture<ResearchResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    System.out.printf("  [%d/%d] 启动: %s (专家: %s)%n",
                            idx + 1, topics.length,
                            topic.substring(0, Math.min(50, topic.length())) + "...",
                            switch (expertType) {
                                case 0 -> "制造工艺";
                                case 1 -> "设备";
                                case 2 -> "材料";
                                case 3 -> "产业生态";
                                case 4 -> "供应链";
                                default -> "未知";
                            });

                    Msg result = switch (expertType) {
                        case 0 -> manufacturingExpert.research(topic, sessionPrefix + "-topic" + idx);
                        case 1 -> equipmentExpert.research(topic, sessionPrefix + "-topic" + idx);
                        case 2 -> materialsExpert.research(topic, sessionPrefix + "-topic" + idx);
                        case 3 -> ecosystemExpert.research(topic, sessionPrefix + "-topic" + idx);
                        case 4 -> supplyChainExpert.research(topic, sessionPrefix + "-topic" + idx);
                        default -> throw new IllegalStateException("Unknown expert type: " + expertType);
                    };

                    System.out.printf("  [%d/%d] 完成: %s%n", idx + 1, topics.length,
                            topic.substring(0, Math.min(50, topic.length())) + "...");
                    return new ResearchResult(idx, topic, result.getTextContent(), expertType);

                } catch (Exception e) {
                    System.err.printf("  [%d/%d] 失败: %s — %s%n",
                            idx + 1, topics.length, topic.substring(0, Math.min(40, topic.length())), e.getMessage());
                    return new ResearchResult(idx, topic, "研究失败: " + e.getMessage(), expertType);
                }
            }, EXECUTOR);

            futures.add(future);
        }

        // 等待所有研究完成(最多10分钟)
        List<ResearchResult> results = new ArrayList<>();
        for (CompletableFuture<ResearchResult> f : futures) {
            try {
                results.add(f.get(10, TimeUnit.MINUTES));
            } catch (Exception e) {
                System.err.println("研究超时: " + e.getMessage());
            }
        }

        // 按原始顺序排列
        results.sort((a, b) -> Integer.compare(a.index, b.index));

        System.out.println();
        System.out.println("Phase 2 完成: " + results.size() + "/" + topics.length + " 个课题研究完成");
        System.out.println();

        // ───── Phase 3: 交叉验证 ─────
        System.out.println("═══ Phase 3: 交叉验证 ═══");
        StringBuilder allReports = new StringBuilder();
        for (ResearchResult r : results) {
            allReports.append("### 课题").append(r.index + 1).append(": ")
                    .append(r.topic).append("\n")
                    .append("专家类型: ").append(switch (r.expertType) {
                        case 0 -> "制造工艺专家"; case 1 -> "设备专家";
                        case 2 -> "材料专家"; case 3 -> "产业生态专家";
                        case 4 -> "供应链专家";
                        default -> "未知";
                    }).append("\n\n")
                    .append(r.content).append("\n\n---\n\n");
        }

        String validationReport = orchestrator.crossValidate(
                allReports.toString(), sessionPrefix + "-phase3").getTextContent();
        System.out.println(validationReport);
        System.out.println();

        // ───── Phase 4: 知识整合 ─────
        System.out.println("═══ Phase 4: 知识整合 ═══");
        String finalReport = orchestrator.synthesize(
                allReports.toString(), validationReport, sessionPrefix + "-phase4").getTextContent();
        System.out.println("最终报告长度: " + finalReport.length() + " 字符");
        System.out.println();

        // ───── Phase 5: 保存输出 ─────
        System.out.println("═══ Phase 5: 保存输出 ═══");
        Files.createDirectories(OUTPUT_DIR);

        // 保存最终报告
        Path finalReportPath = OUTPUT_DIR.resolve("00-智能体研究总报告.md");
        String fullReport = String.format("""
# 国产芯片制造认知体系 — 智能体研究报告

> 生成时间: %s
> 总目标: %s
> 模型: DeepSeek V4 (deepseek-chat) via AgentScope 2.0
> 智能体: 5个专家智能体 + 1个协调者
> 工具: WebSearchTool (DuckDuckGo) + WebFetchTool

---

%s

---

## 附录：各专家研究报告

%s
""",
                Instant.now().toString(),
                mainGoal,
                finalReport,
                allReports.toString());

        Files.writeString(finalReportPath, fullReport);
        System.out.println("  ✓ 总报告: " + finalReportPath.toAbsolutePath());

        // 保存各专家原始报告
        for (ResearchResult r : results) {
            String expertName = switch (r.expertType) {
                case 0 -> "制造工艺专家"; case 1 -> "设备专家";
                case 2 -> "材料专家"; case 3 -> "产业生态专家";
                case 4 -> "供应链专家";
                default -> "未知专家";
            };
            String safeFilename = "专家报告-" + expertName + "-课题" + (r.index + 1) + ".md";
            Path expertPath = OUTPUT_DIR.resolve("99-原始报告").resolve(safeFilename);
            Files.createDirectories(expertPath.getParent());
            Files.writeString(expertPath, String.format("""
# %s — 研究课题%d

**课题**: %s
**时间**: %s

---

%s
""", expertName, r.index + 1, r.topic, Instant.now().toString(), r.content));
            System.out.println("  ✓ " + safeFilename);
        }

        // 保存元数据
        Path metaPath = OUTPUT_DIR.resolve("99-原始报告").resolve("_研究元数据.json");
        Map<String, Object> meta = new HashMap<>();
        meta.put("pipeline_version", "1.0");
        meta.put("total_goal", mainGoal);
        meta.put("start_time", start.toString());
        meta.put("end_time", Instant.now().toString());
        meta.put("duration_seconds", Duration.between(start, Instant.now()).getSeconds());
        meta.put("model", "deepseek-chat (DeepSeek V4)");
        meta.put("framework", "AgentScope 2.0 HarnessAgent");
        meta.put("experts_count", 5);
        meta.put("topics_count", topics.length);
        meta.put("completed_count", results.size());
        meta.put("tools", List.of("WebSearchTool (DuckDuckGo)", "WebFetchTool", "ReadFileTool", "WriteFileTool"));
        Files.writeString(metaPath, MAPPER.writeValueAsString(meta));

        // ───── 汇总 ─────
        long durationSec = Duration.between(start, Instant.now()).getSeconds();
        System.out.println();
        System.out.println("════════════════════════════════════");
        System.out.printf("  研究流水线完成! 耗时 %d 秒%n", durationSec);
        System.out.printf("  完成课题: %d/%d%n", results.size(), topics.length);
        System.out.printf("  总报告: %s%n", finalReportPath.getFileName());
        System.out.println("════════════════════════════════════");

        EXECUTOR.shutdown();
    }

    record ResearchResult(int index, String topic, String content, int expertType) {}
}
