package demo.semiconductor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ExecutionConfig;
import java.time.Duration;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.agentscope.core.message.Msg;

/**
 * 鼎龙股份(300054) 深度研究流水线 — 5个专家从不同维度并发研究。
 *
 * <h3>研究维度</h3>
 * <pre>
 * 材料专家: CMP抛光垫业务深度分析（技术突破、产品矩阵、全球竞争格局）
 * 设备专家: CMP设备与耗材的协同关系（华海清科配套、设备-耗材绑定）
 * 制造工艺专家: CMP工艺中抛光垫的作用（不同制程对CMP次数和抛光垫要求）
 * 产业生态专家: 鼎龙股份公司全景（财务、产能、客户验证、政策支持）
 * 供应链专家: CMP抛光垫供应链脆弱性（陶氏垄断风险、国产替代路径）
 * </pre>
 *
 * <p>用法:
 * <pre>
 * mvn exec:java -Dexec.mainClass="demo.semiconductor.DingLongResearch"
 * </pre>
 */
public class DingLongResearch {

    private static final Path OUTPUT_DIR = Paths.get(
            "D:\\code\\StockTradingSystem\\docs\\knowledge\\semiconductor-manufacturing");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(5);

    // 5个研究维度 — 每个专家从自己的专业领域出发
    private static final String STOCK_NAME = "鼎龙股份(300054)";
    private static final String STOCK_CODE = "300054";

    private static final String[] TOPICS = {
            // 0 — 材料专家：CMP抛光垫业务
            "鼎龙股份(300054)的CMP抛光垫业务深度分析：\n" +
            "1. 鼎龙CMP抛光垫的技术突破历程（从立项到量产的时间线）\n" +
            "2. 产品线覆盖：oxide pad / poly pad / 先进封装pad，各产品的技术参数\n" +
            "3. 全球CMP抛光垫竞争格局：陶氏(Dow/DuPont)的全球垄断份额、Cabot、Thomas West\n" +
            "4. 鼎龙当前国内市场份额、客户导入进度（中芯国际/长江存储/华虹等）\n" +
            "5. 抛光垫的核心技术壁垒：聚氨酯配方、沟槽设计、修整(dressing)技术\n" +
            "6. 与安集科技抛光液的协同/独立关系\n" +
            "7. 搜索鼎龙股份2025年年报/2026年一季报最新财务数据",

            // 1 — 设备专家：CMP设备与耗材协同
            "CMP设备与抛光垫耗材的协同关系，聚焦鼎龙股份(300054)的配套逻辑：\n" +
            "1. CMP设备(华海清科/应用材料)对抛光垫的配套需求和技术要求\n" +
            "2. 设备-耗材绑定模式：设备商认证抛光垫的流程、鼎龙抛光垫是否已通过设备商认证\n" +
            "3. 不同CMP设备型号（华海清科12英寸CMP vs 应用材料Reflexion）对抛光垫的规格要求\n" +
            "4. 华海清科出货量增长对鼎龙抛光垫需求的拉动测算\n" +
            "5. CMP设备国产化率>40%对抛光垫国产替代的催化作用",

            // 2 — 制造工艺专家：CMP工艺中抛光垫的关键作用
            "CMP抛光工艺中抛光垫(pad)的关键作用和技术演进，评估鼎龙股份(300054)的技术定位：\n" +
            "1. CMP工艺三要素：抛光液(slurry) + 抛光垫(pad) + 修整器(conditioner)的协同机制\n" +
            "2. 不同制程节点的CMP次数：28nm约10次、14nm约15次、7nm约25次、5nm约30次\n" +
            "3. 先进制程对抛光垫的新要求：平坦化均匀性、选择性、缺陷控制\n" +
            "4. 3D NAND堆叠层数增加对CMP步骤和抛光垫消耗量的影响\n" +
            "5. 鼎龙抛光垫在不同制程节点的适用性评估\n" +
            "6. 中国晶圆厂扩产(中芯/长存/长鑫)对抛光垫需求量的量化测算",

            // 3 — 产业生态专家：鼎龙股份公司全景（简化为5项核心）
            "鼎龙股份(300054)公司全景分析：\n" +
            "1. 业务结构：CMP抛光垫 + 打印复印耗材双主业，各板块营收占比和毛利率\n" +
            "2. 最新财务：2025年报/2026Q1的营收、净利润、研发费用率\n" +
            "3. 产能建设：抛光垫扩产进度、新建产线时间表\n" +
            "4. 客户验证：已进入中芯国际/长江存储/华虹等Fab供应链的进度\n" +
            "5. 竞争对比：vs 陶氏(Dow/DuPont)的技术差距、大基金/政策支持情况",

            // 4 — 供应链专家：CMP抛光垫供应链脆弱性与国产替代
            "CMP抛光垫的供应链脆弱性分析和鼎龙股份(300054)的国产替代路径：\n" +
            "1. 全球CMP抛光垫供应链格局：陶氏(DuPont)全球份额、日本企业份额\n" +
            "2. 中国CMP抛光垫进口依赖度：当前国产化率是多少\n" +
            "3. 日本/美国出口管制对CMP抛光垫的影响评估（是否受管制清单影响）\n" +
            "4. 鼎龙作为国产替代唯一标的的战略价值评估\n" +
            "5. 替代路径的时间窗口：从验证到量产需要多长时间\n" +
            "6. 供应链断供风险评估：如果陶氏断供，中国Fab的抛光垫库存能撑多久\n" +
            "7. 鼎龙替代陶氏的技术差距和商业可行性分析"
    };

