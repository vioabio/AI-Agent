package com.vio.vioaiagent.agent;

import com.fasterxml.jackson.databind.*;
import org.springframework.ai.chat.messages.*;
import java.util.*;

/** Drop rejected malformed argument pairs from model context, never repair or execute them. */
public final class ToolHistoryIntegrity {
    public static final String VERSION = "malformed-tool-history-isolation-v1";
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ToolHistoryIntegrity() { }
    public static List<Message> sanitize(List<Message> history) {
        Set<String> denied = new HashSet<>();
        for (Message message : history) if (message instanceof ToolResponseMessage response)
            for (var row : response.getResponses()) if (row.responseData() != null && row.responseData().startsWith("[tool.denied]")) denied.add(row.id());
        Set<String> invalid = new HashSet<>();
        for (Message message : history) if (message instanceof AssistantMessage assistant)
            for (var call : assistant.getToolCalls()) if (denied.contains(call.id()) && !object(call.arguments())) invalid.add(call.id());
        if (invalid.isEmpty()) return new ArrayList<>(history);
        List<Message> clean = new ArrayList<>();
        for (Message message : history) {
            if (message instanceof AssistantMessage assistant && assistant.getToolCalls().stream().anyMatch(c -> invalid.contains(c.id()))) {
                var keep = assistant.getToolCalls().stream().filter(c -> !invalid.contains(c.id())).toList();
                if (!keep.isEmpty()) clean.add(new AssistantMessage(assistant.getText(), assistant.getMetadata(), keep));
            } else if (message instanceof ToolResponseMessage response) {
                var keep = response.getResponses().stream().filter(r -> !invalid.contains(r.id())).toList();
                if (!keep.isEmpty()) clean.add(new ToolResponseMessage(keep, response.getMetadata()));
            } else clean.add(message);
        }
        clean.add(new UserMessage("上次有工具参数不是完整JSON对象，已被拒绝且未执行。请重新生成完整JSON对象，检查所有嵌套对象的闭合括号；保留用户原配置和null，不补填或丢弃字段。"));
        return clean;
    }
    private static boolean object(String arguments) {
        try { return arguments != null && JSON.readTree(arguments).isObject(); }
        catch (Exception invalid) { return false; }
    }
}
