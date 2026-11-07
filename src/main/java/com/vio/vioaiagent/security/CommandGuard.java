package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令黑名单 — 防止终端工具执行危险系统命令.
 *
 * <p>在 HITL 审批之前执行 fast-fail 拦截.
 * 内建 9 条常用危险命令模式, 同时支持从配置中加载自定义模式.
 *
 * <p>内建黑名单覆盖：
 * <ul>
 *   <li>sudo — 提权执行</li>
 *   <li>rm -rf / — 递归强制删除根目录</li>
 *   <li>mkfs.* — 格式化磁盘</li>
 *   <li>dd.*of=/dev/ — 直接写设备</li>
 *   <li>> /dev/sd[a-z] — 重定向覆盖磁盘</li>
 *   <li>fork bomb — :(){ :|:& };:</li>
 *   <li>curl|sh / wget|sh — 下载并执行脚本</li>
 *   <li>chmod 777 / — 递归开放所有权限</li>
 * </ul>
 *
 * @author vio
 */
@Slf4j
public class CommandGuard {

    private final List<Pattern> blockedPatterns;

    /** 内建危险命令模式 */
    private static final List<String> BUILTIN_BLOCKED = List.of(
            "sudo\\s+",
            "rm\\s+(-[rRf]+\\s+)*/",
            "mkfs\\.",
            "dd\\s+.*of=/dev/",
            ">\\s*/dev/sd[a-z]",
            ":\\\\(\\\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;:",
            "curl\\s+.*\\|\\s*(ba)?sh",
            "wget\\s+.*-O\\s+-\\s*\\|\\s*(ba)?sh",
            "chmod\\s+777\\s+/"
    );

    /**
     * 从安全配置属性构造命令黑名单.
     */
    public CommandGuard(SecurityProperties properties) {
        this(merge(properties.blockedCommands()));
    }

    /**
     * 构造命令黑名单（直接指定模式列表）.
     */
    public CommandGuard(List<String> customPatterns) {
        List<String> allPatterns = merge(customPatterns);
        this.blockedPatterns = new ArrayList<>();
        for (String pattern : allPatterns) {
            this.blockedPatterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
        }
        log.info("命令黑名单已初始化, 加载 {} 条禁止模式", this.blockedPatterns.size());
    }

    /**
     * 验证命令是否安全.
     *
     * @param command 待执行的命令字符串
     * @throws SecurityException 命令匹配黑名单时抛出
     */
    public void validate(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        for (Pattern pattern : blockedPatterns) {
            if (pattern.matcher(command).find()) {
                log.warn("危险命令拦截: 匹配模式 [{}] — 命令: {}", pattern.pattern(), command);
                throw new SecurityException(
                        "命令被安全策略拦截, 匹配规则: " + pattern.pattern());
            }
        }
    }

    /**
     * 合并内建黑名单和自定义模式.
     */
    private static List<String> merge(List<String> custom) {
        List<String> all = new ArrayList<>(BUILTIN_BLOCKED);
        if (custom != null && !custom.isEmpty()) {
            all.addAll(custom);
        }
        return List.copyOf(all);
    }
}
