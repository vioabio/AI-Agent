package com.vio.vioaiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * ReAct（Reasoning + Acting）模式的智能体抽象类
 * <p>
 * 实现了"思考 → 行动"的循环模式，模仿人类解决问题的认知过程：
 * <ol>
 *   <li><b>推理（Reason/Think）</b>：分析当前状态，决定是否需要调用工具</li>
 *   <li><b>行动（Act）</b>：执行工具调用，获取外部信息</li>
 *   <li><b>观察（Observe）</b>：工具结果自动加入消息上下文，供下一步推理使用</li>
 * </ol>
 * <p>
 * 子类必须实现 {@link #think()} 和 {@link #act()} 方法。
 * 具体实现见 {@link ToolCallAgent}。
 *
 * @author vio
 * @see ToolCallAgent
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent {

    /**
     * 思考阶段：分析当前状态并决定下一步行动
     * <p>
     * 典型实现流程（ToolCallAgent）：
     * <ol>
     *   <li>将 nextStepPrompt 追加到消息列表，引导 AI 主动规划</li>
     *   <li>调用 ChatClient 传入系统提示词 + 可用工具列表</li>
     *   <li>解析 AI 返回的 ToolCall 列表</li>
     *   <li>有工具调用 → 返回 true；无工具调用 → 返回 false</li>
     * </ol>
     *
     * @return true 表示需要执行工具调用（进入 act），false 表示无需行动
     */
    public abstract boolean think();

    /**
     * 行动阶段：执行 think() 阶段决定的工具调用
     * <p>
     * 典型实现流程（ToolCallAgent）：
     * <ol>
     *   <li>通过 ToolCallingManager 执行 AI 选择的工具</li>
     *   <li>将工具返回结果追加到消息上下文</li>
     *   <li>检测是否调用了 terminate 工具 → 设置 state = FINISHED</li>
     *   <li>返回工具执行结果描述</li>
     * </ol>
     *
     * @return 工具执行结果的描述文本
     */
    public abstract String act();

    /**
     * 执行单个步骤：先思考，再行动
     * <p>
     * 重写父类 {@link BaseAgent#step()}，实现 ReAct 的核心循环：
     * <pre>
     *   think() → 需要行动? → act() → 观察结果 → 下一轮 think()
     * </pre>
     *
     * @return 步骤执行结果
     */
    @Override
    public String step() {
        try {
            // 第一步：思考
            boolean shouldAct = think();
            if (!shouldAct) {
                return "思考完成 - 无需行动";
            }
            // 第二步：行动
            return act();
        } catch (Exception e) {
            log.error("ReAct 步骤执行失败", e);
            return "步骤执行失败：" + e.getMessage();
        }
    }
}