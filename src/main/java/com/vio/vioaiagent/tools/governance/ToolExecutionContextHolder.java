package com.vio.vioaiagent.tools.governance;

/**
 * 将 Run 上下文传递给 Spring AI 的同步 ToolCallback。
 *
 * <p>Spring AI 1.0 的 {@code ToolCallback.call(String)} 没有业务上下文参数，
 * 因此在一次 manager 执行期间使用短生命周期 ThreadLocal 传递上下文。
 * 未显式设置时会回退到请求上下文，并在 finally 中清理。</p>
 */
public final class ToolExecutionContextHolder {

    private static final ThreadLocal<ToolExecutionContext> CURRENT = new ThreadLocal<>();

    private ToolExecutionContextHolder() {
    }

    public static ToolExecutionContext current() {
        ToolExecutionContext context = CURRENT.get();
        return context == null ? ToolExecutionContext.fromRequest() : context;
    }

    /** Raw binding for nested scopes; unlike current(), this does not create a fallback. */
    public static ToolExecutionContext peek() { return CURRENT.get(); }

    public static void set(ToolExecutionContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
