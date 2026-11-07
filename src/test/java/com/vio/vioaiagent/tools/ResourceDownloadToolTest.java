package com.vio.vioaiagent.tools;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceDownloadToolTest {

    @Test
    void rejectsTraversalAndControlFileNames() {
        assertEquals("team.png", ResourceDownloadTool.safeFileName("team.png"));
        assertNull(ResourceDownloadTool.safeFileName("../team.png"));
        assertNull(ResourceDownloadTool.safeFileName("C:\\temp\\team.png"));
        assertNull(ResourceDownloadTool.safeFileName("team image.png"));
    }

    @Test
    void rejectsPrivateAndNonHttpTargetsBeforeOpeningConnection() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDownloadTool.validateTarget("http://127.0.0.1:8080/resource"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDownloadTool.validateTarget("http://169.254.169.254/latest"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDownloadTool.validateTarget("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceDownloadTool.validateTarget("https://user:password@example.com/a"));
    }

    @Test
    void returnsStableErrorWithoutLeakingLocalPathOrNestedMessage() throws Exception {
        Path directory = Files.createTempDirectory("resource-download-test-");
        try {
            ResourceDownloadTool tool = new ResourceDownloadTool(directory, 1024, 2048,
                    Set.of("text/plain"));
            String result = tool.downloadResource("http://127.0.0.1:8080/resource", "safe.txt");
            assertTrue(result.contains("\"status\":\"error\""));
            assertTrue(result.contains("\"code\":\"INVALID_URL\""));
            assertTrue(!result.contains(directory.toString()));
        } finally {
            try (var files = Files.walk(directory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void validatesPublicAddressResolutionAndUriShape() {
        assertEquals(URI.create("https://8.8.8.8/resource"),
                ResourceDownloadTool.validateTarget("https://8.8.8.8/resource"));
        assertTrue(ResourceDownloadTool.publicAddresses("8.8.8.8").contains("8.8.8.8"));
    }

    @Test
    void matchesConcreteMimeTypesExactlyAndAllowsExplicitPrefixes() {
        Set<String> allowlist = Set.of("application/json", "image/");
        assertTrue(ResourceDownloadTool.mediaTypeAllowed("application/json", allowlist));
        assertTrue(ResourceDownloadTool.mediaTypeAllowed("image/png", allowlist));
        assertTrue(ResourceDownloadTool.mediaTypeAllowed("IMAGE/WEBP", allowlist));
        assertTrue(!ResourceDownloadTool.mediaTypeAllowed("application/json-malicious", allowlist));
        assertTrue(!ResourceDownloadTool.mediaTypeAllowed("text/plain", allowlist));
    }
}
