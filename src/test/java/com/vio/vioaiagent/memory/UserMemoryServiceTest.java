package com.vio.vioaiagent.memory;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMemoryServiceTest {

    @Test
    void rememberRequiresExplicitConsent() {
        UserMemoryService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.remember("user-a", "conversation-1", "fact", "喜欢火焰鸡",
                        0.9, "", Duration.ofDays(7)));
    }

    @Test
    void recallAndDeleteAreScopedToTheAuthenticatedUser() {
        UserMemoryService service = service();
        UserMemoryEntry a = service.remember("user-a", "conversation-a", "FACT", "喜欢火焰鸡",
                0.9, "consent-a", Duration.ofDays(7));
        service.remember("user-b", "conversation-b", "FACT", "喜欢水箭龟",
                0.9, "consent-b", Duration.ofDays(7));

        assertEquals(1, service.recall("user-a", "火焰", 20).size());
        assertTrue(service.recall("user-a", "火焰", 20).getFirst().content().contains("火焰鸡"));
        assertTrue(service.recall("user-b", "水箭", 20).getFirst().content().contains("水箭龟"));
        assertFalse(service.delete("user-b", a.memoryId()));
        assertTrue(service.delete("user-a", a.memoryId()));
        assertTrue(service.recall("user-a", "", 20).isEmpty());
    }

    @Test
    void cipherRoundTripUsesUserBoundAuthenticatedEncryption() {
        AesGcmMemoryCipher cipher = new AesGcmMemoryCipher(
                "test-memory-key-32-bytes-1234567".getBytes(StandardCharsets.UTF_8), "v1");
        MemoryCipher.Encrypted encrypted = cipher.encrypt("user-a", "只在授权后保存");

        assertNotEquals("只在授权后保存", encrypted.ciphertext());
        assertEquals("只在授权后保存", cipher.decrypt("user-a", encrypted.ciphertext(), "v1"));
        assertThrows(IllegalStateException.class,
                () -> cipher.decrypt("user-b", encrypted.ciphertext(), "v1"));
    }

    @Test
    void configuredCipherRejectsPlaceholder() {
        assertThrows(IllegalArgumentException.class,
                () -> AesGcmMemoryCipher.fromConfigured("replace-with-base64-256-bit-memory-key", "v1"));
        String configured = Base64.getEncoder().encodeToString(new byte[32]);
        AesGcmMemoryCipher cipher = AesGcmMemoryCipher.fromConfigured(configured, "v2");
        assertEquals("v2", cipher.encrypt("user-a", "configured-key-works").keyVersion());
    }

    private UserMemoryService service() {
        byte[] key = "test-memory-key-32-bytes-1234567".getBytes(StandardCharsets.UTF_8);
        return new UserMemoryService(new InMemoryUserMemoryRepository(),
                new AesGcmMemoryCipher(key, "v1"), 30);
    }
}
