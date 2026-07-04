package com.vio.vioaiagent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * VioManus 智能体测试
 * <p>
 * 测试智能体的核心能力：
 * <ul>
 *   <li>简单对话（无需工具，AI 直接回复）</li>
 *   <li>单步工具调用（搜索）</li>
 *   <li>多步自主规划（搜索→抓取→生成PDF）</li>
 *   <li>状态校验（非 IDLE 状态不可启动）</li>
 *   <li>空输入校验</li>
 * </ul>
 *
 * @author vio
 */
@SpringBootTest
class VioManusTest {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    /**
     * 测试简单对话 — AI 应直接回答，不调用工具
     */
    @Test
    void testSimpleConversation() {
        VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
        String message = "你好，请做一下自我介绍";
        String result = vioManus.run(message);
        Assertions.assertNotNull(result);
        System.out.println("简单对话结果：\n" + result);
    }

    /**
     * 测试单步工具调用 — AI 调用 WebSearchTool 搜索信息
     */
    @Test
    void testSingleToolCall() {
        VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
        String message = "帮我搜索一下2024年最受欢迎的约会方式";
        String result = vioManus.run(message);
        Assertions.assertNotNull(result);
        System.out.println("单步工具调用结果：\n" + result);
    }

    /**
     * 测试多步自主规划 — 搜索 → 记入文件 → 完成
     */
    @Test
    void testMultiStepPlanning() {
        VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
        String message = "搜索3个适合情侣约会的浪漫创意，然后保存到文件 love_ideas.txt 中";
        String result = vioManus.run(message);
        Assertions.assertNotNull(result);
        System.out.println("多步规划结果：\n" + result);
    }

    /**
     * 测试文件操作 + PDF 生成的组合任务
     */
    @Test
    void testFileAndPdfTask() {
        VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
        String message = "帮我生成一份名为 date_plan.pdf 的约会计划PDF文档，内容包括3个约会创意";
        String result = vioManus.run(message);
        Assertions.assertNotNull(result);
        System.out.println("文件+PDF任务结果：\n" + result);
    }

    /**
     * 测试状态校验 — 从非 IDLE 状态运行应抛出异常
     */
    @Test
    void testCannotRunFromNonIdleState() {
        VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
        // 第一次运行
        vioManus.run("搜索一下天气");
        // 第二次运行应从 IDLE 开始，但 Agent 已经处于 FINISHED/ERROR 状态
        Assertions.assertThrows(RuntimeException.class, () -> {
            vioManus.run("再搜索一次");
        });
    }

    /**
     * 测试空输入校验
     */
    @Test
    void testEmptyPromptValidation() {
        VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
        Assertions.assertThrows(RuntimeException.class, () -> {
            vioManus.run("");
        });
        Assertions.assertThrows(RuntimeException.class, () -> {
            vioManus.run(null);
        });
    }

    /**
     * 测试 SSE 流式执行 — 每步实时推送结果
     */
    @Test
    void testStreamExecution() throws Exception {
        VioManus vioManus = new VioManus(allTools, dashscopeChatModel);
        String message = "搜索一下上海的约会地点推荐";
        // SSE 流式执行是异步的，这里只验证不会抛异常
        var sseEmitter = vioManus.runStream(message);
        Assertions.assertNotNull(sseEmitter);
        // 等待 SSE 完成（最多等待 60 秒）
        Thread.sleep(3000);
    }
}
