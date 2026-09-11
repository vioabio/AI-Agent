package com.vio.vioaiagent.run;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 无状态资源游标编码器。
 *
 * <p>游标只包含资源名、用户摘要、offset 和过期时间，并使用 HMAC-SHA256
 * 防止客户端篡改或把一个用户的游标重放给另一个用户。正文不进入游标，
 * 因此不会泄漏会话标题、邮箱或其他资源字段。</p>
 */
public final class ResourceCursorCodec {
    private static final String VERSION = "v1";
    private static final int MAX_OFFSET = 1_000_000;
    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public ResourceCursorCodec(String secret, Duration ttl) {
        this(secret, ttl, Clock.systemUTC());
    }

    public ResourceCursorCodec(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("游标签名密钥不能为空");
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("游标 TTL 必须在 1 秒到 7 天之间");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public String encode(String resource, String ownerId, int offset) {
        if (resource == null || resource.isBlank() || ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("资源和主体不能为空");
        }
        if (offset < 0 || offset > MAX_OFFSET) throw new IllegalArgumentException("资源游标 offset 无效");
        long expiresAt = clock.instant().plus(ttl).getEpochSecond();
        String payload = VERSION + "|" + encodePart(resource.trim()) + "|" + ownerDigest(ownerId)
                + "|" + offset + "|" + expiresAt;
        String body = base64(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + base64(sign(payload));
    }

    public Cursor decode(String token, String expectedResource, String ownerId) {
        if (token == null || token.isBlank() || token.length() > 1024) {
            throw new InvalidResourceCursorException(token);
        }
        if (expectedResource == null || expectedResource.isBlank() || ownerId == null || ownerId.isBlank()) {
            throw new InvalidResourceCursorException(token);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) throw new InvalidResourceCursorException(token);
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payload), actualSignature)) {
                throw new InvalidResourceCursorException(token);
            }
            String[] fields = payload.split("\\|", -1);
            if (fields.length != 5 || !VERSION.equals(fields[0])
                    || !encodePart(expectedResource.trim()).equals(fields[1])
                    || !ownerDigest(ownerId).equals(fields[2])) {
                throw new InvalidResourceCursorException(token);
            }
            int offset = Integer.parseInt(fields[3]);
            long expiresAt = Long.parseLong(fields[4]);
            if (offset < 0 || offset > MAX_OFFSET || expiresAt <= 0) {
                throw new InvalidResourceCursorException(token);
            }
            if (!Instant.ofEpochSecond(expiresAt).isAfter(clock.instant())) {
                throw new ResourceCursorExpiredException();
            }
            return new Cursor(expectedResource.trim(), offset, Instant.ofEpochSecond(expiresAt));
        } catch (ResourceCursorExpiredException | InvalidResourceCursorException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new InvalidResourceCursorException(token);
        }
    }

    public record Cursor(String resource, int offset, Instant expiresAt) { }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成资源游标签名", ex);
        }
    }

    private static String ownerDigest(String ownerId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ownerId.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format("%02x", item));
            return value.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private static String encodePart(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
