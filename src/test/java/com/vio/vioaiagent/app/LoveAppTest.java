package com.vio.vioaiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
class LoveAppTest {

    @Resource
    private LoveApp loveApp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好，我是程序员鱼皮";
        String answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮
        message = "我想让另一半（编程导航）更爱我";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮：测试记忆能力
        message = "我的另一半叫什么来着？刚跟你说过，帮我回忆一下";
        answer = loveApp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        String message = "你好，我是程序员鱼皮，我想让另一半（编程导航）更爱我，但我不知道该怎么做";
        LoveApp.LoveReport loveReport = loveApp.doChatWithReport(message, chatId);
        Assertions.assertNotNull(loveReport);
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "我已经结婚了，但是婚后关系不太亲密，怎么办？";
        String answer = loveApp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
        System.out.println("RAG 回答: " + answer);
    }

    @Test
    void doChatWithRagByStream() {
        String chatId = UUID.randomUUID().toString();
        String message = "我是单身，怎么提升自己的魅力？";
        String answer = loveApp.doChatWithRagByStream(message, chatId)
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
        // 测试单身相关问题是否能检索到单身篇知识库
        String message = "我是单身，怎么扩大社交圈认识更多人？";
        String answer = loveApp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
        System.out.println("单身篇 RAG 回答: " + answer);
    }

    @Test
    void doChatWithTools() {
        // 测试联网搜索问题的答案
        testMessage("周末想带女朋友去上海约会，推荐几个适合情侣的小众打卡地？");

        // 测试网页抓取：恋爱案例分析
        testMessage("最近和对象吵架了，看看编程导航网站（codefather.cn）的其他情侣是怎么解决矛盾的？");

        // 测试资源下载：图片下载
        testMessage("直接下载一张适合做手机壁纸的星空情侣图片为文件");

        // 测试终端操作：执行代码
        testMessage("执行 Python3 脚本来生成数据分析报告");

        // 测试文件操作：保存用户档案
        testMessage("保存我的恋爱档案为文件");

        // 测试 PDF 生成
        testMessage("生成一份'七夕约会计划'PDF，包含餐厅预订、活动流程和礼物清单");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = loveApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithToolsByStream() {
        String chatId = UUID.randomUUID().toString();
        String message = "帮我搜索一下上海有哪些适合约会的地方";
        String answer = loveApp.doChatWithToolsByStream(message, chatId)
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
        // 测试高德地图 MCP：搜索指定位置周边的约会地点
        String message = "我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点";
        String answer = loveApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isEmpty(), "MCP 地图搜索回答不应为空");
        System.out.println("MCP 地图搜索回答: " + answer);
    }

    @Test
    void doChatWithMcpByStream() {
        String chatId = UUID.randomUUID().toString();
        // 测试 MCP 流式调用
        String message = "搜索一些可爱的猫咪照片";
        String answer = loveApp.doChatWithMcpByStream(message, chatId)
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
        // 第一步：搜索上海静安区附近的约会地点
        String message = "我的另一半居住在上海静安区，请帮我找到5公里内合适的约会地点，并帮我搜索一些浪漫的约会场景图片";
        String answer = loveApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isEmpty(), "MCP 多工具调用回答不应为空");
        System.out.println("MCP 多工具调用回答: " + answer);
    }
}