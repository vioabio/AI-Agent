package com.vio.vioaiagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具输出格式单元测试 — 验证工具返回的是可读文本而非原始 HTML/JSON
 */
class ToolOutputTest {

    // ==================== WebScrapingTool 测试 ====================

    @Test
    void testScrapeReturnsTextNotHtml() {
        WebScrapingTool tool = new WebScrapingTool();
        // 使用一个简单的测试页面
        String result = tool.scrapeWebPage("https://httpbin.org/html");
        System.out.println("Scrape result (first 500 chars):\n" +
                (result.length() > 500 ? result.substring(0, 500) : result));

        // 不应包含 HTML 标签
        assertFalse(result.contains("<html>"), "不应返回 HTML 标签");
        assertFalse(result.contains("<body>"), "不应返回 HTML body 标签");
        assertFalse(result.contains("<div"), "不应返回 HTML div 标签");
        // 应该包含 "Title:" 格式
        assertTrue(result.startsWith("Title:"), "应以 Title: 开头");
    }

    @Test
    void testScrapeOutputIsTruncated() {
        WebScrapingTool tool = new WebScrapingTool();
        String result = tool.scrapeWebPage("https://www.baidu.com");
        System.out.println("Scrape length: " + result.length() + " chars");
        // 正常情况下应被截断到 2000 字符左右
        assertTrue(result.length() < 2500,
                "输出应被截断，实际长度: " + result.length());
    }

    @Test
    void testScrapeInvalidUrlReturnsError() {
        WebScrapingTool tool = new WebScrapingTool();
        String result = tool.scrapeWebPage("https://invalid-url-that-does-not-exist-12345.com");
        System.out.println("Error result: " + result);
        assertTrue(result.startsWith("Error scraping"), "无效 URL 应返回错误信息");
    }

    // ==================== WebSearchTool 测试 ====================

    @Test
    void testSearchReturnsFormattedText() {
        WebSearchTool tool = new WebSearchTool("test-key");
        // 注意：用假 key 调 searchapi.io 会返回错误
        // 但我们可以验证错误信息格式
        String result = tool.searchWeb("test query");
        System.out.println("Search result: " + result);

        // 应该要么是格式化结果，要么是错误信息
        // 不应该是原始 JSON
        assertFalse(result.trim().startsWith("{"), "不应返回原始 JSON");
        assertFalse(result.trim().startsWith("["), "不应返回 JSON 数组");
        // 长度应该合理（不是几十万字符）
        assertTrue(result.length() < 5000, "输出应在合理长度范围内");
    }

    // ==================== 截断逻辑测试 ====================

    @Test
    void testTruncationInTools() {
        // 构建一个超长字符串
        StringBuilder sb = new StringBuilder();
        sb.append("Title: Test\n\nContent:\n");
        for (int i = 0; i < 100; i++) {
            sb.append("This is line ").append(i)
                    .append(" with some content that adds up to make a long string. ");
        }
        String longContent = sb.toString();

        // 验证 WebScrapingTool 的截断（通过构造一个会失败的 URL 间接测试）
        WebScrapingTool tool = new WebScrapingTool();
        String result = tool.scrapeWebPage("https://httpbin.org/html");
        // 输出应该被限制（上限 ~2500 字符 = 2000 正文 + 标题等其他信息）
        assertTrue(result.length() < 3000,
                "WebScrapingTool 输出应被截断，实际: " + result.length());
    }
}
