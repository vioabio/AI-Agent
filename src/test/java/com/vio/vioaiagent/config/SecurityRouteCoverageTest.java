package com.vio.vioaiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 身份接线覆盖面回归 — 防止再次出现「业务路由未进入认证拦截器」的静默缺口。
 *
 * <p>背景（升级方案 F02）：{@code ApiKeyAuthInterceptor} 是当前唯一填充
 * {@code RequestContext} 主体信息的位置。若某条业务路由不在拦截器路径模式内，
 * 即使调用方持有合法 API Key，{@code ResourceController.owner()} 仍会因为
 * 主体为空而抛出 {@code UnauthenticatedResourceException} 返回 401。
 *
 * <p>历史缺陷：{@code /conversations/**}、{@code /teams/**}、{@code /favorites/**}
 * 三条真实业务根路径没有被登记，且当时没有任何测试能发现。
 *
 * <p>实现说明：本测试直接从控制器源码解析 {@code @RequestMapping} 与
 * {@code @GetMapping} 等注解，再与 {@link SecurityConfig} 的路径契约比较。
 * 之所以不启动 Spring 上下文，是为了让「路由覆盖面」这一纯契约校验保持快速、
 * 无副作用，并与 {@code ResourceIdentityHttpTest} 的真实 HTTP 行为验证互补。
 */
@DisplayName("身份接线覆盖面回归")
class SecurityRouteCoverageTest {

    /** 控制器源码根目录（相对模块根）。 */
    private static final Path CONTROLLER_ROOT =
            Path.of("src", "main", "java", "com", "vio", "vioaiagent", "controller");

    /** 由框架/文档组件自行注册、不出现在控制器源码中的公开前缀。 */
    private static final List<String> FRAMEWORK_PUBLIC_PREFIXES =
            List.of("/actuator", "/swagger-ui", "/v3/api-docs");

    /** 对外业务根路径；这些前缀永远不允许进入公开白名单。 */
    private static final List<String> BUSINESS_ROOT_PATHS = List.of(
            "/chat", "/conversations", "/teams", "/favorites", "/answers",
            "/knowledge", "/hitl", "/memory", "/pokemon", "/admin", "/metrics", "/ai");

    /** Spring 框架自身的错误转发端点，不承载业务数据。 */
    private static final Set<String> FRAMEWORK_EXEMPT_PATTERNS = Set.of("/error");

    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Patch|Delete)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern BARE_METHOD_MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Patch|Delete)Mapping\\s*[\\r\\n]");

    @Test
    @DisplayName("所有控制器映射都必须被明确判定为受保护或公开")
    void everyControllerMappingIsExplicitlyClassified() throws IOException {
        List<String> mappings = controllerMappings();
        assertTrue(mappings.size() > 20,
                () -> "从控制器源码只解析到 " + mappings.size() + " 个映射，"
                        + "解析规则可能已失效（注解写法变化），覆盖面回归会静默失去保护作用");

        List<String> unclassified = new ArrayList<>();
        for (String mapping : mappings) {
            if (matches(SecurityConfig.AUTHENTICATED_PATH_PATTERNS, mapping)
                    || matches(SecurityConfig.PUBLIC_PATH_PATTERNS, mapping)
                    || FRAMEWORK_EXEMPT_PATTERNS.contains(mapping)) {
                continue;
            }
            unclassified.add(mapping);
        }

        assertTrue(unclassified.isEmpty(),
                () -> "以下路由既未登记为受保护路径，也不在公开白名单中。"
                        + "它们不会经过身份拦截器，RequestContext 主体为空会导致 401。"
                        + "请在 SecurityConfig.AUTHENTICATED_PATH_PATTERNS 中登记，"
                        + "或说明为何应公开：\n  " + String.join("\n  ", unclassified));
    }

    @Test
    @DisplayName("公开白名单保持最小：只允许探针与文档接口")
    void publicAllowlistIsMinimal() {
        for (String publicPattern : SecurityConfig.PUBLIC_PATH_PATTERNS) {
            boolean allowed = FRAMEWORK_PUBLIC_PREFIXES.stream().anyMatch(publicPattern::startsWith);
            assertTrue(allowed || publicPattern.equals("/health") || publicPattern.equals("/ready"),
                    () -> "公开白名单出现非探针/非文档条目: " + publicPattern
                            + "。公开面扩大必须经过评审，不能静默加入");
        }

        for (String publicPattern : SecurityConfig.PUBLIC_PATH_PATTERNS) {
            for (String business : BUSINESS_ROOT_PATHS) {
                assertTrue(!publicPattern.startsWith(business),
                        () -> "业务路径 " + business + " 不得出现在公开白名单: " + publicPattern);
            }
        }
    }

    @Test
    @DisplayName("受保护集合与公开白名单不得出现同一路径")
    void protectedAndPublicSetsDoNotOverlap() {
        // 只拒绝「同一条路径既公开又要求认证」这种真实矛盾。
        // 父子路径并存是有意设计：/ready 供编排探活匿名使用，
        // /ready/details 暴露更详细的内部状态，必须要求探针凭据。
        for (String authenticated : SecurityConfig.AUTHENTICATED_PATH_PATTERNS) {
            for (String publicPattern : SecurityConfig.PUBLIC_PATH_PATTERNS) {
                assertTrue(!authenticated.equals(publicPattern),
                        () -> "同一路径不能既要求认证又公开: " + authenticated + " vs " + publicPattern);
            }
        }
    }

    @Test
    @DisplayName("/ready/details 必须保持受保护，不能随 /ready 一起公开")
    void readyDetailsStaysProtected() {
        assertTrue(SecurityConfig.AUTHENTICATED_PATH_PATTERNS.contains("/ready/details"),
                "就绪明细暴露内部依赖状态，不得匿名可读");
        assertTrue(SecurityConfig.PUBLIC_PATH_PATTERNS.contains("/ready"),
                "/ready 必须保持匿名可用，否则编排系统无法探活");
    }

    @Test
    @DisplayName("N2/N4 根资源路径必须处于受保护列表内")
    void rootResourcePathsAreProtected() {
        // 直接锁定 F02 的三个缺陷路径，避免有人「清理」常量时又把它们删掉。
        for (String required : List.of("/conversations/**", "/teams/**", "/favorites/**")) {
            assertTrue(SecurityConfig.AUTHENTICATED_PATH_PATTERNS.contains(required),
                    () -> "根资源路径必须登记为受保护: " + required);
        }
    }

    // ==================== 解析与匹配 ====================

    private static List<String> controllerMappings() throws IOException {
        Set<String> mappings = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(CONTROLLER_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher classMatcher = CLASS_MAPPING.matcher(source);
                String prefix = classMatcher.find() ? classMatcher.group(1) : "";

                Matcher methodMatcher = METHOD_MAPPING.matcher(source);
                while (methodMatcher.find()) {
                    mappings.add(normalize(prefix + methodMatcher.group(1)));
                }
                // 形如 @GetMapping（无路径参数）的映射等价于类前缀本身。
                Matcher bareMatcher = BARE_METHOD_MAPPING.matcher(source);
                while (bareMatcher.find()) {
                    mappings.add(normalize(prefix));
                }
            }
        }
        return List.copyOf(mappings);
    }

    private static String normalize(String pattern) {
        if (pattern == null || pattern.isBlank()) return "/";
        String value = pattern.trim();
        if (!value.startsWith("/")) value = "/" + value;
        while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static boolean matches(List<String> candidates, String actual) {
        for (String candidate : candidates) {
            if (patternsIntersect(candidate, actual)) return true;
        }
        return false;
    }

    /**
     * 判断两个 Ant 风格模式是否存在覆盖关系。
     *
     * <p>双向判定：拦截器用 {@code /conversations/**} 覆盖具体映射
     * {@code /conversations} 与 {@code /conversations/{id}}，因此不能只做单向前缀比较。
     */
    private static boolean patternsIntersect(String candidate, String actual) {
        String base = candidate.endsWith("/**") ? candidate.substring(0, candidate.length() - 3) : candidate;
        if (base.isEmpty()) base = "/";
        return actual.equals(base) || actual.startsWith(base + "/") || actual.equals(candidate);
    }
}
