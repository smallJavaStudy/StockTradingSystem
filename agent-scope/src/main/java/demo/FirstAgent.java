package demo;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import java.nio.file.Paths;

/**
 * AgentScope Java 智能体 —— 使用 DeepSeek V4 Pro。
 * API Key 已内置，直接运行即可。
 */
public class FirstAgent {

    private static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    public static void main(String[] args) {
        // DeepSeek 是 OpenAI 兼容协议，用 OpenAIChatModel 指定自定义 baseUrl
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(API_KEY)
                .modelName("deepseek-chat")
                .baseUrl("https://api.deepseek.com")
                .build();

        HarnessAgent agent = HarnessAgent.builder()
                .name("小助手")
                .sysPrompt("你是一个乐于助人的中文智能助手，回答简洁有力。")
                .model(model)
                .workspace(Paths.get(".agentscope/workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("user-001")
                .build();

        // 第一轮：自我介绍
        String reply1 = agent.call(
                new UserMessage("你好，请用中文介绍一下你自己。"), ctx
        ).block().getTextContent();
        System.out.println("🤖 " + reply1);

        // 第二轮：同 sessionId，自动记住上文
        String reply2 = agent.call(
                new UserMessage("你刚才说你叫什么名字？"), ctx
        ).block().getTextContent();
        System.out.println("🤖 " + reply2);
    }
}
