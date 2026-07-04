package com.vio.vioimagesearchmcpserver.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 图片搜索工具 —— 通过 Pexels API 搜索网络图片
 * <p>
 * 注册为 MCP 服务端的 Tool，供 AI 客户端调用。
 * 使用前需要在 Pexels 官网 (https://www.pexels.com/api/) 注册获取 API Key。
 *
 * @author vio
 */
@Service
@Slf4j
public class ImageSearchTool {

    /**
     * Pexels API Key（需要在 Pexels 官网注册获取）
     */
    private static final String API_KEY = System.getenv().getOrDefault("PEXELS_API_KEY", "你的 Pexels API Key");

    /**
     * Pexels 图片搜索 API 地址
     */
    private static final String API_URL = "https://api.pexels.com/v1/search";

    /**
     * 搜索网络图片
     *
     * @param query 搜索关键词（支持中英文）
     * @return 图片 URL 列表（逗号分隔的 medium 尺寸图片地址）
     */
    @Tool(description = "search image from web")
    public String searchImage(@ToolParam(description = "Search query keyword") String query) {
        log.info("收到图片搜索请求，关键词: {}", query);
        try {
            List<String> imageUrls = searchMediumImages(query);
            if (imageUrls.isEmpty()) {
                return "未找到与 \"" + query + "\" 相关的图片";
            }
            String result = String.join(",", imageUrls);
            log.info("图片搜索完成，找到 {} 张图片", imageUrls.size());
            return result;
        } catch (Exception e) {
            log.error("图片搜索失败: {}", e.getMessage(), e);
            return "图片搜索出错: " + e.getMessage();
        }
    }

    /**
     * 调用 Pexels API 搜索中等尺寸图片
     *
     * @param query 搜索关键词
     * @return 图片 URL 列表
     */
    public List<String> searchMediumImages(String query) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", API_KEY);

        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("per_page", 10);

        String response = HttpUtil.createGet(API_URL)
                .addHeaders(headers)
                .form(params)
                .execute()
                .body();

        return JSONUtil.parseObj(response)
                .getJSONArray("photos")
                .stream()
                .map(photoObj -> (JSONObject) photoObj)
                .map(photoObj -> photoObj.getJSONObject("src"))
                .map(photo -> photo.getStr("medium"))
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toList());
    }
}
