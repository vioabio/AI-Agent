package com.vio.vioaiagent.pokemon.repository;

import com.vio.vioaiagent.pokemon.domain.SavedTeam;

import java.util.List;
import java.util.Optional;

/** 用户队伍资源仓储；所有读取/删除方法都要求 ownerId。 */
public interface SavedTeamRepository {
    record Snapshot(SavedTeam team, long revision) { }
    default Snapshot snapshotForOwner(long id, String owner) { throw new UnsupportedOperationException("当前存储不支持队伍确认写入"); }
    default Optional<SavedTeam> compareAndSet(Snapshot expected, SavedTeam replacement, Runnable beforeCommit) {
        throw new UnsupportedOperationException("当前存储不支持队伍确认写入");
    }
    SavedTeam create(SavedTeam team);

    /** Replace a team only when the write still matches its owner. */
    default boolean updateForOwner(long id, String ownerId, SavedTeam replacement) { return false; }

    Optional<SavedTeam> findByIdForOwner(long id, String ownerId);

    List<SavedTeam> findForOwner(String ownerId, int limit);

    default List<SavedTeam> findForOwner(String ownerId, int limit, int offset) {
        if (offset < 0 || limit <= 0) return List.of();
        // Keep one look-ahead row for the cursor page boundary.
        return findForOwner(ownerId, Math.min(201, offset + limit)).stream().skip(offset).limit(limit).toList();
    }

    boolean deleteForOwner(long id, String ownerId);
}
