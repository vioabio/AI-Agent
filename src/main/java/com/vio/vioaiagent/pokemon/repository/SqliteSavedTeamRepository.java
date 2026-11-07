package com.vio.vioaiagent.pokemon.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.pokemon.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.*;
import java.nio.file.*;
import java.util.*;

@Repository @Primary @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-history.enabled",havingValue="true")
public class SqliteSavedTeamRepository implements SavedTeamRepository {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    public SqliteSavedTeamRepository(ObjectMapper mapper,
            @Value("${vio.local-teams.path:${user.dir}/.local-data/teams.sqlite}") String file) {
        this.mapper=mapper;
        try {
            Path path=Path.of(file).toAbsolutePath().normalize(); Files.createDirectories(path.getParent());
            SQLiteConfig config=new SQLiteConfig(); config.setBusyTimeout(5000); config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            SQLiteDataSource source=new SQLiteDataSource(config); source.setUrl("jdbc:sqlite:"+path);
            jdbc=new JdbcTemplate(source);
            transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            jdbc.execute("CREATE TABLE IF NOT EXISTS local_team(id INTEGER PRIMARY KEY AUTOINCREMENT,owner_id TEXT NOT NULL,payload TEXT NOT NULL)");
            if (jdbc.queryForList("PRAGMA table_info(local_team)").stream().noneMatch(column -> "revision".equals(column.get("name"))))
                jdbc.execute("ALTER TABLE local_team ADD COLUMN revision INTEGER NOT NULL DEFAULT 0");
        } catch(Exception e) { throw new IllegalStateException("本地队伍数据库初始化失败",e); }
    }
    @Override public synchronized SavedTeam create(SavedTeam team) {
        Long id=jdbc.queryForObject("INSERT INTO local_team(owner_id,payload) VALUES(?,?) RETURNING id",Long.class,team.ownerId(),encode(team));
        return findByIdForOwner(id,team.ownerId()).orElseThrow();
    }
    @Override public synchronized boolean updateForOwner(long id,String owner,SavedTeam replacement) {
        if (!replacement.ownerId().equals(owner)) throw new IllegalArgumentException("队伍归属冲突");
        return jdbc.update("UPDATE local_team SET payload=?,revision=revision+1 WHERE id=? AND owner_id=?",encode(replacement),id,owner)==1;
    }
    @Override public synchronized Optional<SavedTeam> findByIdForOwner(long id,String owner) {
        return jdbc.query("SELECT id,payload FROM local_team WHERE id=? AND owner_id=?",(rs,n)->decode(rs.getLong(1),owner,rs.getString(2)),id,owner).stream().findFirst();
    }
    @Override public List<SavedTeam> findForOwner(String owner,int limit) { return findForOwner(owner,limit,0); }
    @Override public synchronized List<SavedTeam> findForOwner(String owner,int limit,int offset) {
        if(owner==null || limit<=0 || offset<0) return List.of();
        return jdbc.query("SELECT id,payload FROM local_team WHERE owner_id=? ORDER BY id DESC LIMIT ? OFFSET ?",(rs,n)->decode(rs.getLong(1),owner,rs.getString(2)),owner,Math.min(limit,201),offset);
    }
    @Override public synchronized boolean deleteForOwner(long id,String owner) { return jdbc.update("DELETE FROM local_team WHERE id=? AND owner_id=?",id,owner)==1; }
    @Override public synchronized Snapshot snapshotForOwner(long id, String owner) {
        return jdbc.query("SELECT id,payload,revision FROM local_team WHERE id=? AND owner_id=?",
                (rs,n)->new Snapshot(decode(rs.getLong(1),owner,rs.getString(2)),rs.getLong(3)),id,owner)
                .stream().findFirst().orElseThrow(()->new com.vio.vioaiagent.pokemon.service.PokemonResourceNotFoundException("队伍不存在或不属于当前主体"));
    }
    @Override public synchronized Optional<SavedTeam> compareAndSet(Snapshot expected, SavedTeam replacement, Runnable beforeCommit) {
        if (replacement.id()!=expected.team().id() || !replacement.ownerId().equals(expected.team().ownerId()))
            throw new IllegalArgumentException("队伍归属冲突");
        return transactions.execute(status -> {
            beforeCommit.run();
            var updated=jdbc.query("UPDATE local_team SET payload=?,revision=revision+1 WHERE id=? AND owner_id=? AND revision=? RETURNING id,payload",
                    (rs,n)->decode(rs.getLong(1),replacement.ownerId(),rs.getString(2)),encode(replacement),replacement.id(),replacement.ownerId(),expected.revision());
            beforeCommit.run(); // A deadline/lease loss rolls back the actual SQLite transaction.
            return updated.stream().findFirst();
        });
    }
    private String encode(SavedTeam team) { try{return mapper.writeValueAsString(team);}catch(Exception e){throw new IllegalArgumentException("队伍配置无法保存",e);} }
    private SavedTeam decode(long id,String owner,String json) {
        try { SavedTeam saved=mapper.readValue(json,SavedTeam.class);
            if(!owner.equals(saved.ownerId()))throw new IllegalStateException("队伍归属不一致");
            return new SavedTeam(id,owner,saved.name(),saved.rulesetId(),saved.dataReleaseId(),saved.members());
        } catch(Exception e){throw new IllegalStateException("已保存队伍配置损坏",e);}
    }
}