    private static final String[] EXPERT_NAMES = {
            "半导体材料专家", "半导体设备专家", "芯片制造工艺专家",
            "中国半导体产业生态专家", "半导体供应链与制裁专家"
    };

    // 构建15分钟超时的模型
    private static OpenAIChatModel buildLongTimeoutModel() {
        GenerateOptions options = GenerateOptions.builder()
                .executionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofMinutes(15))
                        .maxAttempts(3)
                        .build())
                .build();
        return OpenAIChatModel.builder()
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .modelName("deepseek-chat")
                .baseUrl("https://api.deepseek.com")
                .generateOptions(options)
                .build();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║   鼎龙股份(300054) 深度研究流水线                    ║");
        System.out.println("║   5个专家智能体 × 5个研究维度                        ║");
        System.out.println("║   AgentScope HarnessAgent + DeepSeek V4             ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("研究标的: " + STOCK_NAME);
        System.out.println("模型: DeepSeek V4 (deepseek-chat)");
        System.out.println("工具: WebSearchTool (DuckDuckGo) + WebFetchTool");
        System.out.println();

        Instant start = Instant.now();
        String sessionPrefix = "dinglong-" + System.currentTimeMillis();

        // 初始化5个专家（使用15分钟超时模型）
        OpenAIChatModel longModel = buildLongTimeoutModel();
        SemiconductorMaterialsExpert materialsExpert = new SemiconductorMaterialsExpert(longModel);
        SemiconductorEquipmentExpert equipmentExpert = new SemiconductorEquipmentExpert(longModel);
        SemiconductorManufacturingExpert manufacturingExpert = new SemiconductorManufacturingExpert(longModel);
        ChineseSemiconductorEcosystemExpert ecosystemExpert = new ChineseSemiconductorEcosystemExpert(longModel);
        SemiconductorSupplyChainExpert supplyChainExpert = new SemiconductorSupplyChainExpert(longModel);

        // ───── Phase 1: 5个专家并发研究 ─────
        System.out.println("═══ Phase 1: 5个专家并发研究鼎龙股份 ═══");
        System.out.println();

        List<CompletableFuture<ResearchResult>> futures = new ArrayList<>();
        for (int i = 0; i < TOPICS.length; i++) {
            final int idx = i;
            final String topic = TOPICS[i];

            CompletableFuture<ResearchResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    System.out.printf("  [%d/5] 启动: %s%n", idx + 1, EXPERT_NAMES[idx]);

                    Msg result = switch (idx) {
                        case 0 -> materialsExpert.research(topic, sessionPrefix + "-materials");
                        case 1 -> equipmentExpert.research(topic, sessionPrefix + "-equipment");
                        case 2 -> manufacturingExpert.research(topic, sessionPrefix + "-manufacturing");
                        case 3 -> ecosystemExpert.research(topic, sessionPrefix + "-ecosystem");
                        case 4 -> supplyChainExpert.research(topic, sessionPrefix + "-supplychain");
                        default -> throw new IllegalStateException();
                    };

                    System.out.printf("  [%d/5] 完成: %s (输出 %d 字符)%n",
                            idx + 1, EXPERT_NAMES[idx], result.getTextContent().length());
                    return new ResearchResult(idx, EXPERT_NAMES[idx], result.getTextContent());

                } catch (Exception e) {
                    System.err.printf("  [%d/5] 失败: %s — %s%n", idx + 1, EXPERT_NAMES[idx], e.getMessage());
                    return new ResearchResult(idx, EXPERT_NAMES[idx], "研究失败: " + e.getMessage());
                }
            }, EXECUTOR);

            futures.add(future);
        }

        // 等待所有研究完成(最多10分钟)
        List<ResearchResult> results = new ArrayList<>();
        for (CompletableFuture<ResearchResult> f : futures) {
            try {
                results.add(f.get(15, TimeUnit.MINUTES));
            } catch (Exception e) {
                System.err.println("研究超时: " + e.getMessage());
            }
        }
        results.sort(Comparator.comparingInt(a -> a.index));

        long phase1Sec = Duration.between(start, Instant.now()).getSeconds();
        System.out.println();
        System.out.printf("Phase 1 完成: %d/5 个专家研究完成, 耗时 %d 秒%n", results.size(), phase1Sec);
        System.out.println();

        // ───── Phase 2: 交叉验证 ─────
        System.out.println("═══ Phase 2: 协调者交叉验证 ═══");
        SemiconductorOrchestrator orchestrator = new SemiconductorOrchestrator(longModel);

        StringBuilder allReports = new StringBuilder();
        for (ResearchResult r : results) {
            allReports.append("### ").append(r.expertName).append("\n\n")
                    .append(r.content).append("\n\n---\n\n");
        }

        String validationReport = orchestrator.crossValidate(
                allReports.toString(), sessionPrefix + "-validate").getTextContent();
        System.out.println(validationReport.substring(0, Math.min(1000, validationReport.length())));
        System.out.println("...");
        System.out.println();

        // ───── Phase 3: 知识整合 ─────
        System.out.println("═══ Phase 3: 生成综合深度报告 ═══");
        String finalReport = orchestrator.synthesize(
                allReports.toString(), validationReport, sessionPrefix + "-synthesize").getTextContent();
        System.out.println("综合报告长度: " + finalReport.length() + " 字符");
        System.out.println();

        // ───── Phase 4: 保存输出 ─────
        System.out.println("═══ Phase 4: 保存输出 ═══");
        Files.createDirectories(OUTPUT_DIR);

        // 保存综合报告
        Path reportPath = OUTPUT_DIR.resolve("鼎龙股份-深度研究报告.md");
        String fullReport = String.format("""
# 鼎龙股份(300054) 深度研究报告

> 生成时间: %s
> 研究标的: %s
> 模型: DeepSeek V4 (deepseek-chat) via AgentScope 2.0
> 智能体: 5个专家智能体并发研究 + 协调者交叉验证与整合
> 工具: WebSearchTool (DuckDuckGo) + WebFetchTool
> 总耗时: %d 秒

---

%s

---

## 附录：各专家原始研究报告

%s
""",
                Instant.now().toString(),
                STOCK_NAME,
                Duration.between(start, Instant.now()).getSeconds(),
                finalReport,
                allReports.toString());

        Files.writeString(reportPath, fullReport);
        System.out.println("  ✓ 综合报告: " + reportPath.toAbsolutePath());

        // 保存各专家原始报告
        Path expertDir = OUTPUT_DIR.resolve("鼎龙股份-专家原始报告");
        Files.createDirectories(expertDir);
        for (ResearchResult r : results) {
            String safeName = r.expertName.replace(" ", "_").replace("/", "_");
            Path expertPath = expertDir.resolve(safeName + ".md");
            Files.writeString(expertPath, String.format("""
# %s — 鼎龙股份(300054)研究报告

**时间**: %s

---

%s
""", r.expertName, Instant.now().toString(), r.content));
            System.out.println("  ✓ " + safeName + ".md");
        }

        // 保存元数据
        Path metaPath = expertDir.resolve("_研究元数据.json");
        Map<String, Object> meta = new HashMap<>();
        meta.put("stock_name", STOCK_NAME);
        meta.put("stock_code", STOCK_CODE);
        meta.put("pipeline_version", "1.0");
        meta.put("start_time", start.toString());
        meta.put("end_time", Instant.now().toString());
        meta.put("duration_seconds", Duration.between(start, Instant.now()).getSeconds());
        meta.put("model", "deepseek-chat (DeepSeek V4)");
        meta.put("framework", "AgentScope 2.0 HarnessAgent");
        meta.put("experts", List.of(EXPERT_NAMES));
        meta.put("completed_count", results.size());
        Files.writeString(metaPath, MAPPER.writeValueAsString(meta));

        // ───── 汇总 ─────
        long totalSec = Duration.between(start, Instant.now()).getSeconds();
        System.out.println();
        System.out.println("═══════════════════════════════════════════");
        System.out.printf("  鼎龙股份深度研究完成! 总耗时 %d 秒%n", totalSec);
        System.out.printf("  完成专家: %d/5%n", results.size());
        System.out.printf("  综合报告: %s%n", reportPath.getFileName());
        System.out.println("═══════════════════════════════════════════");

        EXECUTOR.shutdown();
    }

    record ResearchResult(int index, String expertName, String content) {}
}
