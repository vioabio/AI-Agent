package com.vio.vioaiagent.app;

import com.vio.vioaiagent.advisor.MyLoggerAdvisor;
import com.vio.vioaiagent.advisor.ReReadingAdvisor;
import com.vio.vioaiagent.chatmemory.FileBasedChatMemory;
import com.vio.vioaiagent.rag.GameAppRagCustomAdvisorFactory;
import com.vio.vioaiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@Slf4j
public class GameApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            扮演深耕宝可梦对战与养成领域的专家，昵称「宝可梦大师」。
            开场向用户表明身份，告知可以咨询宝可梦相关问题。
            覆盖全世代宝可梦知识（从GBA红蓝宝石到Switch朱紫/ZA）：
            图鉴方向 — 任意宝可梦的种族值、属性克制、进化方式、栖息地、闪光形态；
            对战方向 — 配招推荐、努力值分配、性格选择、双打队伍体系、太晶属性策略；
            攻略方向 — 道馆馆主打法、冠军之路、DLC剧情、太晶团体战6星坑攻略；
            版本方向 — 各代差异对比（Mega/Z招式/极巨化/太晶化）、入坑推荐、限定宝可梦。
            引导用户说明想了解的宝可梦名称、对战环境（单打/双打/VGC规则）、使用场景，提供精准建议。
            """;

    /**
     * 初始化 ChatClient
     *
     * @param dashscopeChatModel
     */
    public GameApp(ChatModel dashscopeChatModel) {
//        // 初始化基于文件的对话记忆
//        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
//        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
        // 初始化基于内存的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .build();
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 基础对话（支持多轮对话记忆，SSE 流式传输）
     * <p>
     * 使用默认 advisors（包含 MessageChatMemoryAdvisor）实现多轮对话，
     * 同时通过 advisors 回调传入 conversationId 参数
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        // 使用 try-catch 包裹，避免 advisor 异常导致整个流失败
        try {
            return chatClient
                    .prompt()
                    .user(message)
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                    .stream()
                    .content()
                    .doOnError(e -> log.error("GameApp 流式对话出错: {}", e.getMessage(), e));
        } catch (Exception e) {
            log.error("GameApp 流式对话启动失败: {}", e.getMessage(), e);
            return Flux.just("抱歉，服务暂时不可用：" + e.getMessage());
        }
    }

    record GameReport(String title, List<String> suggestions) {

    }

    /**
     * AI 宝可梦报告功能（实战结构化输出）
     *
     * @param message
     * @param chatId
     * @return
     */
    public GameReport doChatWithReport(String message, String chatId) {
        GameReport gameReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次咨询后生成宝可梦训练家报告，标题为{用户名}的宝可梦报告，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(GameReport.class);
        log.info("gameReport: {}", gameReport);
        return gameReport;
    }

    // AI 宝可梦知识库问答功能

    @Lazy
    @Resource
    private VectorStore gameAppVectorStore;

    @Autowired(required = false)
    private Advisor loveAppRagCloudAdvisor;

    @Autowired(required = false)
    private VectorStore pgVectorVectorStore;

    @Resource
    private QueryRewriter queryRewriter;

    /**
     * 和 RAG 知识库进行对话
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithRag(String message, String chatId) {
        // 查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                // 使用改写后的查询
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                // 应用 RAG 知识库问答
                .advisors(new QuestionAnswerAdvisor(gameAppVectorStore))
                // 应用 RAG 检索增强服务（基于云知识库服务）
//                .advisors(loveAppRagCloudAdvisor)
                // 应用 RAG 检索增强服务（基于 PgVector 向量存储）
//                .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
                // 应用自定义的 RAG 检索增强服务（文档查询器 + 上下文增强器）
//                .advisors(
//                        GameAppRagCustomAdvisorFactory.createGameAppRagCustomAdvisor(
//                                gameAppVectorStore, "单身"
//                        )
//                )
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * 和 RAG 知识库进行对话（SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatWithRagByStream(String message, String chatId) {
        // 查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        return chatClient
                .prompt()
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(new QuestionAnswerAdvisor(gameAppVectorStore))
                .stream()
                .content();
    }

    // AI 调用工具能力
    @Autowired(required = false)
    private ToolCallback[] allTools;

    /**
     * AI 对话（支持调用工具）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 对话（支持调用工具，SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatWithToolsByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .stream()
                .content();
    }

    // AI 调用 MCP 服务

    @Autowired(required = false)
    private ToolCallbackProvider toolCallbackProvider;

    /**
     * AI 对话（调用 MCP 服务）
     *
     * @param message
     * @param chatId
     * @return
     */
    public String doChatWithMcp(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }

    /**
     * AI 对话（调用 MCP 服务，SSE 流式传输）
     *
     * @param message
     * @param chatId
     * @return
     */
    public Flux<String> doChatWithMcpByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 开启日志，便于观察效果
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .stream()
                .content();
    }
}
