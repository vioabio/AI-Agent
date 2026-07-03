package com.vio.vioaiagent.demo.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;

import java.util.List;

/**
 * 多查询扩展示例
 * 演示预检索阶段：将用户问题扩展为多个不同角度的查询，提高检索召回率
 */
public class MultiQueryExpanderDemo {

    public static void run(ChatModel dashscopeChatModel) {
        ChatClient.Builder builder = ChatClient.builder(dashscopeChatModel);
        // 创建多查询扩展器，生成 3 个不同角度的查询
        MultiQueryExpander queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(builder)
                .numberOfQueries(3)
                .build();
        // 扩展查询
        List<Query> queries = queryExpander.expand(
                new Query("我是单身，怎么找对象？"));
        System.out.println("=== 多查询扩展结果 ===");
        queries.forEach(query -> System.out.println("  -> " + query.text()));
    }
}