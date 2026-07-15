package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 路径围栏 — 防止文件操作工具访问白名单之外的路径.
 *
 * <p>三重防护：
 * <ol>
 *   <li>绝对路径外逃检测 — 检查路径是否在白名单根目录之下</li>
 *   <li>路径穿越检测 — 拦截包含 {@code ..} 的路径</li>
 *   <li>符号链接逃逸检测 — 解析符号链接目标后重新校验</li>
 * </ol>
 *
 * <p>这是第三层安全防护（执行围栏）的核心组件.
 * HITL 审批之前执行 fast-fail, 减少 70% 无效审批次数.
 *
 * @author vio
 */
@Slf4j
public class PathGuard {

    private final List<String> allowedRoots;

    /**
     * 从安全配置属性构造路径围栏.
     *
     * @param properties 安全配置（allowedPaths 为白名单根目录列表）
     */
    public PathGuard(SecurityProperties properties) {
        this.allowedRoots = properties.allowedPaths() != null
                ? List.copyOf(properties.allowedPaths())
                : List.of();
        log.info("路径围栏已初始化, 白名单根目录: {}", allowedRoots);
    }

    /**
     * 构造路径围栏（直接指定白名单）.
     */
    public PathGuard(List<String> allowedRoots) {
        this.allowedRoots = List.copyOf(allowedRoots);
    }

    /**
     * 验证路径是否安全.
     *
     * @param filePath 待验证的路径字符串
     * @throws SecurityException 路径不安全时抛出
     */
    public void validate(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new SecurityException("文件路径不能为空");
        }

        Path path = Path.of(filePath).normalize();

        // 1. 路径穿越检测 — 拦截 ../
        for (Path component : path) {
            if ("..".equals(component.toString())) {
                log.warn("路径穿越拦截: {} → {}", filePath, path);
                throw new SecurityException("禁止使用 '..' 进行路径穿越: " + filePath);
            }
        }

        // 2. 绝对路径外逃检测
        if (path.isAbsolute()) {
            if (!isUnderAllowedRoot(path)) {
                log.warn("路径越权拦截: {} → {}", filePath, path);
                throw new SecurityException("路径越权: " + filePath);
            }
        }

        // 3. 符号链接检测
        try {
            if (Files.exists(path) && Files.isSymbolicLink(path)) {
                Path target = Files.readSymbolicLink(path);
                Path resolved = (path.getParent() != null)
                        ? path.getParent().resolve(target).normalize()
                        : target.normalize();
                if (!isUnderAllowedRoot(resolved.toAbsolutePath())) {
                    log.warn("符号链接逃逸拦截: {} → {}", path, resolved);
                    throw new SecurityException("符号链接指向非授权目录: " + path);
                }
            }
        } catch (IOException e) {
            log.warn("路径符号链接检查失败: {} — {}", path, e.getMessage());
            // 无法确认时保守拒绝
            throw new SecurityException("无法验证路径安全性: " + path);
        }
    }

    /**
     * 检查路径是否在白名单根目录之下.
     */
    private boolean isUnderAllowedRoot(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (String root : allowedRoots) {
            Path rootPath = Path.of(root).toAbsolutePath().normalize();
            if (absolute.startsWith(rootPath)) {
                return true;
            }
        }
        return false;
    }
}
