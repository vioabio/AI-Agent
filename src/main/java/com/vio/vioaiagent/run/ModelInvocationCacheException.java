package com.vio.vioaiagent.run;

/** 模型调用缓存损坏、不可用或未满足安全配置时的 fail-closed 异常。 */
public class ModelInvocationCacheException extends RuntimeException {
    public ModelInvocationCacheException(String message) {
        super(message);
    }

    public ModelInvocationCacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
