package com.vio.vioaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 网页抓取工具 — 提取网页正文文本（非 HTML），限制长度防止 Token 爆炸
 */
public class WebScrapingTool {

    /** 最大返回字符数，防止单次工具调用塞爆 AI 上下文窗口 */
    private static final int MAX_OUTPUT_LENGTH = 2000;

    @Tool(description = "Scrape the text content of a web page (not raw HTML)")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape") String url) {
        try {
            Document document = Jsoup.connect(url)
                    .timeout(10000)
                    // 网关只校验初始 URL；禁止自动跟随 30x，防止公网地址
                    // 重定向到本机/内网后形成 SSRF 绕过。
                    .followRedirects(false)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .get();
            // 获取页面标题
            String title = document.title();
            // 只取正文文本，丢弃 HTML 标签
            String text = document.body().text();
            // 截断到最大长度
            if (text.length() > MAX_OUTPUT_LENGTH) {
                text = text.substring(0, MAX_OUTPUT_LENGTH) + "...(truncated, total " + text.length() + " chars)";
            }
            return "Title: " + title + "\n\nContent:\n" + text;
        } catch (Exception e) {
            return "Error scraping web page: " + e.getMessage();
        }
    }
}
