package com.vio.vioaiagent.mcp.protocol.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;

/**
 * MCP 工具调用结果.
 *
 * <p>对应 JSON-RPC 方法 {@code "tools/call"} 的响应.
 * 包含一个或多个内容项, 标记此次调用是否出错.
 *
 * @param content 内容项列表（文本、图片等）
 * @param isError 此次工具调用是否以错误结束
 * @author vio
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallResult(List<ContentItem> content, boolean isError) {

    /**
     * MCP 内容项 — 工具调用的基本输出单元.
     *
     * @param type 内容类型: "text" 或 "image"
     * @param text 文本内容（type 为 "text" 时使用）
     * @param data Base64 编码的图片数据（type 为 "image" 时使用）
     * @param mimeType 图片的 MIME 类型（type 为 "image" 时使用）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContentItem(String type, String text, String data, String mimeType) {

        public static ContentItem text(String text) {
            return new ContentItem("text", text, null, null);
        }

        public static ContentItem image(String data, String mimeType) {
            return new ContentItem("image", null, data, mimeType);
        }
    }

    /**
     * 创建成功结果（单条文本内容）.
     */
    public static ToolCallResult success(String text) {
        return new ToolCallResult(
                Collections.singletonList(ContentItem.text(text)), false);
    }

    /**
     * 创建错误结果（单条文本内容）.
     */
    public static ToolCallResult error(String text) {
        return new ToolCallResult(
                Collections.singletonList(ContentItem.text(text)), true);
    }
}
