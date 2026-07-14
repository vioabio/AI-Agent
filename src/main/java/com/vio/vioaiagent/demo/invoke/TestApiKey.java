package com.vio.vioaiagent.demo.invoke;

/**
 * 仅用于测试获取 API Key
 * <p>
 * 从环境变量 AI_DASHSCOPE_API_KEY 读取，不硬编码在源码中。
 * 启动前请设置环境变量，或在 IDE 运行配置中设置。
 */
public interface TestApiKey {

    String API_KEY = System.getenv("AI_DASHSCOPE_API_KEY");
}
