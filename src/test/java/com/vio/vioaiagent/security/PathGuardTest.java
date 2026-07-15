package com.vio.vioaiagent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PathGuard 测试.
 *
 * @author vio
 */
@DisplayName("路径围栏")
class PathGuardTest {

    private PathGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PathGuard(List.of(
                System.getProperty("user.dir") + "/tmp",
                "/var/log"
        ));
    }

    @Test
    @DisplayName("白名单内路径应通过验证")
    void shouldAllowPathInWhitelist() {
        String path = System.getProperty("user.dir") + "/tmp/file/output.txt";
        assertDoesNotThrow(() -> guard.validate(path));
    }

    @Test
    @DisplayName("路径穿越 '..' 应被拦截")
    void shouldBlockPathTraversal() {
        assertThrows(SecurityException.class,
                () -> guard.validate("../../Windows/System32/config/SAM"));
    }

    @Test
    @DisplayName("绝对路径越权应被拦截")
    void shouldBlockAbsolutePathOutsideRoot() {
        String osRoot = System.getProperty("os.name").toLowerCase().contains("win")
                ? "C:\\Windows\\System32\\config\\SAM"
                : "/etc/passwd";
        assertThrows(SecurityException.class,
                () -> guard.validate(osRoot));
    }

    @Test
    @DisplayName("空路径应被拦截")
    void shouldBlockEmptyPath() {
        assertThrows(SecurityException.class, () -> guard.validate(""));
        assertThrows(SecurityException.class, () -> guard.validate(null));
    }

    @Test
    @DisplayName("白名单内正常路径应通过")
    void shouldAllowNormalPaths() {
        String path = System.getProperty("user.dir") + "/tmp/file/test.txt";
        assertDoesNotThrow(() -> guard.validate(path));
    }
}
