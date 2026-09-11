package com.vio.vioaiagent.run;

/** 同一 scope/key 被不同 request hash 重用。 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String scope, String key) {
        super("幂等键与历史请求内容冲突: " + scope + "/" + key);
    }
}
