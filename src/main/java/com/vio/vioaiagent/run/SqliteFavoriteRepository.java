package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Repository @Primary @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-accounts.enabled",havingValue="true")
public class SqliteFavoriteRepository implements FavoriteRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper mapper;
    public SqliteFavoriteRepository(ObjectMapper mapper,@Value("${vio.local-favorites.path:${user.dir}/.local-data/favorites.sqlite}") String file) {
        this.mapper=mapper.copy();
        try { Path path=Path.of(file).toAbsolutePath().normalize();Files.createDirectories(path.getParent());
            SQLiteDataSource source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+path);jdbc=new JdbcTemplate(source);
            jdbc.execute("PRAGMA busy_timeout=5000");
            jdbc.execute("CREATE TABLE IF NOT EXISTS local_favorite(id INTEGER PRIMARY KEY AUTOINCREMENT,owner_id TEXT NOT NULL,resource_type TEXT NOT NULL,resource_key TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(owner_id,resource_type,resource_key))");
        }catch(Exception e){throw new IllegalStateException("本地收藏数据库初始化失败",e);}
    }
    private String encode(Favorite f){try{return mapper.writeValueAsString(f);}catch(Exception e){throw new IllegalArgumentException("收藏无法保存",e);}}
    private Favorite decode(long id,String owner,String json){try{var f=mapper.readValue(json,Favorite.class);if(!owner.equals(f.ownerId()))throw new IllegalStateException("收藏归属不一致");return new Favorite(id,owner,f.resourceType(),f.resourceKey(),f.label(),f.metadata(),f.createdAt(),f.updatedAt());}catch(Exception e){throw new IllegalStateException("收藏数据损坏",e);}}
    public synchronized Favorite create(Favorite f){
        jdbc.update("INSERT INTO local_favorite(owner_id,resource_type,resource_key,payload) VALUES(?,?,?,?) ON CONFLICT(owner_id,resource_type,resource_key) DO NOTHING",f.ownerId(),f.resourceType(),f.resourceKey(),encode(f));
        return jdbc.query("SELECT id,payload FROM local_favorite WHERE owner_id=? AND resource_type=? AND resource_key=?",(r,n)->decode(r.getLong(1),f.ownerId(),r.getString(2)),f.ownerId(),f.resourceType(),f.resourceKey()).getFirst();
    }
    public Optional<Favorite> findByIdForOwner(long id,String owner){return jdbc.query("SELECT id,payload FROM local_favorite WHERE id=? AND owner_id=?",(r,n)->decode(r.getLong(1),owner,r.getString(2)),id,owner).stream().findFirst();}
    public List<Favorite> findForOwner(String owner,int limit){return findForOwner(owner,limit,0);}
    public List<Favorite> findForOwner(String owner,int limit,int offset){if(owner==null||limit<=0||offset<0)return List.of();return jdbc.query("SELECT id,payload FROM local_favorite WHERE owner_id=? ORDER BY id DESC LIMIT ? OFFSET ?",(r,n)->decode(r.getLong(1),owner,r.getString(2)),owner,Math.min(limit,201),offset);}
    public synchronized Optional<Favorite> updateForOwner(long id,String owner,String label,Map<String,Object> metadata){return findByIdForOwner(id,owner).map(f->{var updated=new Favorite(id,owner,f.resourceType(),f.resourceKey(),label,metadata,f.createdAt(),Instant.now());jdbc.update("UPDATE local_favorite SET payload=? WHERE id=? AND owner_id=?",encode(updated),id,owner);return updated;});}
    public boolean deleteForOwner(long id,String owner){return jdbc.update("DELETE FROM local_favorite WHERE id=? AND owner_id=?",id,owner)==1;}
}
