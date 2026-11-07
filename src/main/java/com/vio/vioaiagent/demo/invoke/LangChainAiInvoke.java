package com.vio.vioaiagent.demo.invoke;

import com.vio.vioaiagent.demo.invoke.TestApiKey;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;

public class LangChainAiInvoke {

    public static void main(String[] args) {
        ChatLanguageModel qwenChatModel = QwenChatModel.builder()
                .apiKey(TestApiKey.API_KEY)
                .modelName("qwen-max")
                .build();
        String answer = qwenChatModel.chat("你好，我是vio，欢迎来到我创建的AI超级智能体。");
        System.out.println(answer);
    }
}
