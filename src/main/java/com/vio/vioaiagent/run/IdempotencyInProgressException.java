package com.vio.vioaiagent.run;

/** 同一幂等键正在由另一请求处理，调用方应稍后用同一键重试。 */
public class IdempotencyInProgressException extends RuntimeException {
    private final String scope;
    private final String key;

    public IdempotencyInProgressException(String scope, String key) {
        super("幂等请求仍在处理中");
        this.scope = scope;
        this.key = key;
    }

    public String scope() { return scope; }
    public String key() { return key; }
}
