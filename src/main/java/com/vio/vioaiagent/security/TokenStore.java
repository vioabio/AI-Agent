package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 令牌存储 — 管理 API Key 的验证和绑定信息.
 *
 * <p>启动时从 {@link SecurityProperties} 加载配置的 API Keys,
 * 运行时可通过 API 动态添加/撤销令牌. 线程安全（基于 ConcurrentHashMap）.
 *
 * <p>YAML 配置格式:
 * <pre>{@code
 * vio:
 *   security:
 *     api-keys:
 *       "sk-xxx-001": "MCP 管理令牌:amap-maps"
 *       "sk-xxx-002": "只读令牌:image-search"
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class TokenStore {

    private final Map<String, TokenBinding> tokenRegistry = new ConcurrentHashMap<>();
    /** 进程内滑动分钟窗口；生产多实例部署应替换为 Redis/网关限流实现。 */
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final GlobalQuotaStore globalQuotaStore;

    /**
     * 从安全配置属性加载初始 API Keys.
     *
     * @param properties 安全配置属性
     */
    public TokenStore(SecurityProperties properties) {
        this(properties, null);
    }

    /** 可选跨实例配额适配器；未注入时沿用本地窗口，便于离线测试。 */
    public TokenStore(SecurityProperties properties, GlobalQuotaStore globalQuotaStore) {
        this.globalQuotaStore = globalQuotaStore;
        if (properties.apiKeys() != null) {
            properties.apiKeys().forEach((key, desc) -> {
                // 解析描述: "描述文本:协议ID" 格式
                String protocolId = "default";
                String[] parts = desc.split(":");
                if (parts.length >= 2) {
                    protocolId = parts[parts.length - 1].trim();
                }
                TokenBinding binding = TokenBinding.unlimited(key, protocolId);
                tokenRegistry.put(key, binding);
            });
            log.info("已加载 {} 个 API Key 到令牌存储", tokenRegistry.size());
        }
    }

    /**
     * 验证 API Key 是否有效.
     *
     * @param apiKey API Key 字符串
     * @return 对应的令牌绑定, 无效/过期返回 null
     */
    public TokenBinding validate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        TokenBinding binding = tokenRegistry.get(apiKey);
        if (binding == null) {
            return null;
        }
        if (binding.isExpired()) {
            // tokenId 即密钥本身；日志只允许输出脱敏前缀，避免过期令牌落盘。
            log.warn("API Key 已过期: {}", maskToken(binding.tokenId()));
            return null;
        }
        return binding;
    }

    /** 校验令牌工具白名单；null 表示允许。 */
    public String authorizeTool(TokenBinding binding, String toolName) {
        if (binding == null) return null;
        // 绑定可能来自异步 Run 的内存快照；每次工具调用仍需回到当前注册表，
        // 这样撤销/过期会在下一次副作用前生效，而不会被旧快照绕过。
        TokenBinding currentBinding = tokenRegistry.get(binding.tokenId());
        if (currentBinding == null || binding.isExpired() || currentBinding.isExpired()
                || !(currentBinding.equals(binding) || sameOidcAuthorization(currentBinding, binding))) {
            return "令牌已撤销或过期";
        }
        binding = currentBinding;
        if (toolName == null || !binding.allowsTool(toolName)) {
            return "令牌未授权调用工具: " + toolName;
        }
        return null;
    }

    /**
     * A browser's next request refreshes expiry for the same OIDC policy key.
     * Keep an existing Run usable within its own original lifetime, without
     * letting a changed role/tool/tenant/quota policy reuse the old snapshot.
     * API keys deliberately retain exact record equality.
     */
    private boolean sameOidcAuthorization(TokenBinding current, TokenBinding captured) {
        return current.isOidcPolicyBinding() && captured.isOidcPolicyBinding()
                && current.tokenId().equals(captured.tokenId())
                && current.protocolId().equals(captured.protocolId())
                && current.subjectId().equals(captured.subjectId())
                && current.tenantId().equals(captured.tenantId())
                && current.roles().equals(captured.roles())
                && current.allowedTools().equals(captured.allowedTools())
                && current.policyVersion().equals(captured.policyVersion())
                && current.maxRequestsPerMinute() == captured.maxRequestsPerMinute();
    }

    /**
     * 消费一次频率额度。仅在 Schema/策略/HITL/幂等检查通过且即将产生实际工具
     * 副作用时调用，避免审批等待或幂等重放误耗尽令牌额度。
     */
    public String consumeRate(TokenBinding binding) {
        if (binding == null) return null;
        int limit = binding.maxRequestsPerMinute();
        if (limit <= 0) return null;
        if (globalQuotaStore != null) {
            GlobalQuotaStore.QuotaDecision decision;
            try {
                decision = globalQuotaStore.consume("token-minute", digest(binding.tokenId()), limit,
                        Duration.ofMinutes(1), Instant.now());
            } catch (RuntimeException ex) {
                // 全局配额故障必须 fail-closed，不能回退到单机窗口让多实例绕过限制。
                log.warn("全局配额存储不可用，拒绝令牌调用: {}", ex.getClass().getSimpleName());
                return "全局配额服务不可用，请稍后重试";
            }
            if (!decision.allowed()) {
                long retry = decision.retryAfterSeconds(Instant.now());
                return "令牌调用频率超限（每分钟最多 " + limit + " 次），请在 " + retry + " 秒后重试";
            }
            return null;
        }
        long window = System.currentTimeMillis() / 60_000L;
        RateWindow current = rateWindows.computeIfAbsent(binding.tokenId(), key -> new RateWindow(window));
        if (current.window != window) {
            synchronized (current) {
                if (current.window != window) {
                    current.window = window;
                    current.count.set(0);
                }
            }
        }
        while (true) {
            int used = current.count.get();
            if (used >= limit) return "令牌调用频率超限（每分钟最多 " + limit + " 次）";
            if (current.count.compareAndSet(used, used + 1)) return null;
        }
    }

    /**
     * 动态注册新的令牌绑定.
     */
    public void register(TokenBinding binding) {
        tokenRegistry.put(binding.tokenId(), binding);
        log.info("已注册令牌: {}", maskToken(binding.tokenId()));
    }

    /**
     * 撤销令牌.
     */
    public void revoke(String apiKey) {
        tokenRegistry.remove(apiKey);
        rateWindows.remove(apiKey);
        log.info("已撤销令牌: {}", maskToken(apiKey));
    }

    /** 当前令牌数量 */
    public int size() {
        return tokenRegistry.size();
    }

    private static final class RateWindow {
        private volatile long window;
        private final AtomicInteger count = new AtomicInteger();

        private RateWindow(long window) {
            this.window = window;
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 6) + "***";
    }

    private String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JRE 不支持 SHA-256", ex);
        }
    }
}
