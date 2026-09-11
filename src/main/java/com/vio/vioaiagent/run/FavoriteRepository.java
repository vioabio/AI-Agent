package com.vio.vioaiagent.run;

import java.util.List;
import java.util.Optional;

public interface FavoriteRepository {
    Favorite create(Favorite favorite);
    Optional<Favorite> findByIdForOwner(long id, String ownerId);
    List<Favorite> findForOwner(String ownerId, int limit);
    /**
     * Update the user-editable projection of a favorite.  The resource type
     * and key are intentionally immutable so a PATCH cannot silently move a
     * record to another business object or bypass the uniqueness constraint.
     */
    Optional<Favorite> updateForOwner(long id, String ownerId, String label,
                                      java.util.Map<String, Object> metadata);
    default List<Favorite> findForOwner(String ownerId, int limit, int offset) {
        if (offset < 0 || limit <= 0) return List.of();
        // Keep one look-ahead row for the cursor page boundary.
        return findForOwner(ownerId, Math.min(201, offset + limit)).stream().skip(offset).limit(limit).toList();
    }
    boolean deleteForOwner(long id, String ownerId);
}
