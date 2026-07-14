package com.vio.vioaiagent.demo.invoke;

/**
 * 仅用于测试获取 API Key
 * <p>
 * 从系统属性或环境变量读取，不硬编码在源码中。
 * 设置方式（任选其一）：
 * <ul>
 *   <li>环境变量：export AI_DASHSCOPE_API_KEY=your-key</li>
 *   <li>JVM 参数：-DAI_DASHSCOPE_API_KEY=your-key</li>
 *   <li>IDE 运行配置中设置环境变量</li>
 * </ul>
 * <p>
 * 主应用的 Key 在 application-local.yml 中配置（该文件已被 .gitignore 忽略）。
 */
public interface TestApiKey {

    String API_KEY = System.getProperty("AI_DASHSCOPE_API_KEY",
            System.getenv("AI_DASHSCOPE_API_KEY"));
}
