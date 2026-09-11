package com.vio.vioaiagent.run;

import java.util.function.Function;

/**
 * 明确声明“只包含可安全重放模型调用”的回答生成器。
 *
 * <p>带工具、写入或其他外部副作用的 Agent 生成器不得实现/标记此接口；
 * 这样缓存不会把整段 VioManus 执行误当成幂等模型步骤。</p>
 */
@FunctionalInterface
public interface ReplayableAnswerGenerator extends Function<String, String> {
}
