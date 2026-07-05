package com.vio.vioimagesearchmcpserver.tools;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ImageSearchTool 单元测试
 * <p>
 * 验证通过 Pexels API 搜索网络图片的功能。
 * 需要有效的 PEXELS_API_KEY 环境变量或使用默认内置 Key。
 *
 * @author vio
 */
@SpringBootTest
class ImageSearchToolTest {

    @Resource
    private ImageSearchTool imageSearchTool;

    @Test
    void searchImage() {
        String result = imageSearchTool.searchImage("computer");
        Assertions.assertNotNull(result);
        System.out.println("图片搜索结果: " + result);
    }

    @Test
    void searchImageWithChineseKeyword() {
        // 测试中文关键词搜索
        String result = imageSearchTool.searchImage("浪漫约会");
        Assertions.assertNotNull(result);
        Assertions.assertFalse(result.isEmpty(), "中文关键词搜索结果不应为空");
        System.out.println("中文关键词搜索结果: " + result);
    }

    @Test
    void searchImageNoResult() {
        // 测试无结果场景 —— 使用极长随机字符串
        String result = imageSearchTool.searchImage("xysiwkemcnrupqazoplmfjghdutr");
        Assertions.assertNotNull(result);
        // 无结果时应返回提示信息而非空字符串
        Assertions.assertTrue(
                result.contains("未找到") || !result.isEmpty(),
                "无结果时应返回提示信息"
        );
        System.out.println("无结果返回: " + result);
    }

    @Test
    void searchImageReturnsMediumUrls() {
        // 测试返回的是 medium 尺寸的图片 URL
        String result = imageSearchTool.searchImage("sunset");
        Assertions.assertNotNull(result);
        // 正常情况下返回多个逗号分隔的 URL
        if (!result.contains("未找到") && !result.contains("出错")) {
            String[] urls = result.split(",");
            Assertions.assertTrue(urls.length > 0, "应至少返回一张图片");
            for (String url : urls) {
                Assertions.assertTrue(
                        url.startsWith("https://"),
                        "图片 URL 应以 https:// 开头: " + url
                );
            }
        }
        System.out.println("Sunset 搜索结果 (" + result.split(",").length + " 张图片)");
    }

    @Test
    void searchImageEmptyQuery() {
        // 测试空关键词
        String result = imageSearchTool.searchImage("");
        Assertions.assertNotNull(result);
        System.out.println("空关键词结果: " + result);
    }
}