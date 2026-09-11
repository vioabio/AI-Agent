package com.vio.vioaiagent.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N4 收藏资源：PATCH 只改变展示投影并保持 owner 隔离。 */
class FavoriteRepositoryTest {
    @Test
    void updateKeepsBusinessIdentityAndRejectsOtherOwner() {
        InMemoryFavoriteRepository repository = new InMemoryFavoriteRepository();
        Instant created = Instant.parse("2026-09-03T00:00:00Z");
        Favorite original = repository.create(new Favorite(0, "user-a", "pokemon",
                "pikachu", "旧标签", Map.of("source", "fixture"), created, created));

        assertTrue(repository.updateForOwner(original.id(), "user-b", "越权", Map.of()).isEmpty());
        Favorite updated = repository.updateForOwner(original.id(), "user-a", "新标签",
                Map.of("source", "reviewed")).orElseThrow();

        assertEquals(original.id(), updated.id());
        assertEquals("user-a", updated.ownerId());
        assertEquals("pokemon", updated.resourceType());
        assertEquals("pikachu", updated.resourceKey());
        assertEquals("新标签", updated.label());
        assertEquals(Map.of("source", "reviewed"), updated.metadata());
        assertEquals(created, updated.createdAt());
        assertNotEquals(created, updated.updatedAt());
    }
}
