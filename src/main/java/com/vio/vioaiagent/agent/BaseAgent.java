package com.vio.vioaiagent.agent;

import cn.hutool.core.util.StrUtil;
import com.vio.vioaiagent.agent.model.AgentState;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 智能体抽象基类
 * <p>
 * 提供智能体的核心基础设施：
 * <ul>
 *   <li><b>状态管理</b>：IDLE → RUNNING → FINISHED / ERROR 的生命周期控制</li>
 *   <li><b>Agent Loop</b>：基于步数的自主执行循环，直到任务完成或达到最大步数</li>
 *   <li><b>消息记忆</b>：自主维护会话上下文（messageList），不依赖 Spring AI 的 ChatMemory</li>
 *   <li><b>双模式执行</b>：同步 {@link #run(String)} 和 SSE 流式 {@link #runStream(String)}</li>
 * </ul>
 * <p>
 * 采用<b>模板方法设计模式</b>：父类定义执行流程，子类通过实现 {@link #step()} 来决定每步的具体行为。
 * <p>
 * 类继承体系：
 * <pre>
 *   BaseAgent → ReActAgent → ToolCallAgent → VioManus
 * </pre>
 *
 * @author vio
 */
@Data
@Slf4j
@Hidden
public abstract class BaseAgent {

    // ==================== 核心属性 ====================

    /**
     * 智能体名称
     */
    private String name;

    /**
     * 系统提示词 — 定义智能体的角色和能力
     */
    private String systemPrompt;

    /**
     * 下一步提示词 — 引导 AI 主动分解任务并选择工具
     */
    private String nextStepPrompt;

    // ==================== 状态控制 ====================

    /**
     * 智能体当前状态
     */
    private AgentState state = AgentState.IDLE;

    /**
     * 当前执行步数（从 1 开始）
     */
    private int currentStep = 0;

    /**
     * 最大执行步数（安全阀，防止无限循环消耗 Token）
     */
    private int maxSteps = 10;

    /**
     * 手动停止标志（volatile 保证跨线程可见性）
     */
    private volatile boolean stopped = false;

    // ==================== AI 大模型 ====================

    /**
     * AI 对话客户端（由子类或调用方注入）
     */
    private ChatClient chatClient;

    // ==================== 记忆系统 ====================

    /**
     * 消息上下文列表（自主维护，不依赖 Spring AI 的 ChatMemory）
     * <p>
     * 存储用户消息、AI 助手消息、工具调用消息和工具返回消息的完整历史
     */
    private List<Message> messageList = new ArrayList<>();

    // ==================== 同步执行 ====================

    /**
     * 同步运行智能体
     *
     * @param userPrompt 用户的输入/任务描述
     * @return 所有步骤的执行结果（用换行符分隔）
     * @throws RuntimeException 如果智能体状态不是 IDLE 或用户输入为空
     */
    public String run(String userPrompt) {
        // 1. 基础校验
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("无法从状态 " + this.state + " 运行智能体，当前仅允许从 IDLE 状态启动");
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("不能使用空的用户提示词运行智能体");
        }

        // 2. 状态切换 + 记录用户消息 + 注入可观测性上下文
        this.state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));

        com.vio.vioaiagent.observability.AgentLogContext logCtx =
                new com.vio.vioaiagent.observability.AgentLogContext();
        logCtx.sessionId(this.getName());
        logCtx.agentType(this.getName() != null ? this.getName() : "Agent");
        logCtx.injectMdc();

        // 3. Agent Loop：循环执行直到完成或超步数
        List<String> results = new ArrayList<>();
        try {
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED && !stopped; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("{} 正在执行步骤 {}/{}", name, stepNumber, maxSteps);

                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }

            if (stopped) {
                state = AgentState.FINISHED;
                results.add("用户手动停止了任务");
            } else if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("执行终止：已达到最大步数限制（" + maxSteps + "）");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("智能体 {} 执行出错", name, e);
            return "执行错误：" + e.getMessage();
        } finally {
            // 4. 清理资源
            this.cleanup();
        }
    }

    // ==================== SSE 流式执行 ====================

    /**
     * SSE 流式运行智能体（创建新的 SseEmitter）
     */
    public SseEmitter runStream(String userPrompt) {
        SseEmitter sseEmitter = new SseEmitter(300000L);
        CompletableFuture.runAsync(() -> doRunStream(userPrompt, sseEmitter));
        sseEmitter.onTimeout(() -> { this.state = AgentState.ERROR; this.cleanup(); });
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) this.state = AgentState.FINISHED;
            this.cleanup();
        });
        return sseEmitter;
    }

    /**
     * SSE 流式运行智能体（创建新的 SseEmitter, 使用专用线程池）.
     *
     * @param userPrompt 用户消息
     * @param executor   专用线程池（null 则使用默认 ForkJoinPool）
     */
    public SseEmitter runStream(String userPrompt,
                                 java.util.concurrent.Executor executor) {
        SseEmitter sseEmitter = new SseEmitter(300000L);
        if (executor != null) {
            CompletableFuture.runAsync(() -> doRunStream(userPrompt, sseEmitter), executor);
        } else {
            CompletableFuture.runAsync(() -> doRunStream(userPrompt, sseEmitter));
        }
        sseEmitter.onTimeout(() -> { this.state = AgentState.ERROR; this.cleanup(); });
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) this.state = AgentState.FINISHED;
            this.cleanup();
        });
        return sseEmitter;
    }

    /**
     * SSE 流式运行智能体（写入已有 emitter, 用于外部管理生命周期）
     */
    public void runStream(String userPrompt, SseEmitter sseEmitter) {
        CompletableFuture.runAsync(() -> doRunStream(userPrompt, sseEmitter));
    }

    /**
     * SSE 流式运行智能体（写入已有 emitter, 使用专用线程池）.
     */
    public void runStream(String userPrompt, SseEmitter sseEmitter,
                           java.util.concurrent.Executor executor) {
        if (executor != null) {
            CompletableFuture.runAsync(() -> doRunStream(userPrompt, sseEmitter), executor);
        } else {
            CompletableFuture.runAsync(() -> doRunStream(userPrompt, sseEmitter));
        }
    }

    /**
     * SSE 流式执行的核心逻辑
     */
    private void doRunStream(String userPrompt, SseEmitter sseEmitter) {
        try {
            // 1. 基础校验
            if (this.state != AgentState.IDLE) {
                sseEmitter.send("错误：无法从状态 " + this.state + " 运行智能体");
                sseEmitter.complete();
                return;
            }
            if (StrUtil.isBlank(userPrompt)) {
                sseEmitter.send("错误：不能使用空的用户提示词运行智能体");
                sseEmitter.complete();
                return;
            }
        } catch (Exception e) {
            sseEmitter.completeWithError(e);
            return;
        }

        // 2. 状态切换 + 记录用户消息 + 注入可观测性上下文
        this.state = AgentState.RUNNING;
        messageList.add(new UserMessage(userPrompt));

        com.vio.vioaiagent.observability.AgentLogContext sseLogCtx =
                new com.vio.vioaiagent.observability.AgentLogContext();
        sseLogCtx.sessionId(com.vio.vioaiagent.common.RequestContext.getTraceId());
        sseLogCtx.agentType(this.getName() != null ? this.getName() : "Agent");
        sseLogCtx.stepType("stream");
        sseLogCtx.injectMdc();

        // 3. Agent Loop（SSE 实时推送每步结果）
        try {
            sseEmitter.send("正在分析任务：「" + userPrompt + "」...");

            for (int i = 0; i < maxSteps && state != AgentState.FINISHED && !stopped; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("{} 正在执行步骤 {}/{}", name, stepNumber, maxSteps);

                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                sseEmitter.send(result);
            }

            if (stopped) {
                state = AgentState.FINISHED;
                sseEmitter.send("⏹ 用户手动停止了任务");
            } else if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                sseEmitter.send("执行终止：已达到最大步数限制（" + maxSteps + "）");
            }

            sseEmitter.complete();
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("智能体 {} SSE 执行出错", name, e);
            try {
                sseEmitter.send("执行错误：" + e.getMessage());
                sseEmitter.complete();
            } catch (IOException ex) {
                sseEmitter.completeWithError(ex);
            }
        } finally {
            this.cleanup();
        }
    }

    // ==================== 抽象方法 ====================

    /**
     * 执行单个步骤（模板方法中的可变部分）
     * <p>
     * 子类必须实现此方法来定义每步的具体行为。
     * 在 ReActAgent 中，step() 被重写为 think() + act() 的执行流程。
     *
     * @return 步骤执行结果描述
     */
    public abstract String step();

    // ==================== 资源清理 ====================

    /**
     * 手动停止 Agent（线程安全）
     */
    public void stop() {
        this.stopped = true;
        log.info("{} 收到停止信号", name);
    }

    /**
     * 清理资源（子类可按需重写）
     */
    protected void cleanup() {
        this.stopped = false;
    }
}
