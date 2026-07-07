package com.vio.vioaiagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.Map;

/**
 * 网页搜索工具 — 返回格式化的可读文本，而非原始 JSON
 */
public class WebSearchTool {

    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";

    private final String apiKey;

    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(description = "Search for information from Baidu Search Engine")
    public String searchWeb(
            @ToolParam(description = "Search query keyword") String query) {
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("q", query);
        paramMap.put("api_key", apiKey);
        paramMap.put("engine", "baidu");
        try {
            String response = HttpUtil.get(SEARCH_API_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            if (organicResults == null || organicResults.isEmpty()) {
                return "No results found for query: " + query;
            }
            // 最多返回 5 条，格式化为可读文本
            int maxResults = Math.min(organicResults.size(), 5);
            StringBuilder sb = new StringBuilder();
            sb.append("Search results for \"").append(query).append("\":\n\n");
            for (int i = 0; i < maxResults; i++) {
                JSONObject item = organicResults.getJSONObject(i);
                sb.append(i + 1).append(". ");
                sb.append(item.getStr("title", "No title")).append("\n");
                sb.append("   URL: ").append(item.getStr("link", "No link")).append("\n");
                String snippet = item.getStr("snippet", "");
                if (snippet.length() > 300) {
                    snippet = snippet.substring(0, 300) + "...";
                }
                sb.append("   ").append(snippet).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error searching Baidu: " + e.getMessage();
        }
    }
}
