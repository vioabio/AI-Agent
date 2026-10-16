package com.vio.vioaiagent.run;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** 幂等记录存储；生产实现必须跨实例，不能依赖请求线程局部状态。 */
public interface IdempotencyStore {
    /** Read without creating a claim; overload rejection must not consume a retry key. */
    default Optional<IdempotencyRecord> find(String scope, String key) { return Optional.empty(); }
    Claim claim(String scope, String key, String requestHash, Duration ttl, Instant now);
    IdempotencyRecord complete(String scope, String key, String requestHash, int responseStatus,
                               Map<String, Object> responseBody, Instant now);
    int deleteExpired(Instant now, int limit);

    record Claim(boolean accepted, boolean replay, boolean conflict, Optional<IdempotencyRecord> record) {
        public Claim {
            record = record == null ? Optional.empty() : record;
        }
        /** 无幂等键或存储未启用时的放行结果。 */
        public static Claim acceptedClaim() { return new Claim(true, false, false, Optional.empty()); }
        public static Claim replay(IdempotencyRecord value) { return new Claim(false, true, false, Optional.of(value)); }
        public static Claim conflict(IdempotencyRecord value) { return new Claim(false, false, true, Optional.ofNullable(value)); }
    }
}
