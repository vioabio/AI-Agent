package com.vio.vioaiagent.run;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!prod & !demo")
@Primary
public class InMemoryFavoriteRepository implements FavoriteRepository {
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<Long, Favorite> values = new ConcurrentHashMap<>();

    @Override
    public Favorite create(Favorite favorite) {
        if (favorite == null) throw new IllegalArgumentException("收藏不能为空");
        long id = favorite.id() > 0 ? favorite.id() : sequence.incrementAndGet();
        sequence.accumulateAndGet(id, Math::max);
        Favorite persisted = new Favorite(id, favorite.ownerId(), favorite.resourceType(), favorite.resourceKey(),
                favorite.label(), favorite.metadata(), favorite.createdAt(), favorite.updatedAt());
        // 同一主体/资源键幂等：返回已有记录而不是产生重复收藏。
        Optional<Favorite> existing = values.values().stream()
                .filter(item -> item.ownerId().equals(persisted.ownerId())
                        && item.resourceType().equals(persisted.resourceType())
                        && item.resourceKey().equals(persisted.resourceKey()))
                .findFirst();
        if (existing.isPresent()) return existing.get();
        values.put(id, persisted);
        return persisted;
    }

    @Override
    public Optional<Favorite> findByIdForOwner(long id, String ownerId) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return Optional.empty();
        return Optional.ofNullable(values.get(id)).filter(item -> ownerId.equals(item.ownerId()));
    }

    @Override
    public synchronized Optional<Favorite> updateForOwner(long id, String ownerId, String label,
                                                           java.util.Map<String, Object> metadata) {
        if (id <= 0 || ownerId == null || ownerId.isBlank()) return Optional.empty();
        Favorite current = values.get(id);
        if (current == null || !ownerId.trim().equals(current.ownerId())) return Optional.empty();
        Favorite updated = new Favorite(current.id(), current.ownerId(), current.resourceType(),
                current.resourceKey(), label == null ? current.label() : label,
                metadata == null ? current.metadata() : metadata,
                current.createdAt(), java.time.Instant.now());
        values.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public List<Favorite> findForOwner(String ownerId, int limit) {
        if (ownerId == null || ownerId.isBlank() || limit <= 0) return List.of();
        return values.values().stream().filter(item -> ownerId.equals(item.ownerId()))
                .sorted(Comparator.comparing(Favorite::createdAt).reversed().thenComparingLong(Favorite::id))
                .limit(Math.min(limit, 201)).toList();
    }

    @Override
    public List<Favorite> findForOwner(String ownerId, int limit, int offset) {
        if (ownerId == null || ownerId.isBlank() || limit <= 0 || offset < 0) return List.of();
        return values.values().stream().filter(item -> ownerId.equals(item.ownerId()))
                .sorted(Comparator.comparing(Favorite::createdAt).reversed().thenComparingLong(Favorite::id))
                .skip(offset).limit(Math.min(limit, 201)).toList();
    }

    @Override
    public boolean deleteForOwner(long id, String ownerId) {
        Favorite current = values.get(id);
        return current != null && ownerId != null && ownerId.equals(current.ownerId()) && values.remove(id, current);
    }
}
