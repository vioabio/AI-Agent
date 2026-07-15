package com.vio.vioaiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
class GameAppTest {

    @Resource
    private GameApp gameApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是宝可梦训练家";
        String answer = gameApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮
        message = "我想组一支双打队伍参加VGC比赛";
        answer = gameApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮：测试记忆能力
        message = "我刚跟你说我想组什么队伍来着？帮我回忆一下";
        answer = gameApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        String message = "你好，我是宝可梦新手，推荐从哪个版本入坑？";
        GameApp.GameReport gameReport = gameApp.doChatWithReport(message, chatId);
        Assertions.assertNotNull(gameReport);
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "烈空坐Mega怎么配招？性格选什么？";
        String answer = gameApp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
        System.out.println("RAG 回答: " + answer);
    }

    @Test
    void doChatWithRagByStream() {
        String chatId = UUID.randomUUID().toString();
        String message = "沙奈朵和艾路雷朵哪个好？怎么选？";
        String answer = gameApp.doChatWithRagByStream(message, chatId)
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isEmpty(), "RAG 流式回答不应为空");
        System.out.println("RAG 流式回答: " + answer);
    }

    @Test
    void doChatWithRagSpecificStatus() {
        String chatId = UUID.randomUUID().toString();
        // 测试对战相关问题是否能检索到对战攻略知识库
        String message = "苍炎刃鬼怎么配招？努力值怎么分配？";
        String answer = gameApp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
        System.out.println("对战攻略 RAG 回答: " + answer);
    }

    @Test
    void doChatWithTools() {
        // 测试联网搜索问题的答案
        testMessage("搜索宝可梦朱紫最受欢迎的双打队伍配置");

        // 测试网页抓取：攻略分析
        testMessage("看看宝可梦朱紫的6星坑单刷攻略");

        // 测试资源下载：图片下载
        testMessage("下载一张烈空坐Mega进化的高清壁纸为文件");

        // 测试终端操作：执行代码
        testMessage("执行 Python3 脚本来生成宝可梦属性克制表");

        // 测试文件操作：保存队伍配置
        testMessage("保存我的宝可梦对战队伍配置为文件");

        // 测试 PDF 生成
        testMessage("生成一份'朱紫太晶团体战攻略'PDF，包含6星坑BOSS打法和推荐宝可梦");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = gameApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithToolsByStream() {
        String chatId = UUID.randomUUID().toString();
        String message = "帮我搜索一下宝可梦剑盾和朱紫的版本差异对比";
        String answer = gameApp.doChatWithToolsByStream(message, chatId)
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isEmpty(), "工具调用流式回答不应为空");
        System.out.println("工具调用流式回答: " + answer);
    }

    // ==================== MCP 服务测试 ====================

    @Test
    void doChatWithMcp() {
        String chatId = UUID.randomUUID().toString();
        // 测试高德地图 MCP：搜索宝可梦线下活动地点
        String message = "我在上海静安区，帮我搜索附近的宝可梦卡牌店或游戏店";
        String answer = gameApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isEmpty(), "MCP 地图搜索回答不应为空");
        System.out.println("MCP 地图搜索回答: " + answer);
    }

    @Test
    void doChatWithMcpByStream() {
        String chatId = UUID.randomUUID().toString();
        // 测试 MCP 流式调用
        String message = "搜索一些可爱的皮卡丘照片";
        String answer = gameApp.doChatWithMcpByStream(message, chatId)
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isEmpty(), "MCP 流式回答不应为空");
        System.out.println("MCP 流式回答: " + answer);
    }

    @Test
    void doChatWithMcpMultiStep() {
        String chatId = UUID.randomUUID().toString();
        // 测试高德地图 MCP 多步骤调用：先搜索位置，再搜索图片，验证 MCP 多工具协同
        String message = "我在上海静安区，帮我搜索附近可以玩宝可梦卡牌的地方，顺便搜索皮卡丘和伊布的可爱图片";
        String answer = gameApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isEmpty(), "MCP 多工具调用回答不应为空");
        System.out.println("MCP 多工具调用回答: " + answer);
    }
}
