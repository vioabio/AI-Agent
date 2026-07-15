package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 从安全配置属性加载初始 API Keys.
     *
     * @param properties 安全配置属性
     */
    public TokenStore(SecurityProperties properties) {
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
            log.warn("API Key 已过期: {}", binding.tokenId());
            return null;
        }
        return binding;
    }

    /**
     * 动态注册新的令牌绑定.
     */
    public void register(TokenBinding binding) {
        tokenRegistry.put(binding.tokenId(), binding);
        log.info("已注册令牌: {}", binding.tokenId());
    }

    /**
     * 撤销令牌.
     */
    public void revoke(String apiKey) {
        tokenRegistry.remove(apiKey);
        log.info("已撤销令牌: {}", apiKey);
    }

    /** 当前令牌数量 */
    public int size() {
        return tokenRegistry.size();
    }
}
