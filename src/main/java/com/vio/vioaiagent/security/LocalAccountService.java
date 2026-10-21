package com.vio.vioaiagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Local credentials never leave this machine; only hashes are persisted. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-accounts.enabled", havingValue="true")
public class LocalAccountService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(12);
    private final String dummyHash = passwords.encode("not-a-user-password");
    public LocalAccountService(@Value("${vio.local-accounts.path:${user.dir}/.local-data/accounts.sqlite}") String file) {
        try {
            Path path=Path.of(file).toAbsolutePath().normalize(); Files.createDirectories(path.getParent());
            SQLiteDataSource source=new SQLiteDataSource(); source.setUrl("jdbc:sqlite:"+path);
            jdbc=new JdbcTemplate(source);
            jdbc.execute("PRAGMA busy_timeout=5000");
            jdbc.execute("CREATE TABLE IF NOT EXISTS local_account(id TEXT PRIMARY KEY,username TEXT UNIQUE NOT NULL,nickname TEXT NOT NULL,avatar TEXT NOT NULL,password_hash TEXT NOT NULL,recovery_hash TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 1,created_at TEXT NOT NULL)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS local_auth_attempt(bucket TEXT PRIMARY KEY,failures INTEGER NOT NULL,until_ms INTEGER NOT NULL)");
        } catch(Exception e) { throw new IllegalStateException("本地账号数据库无法初始化",e); }
    }
    public record Account(String id,String username,String nickname,String avatar,int version) implements java.io.Serializable {}
    public record Registration(Account account,String recoveryCode) {}
    public record Credentials(String password,String recovery) {}
    private static ResponseStatusException error(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
    private String username(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_]{3,32}")) throw error(HttpStatus.BAD_REQUEST,"用户名需为 3～32 位字母、数字或下划线");
        return value.toLowerCase(Locale.ROOT);
    }
    private void password(String value) {
        if(value==null || value.length()<10 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw error(HttpStatus.BAD_REQUEST,"密码至少 10 位，且不超过 72 字节");
    }
    public synchronized Registration register(String name,String secret,String nickname,String address) {
        throttle("register:"+address,10);
        name=username(name); password(secret);
        if(!jdbc.queryForList("SELECT id FROM local_account WHERE username=?",name).isEmpty()) throw error(HttpStatus.CONFLICT,"该用户名已被使用");
        String id="local-"+UUID.randomUUID(); String recovery=recoveryCode();
        jdbc.update("INSERT INTO local_account(id,username,nickname,avatar,password_hash,recovery_hash,created_at) VALUES(?,?,?,?,?,?,?)",
                id,name,nickname(nickname==null || nickname.isBlank()?name:nickname),"squirtle",passwords.encode(secret),passwords.encode(recovery),Instant.now().toString());
        return new Registration(find(id).orElseThrow(),recovery);
    }
    public Optional<Account> find(String id) {
        return jdbc.query("SELECT id,username,nickname,avatar,version FROM local_account WHERE id=?",(r,n)->new Account(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getInt(5)),id).stream().findFirst();
    }
    public synchronized Account login(String name,String secret,String address) {
        String normalized=name==null?"":name.toLowerCase(Locale.ROOT);
        throttle("login-ip:"+address,30); throttle("login-user:"+TokenBinding.sha256Hex(normalized),8);
        var rows=jdbc.query("SELECT id,password_hash FROM local_account WHERE username=?",(r,n)->new String[]{r.getString(1),r.getString(2)},normalized);
        boolean valid=matches(secret,rows.isEmpty()?dummyHash:rows.getFirst()[1]);
        if(!valid || rows.isEmpty()) throw error(HttpStatus.UNAUTHORIZED,"用户名或密码错误");
        jdbc.update("DELETE FROM local_auth_attempt WHERE bucket=?","login-user:"+TokenBinding.sha256Hex(normalized));
        return find(rows.getFirst()[0]).orElseThrow();
    }
    private boolean matches(String secret,String hash) {
        return secret!=null && secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=72 && passwords.matches(secret,hash);
    }
    public void confirmPassword(Account account,String secret) {
        String hash=jdbc.queryForObject("SELECT password_hash FROM local_account WHERE id=?",String.class,account.id());
        if(!matches(secret,hash)) throw error(HttpStatus.BAD_REQUEST,"当前密码错误");
    }
    private String nickname(String value) {
        if(value==null || value.isBlank() || value.length()>24 || value.codePoints().anyMatch(Character::isISOControl)) throw error(HttpStatus.BAD_REQUEST,"昵称需为 1～24 个字符");
        return value.trim();
    }
    public synchronized Account update(Account account,String name,String avatar) {
        if(!Set.of("squirtle","bulbasaur","pikachu").contains(avatar==null?"":avatar)) throw error(HttpStatus.BAD_REQUEST,"请选择预设头像");
        jdbc.update("UPDATE local_account SET nickname=?,avatar=? WHERE id=?",nickname(name),avatar,account.id());
        return find(account.id()).orElseThrow();
    }
    public synchronized String changePassword(Account account,String oldPassword,String newPassword) {
        throttle("password:"+account.id(),8); confirmPassword(account,oldPassword); password(newPassword);
        return replacePassword(account.id(),newPassword);
    }
    public synchronized String recover(String name,String code,String newPassword,String address) {
        throttle("recover-ip:"+address,10); String normalized=username(name);
        throttle("recover-user:"+TokenBinding.sha256Hex(normalized),5); password(newPassword);
        var rows=jdbc.query("SELECT id,recovery_hash FROM local_account WHERE username=?",(r,n)->new String[]{r.getString(1),r.getString(2)},normalized);
        boolean valid=matches(code,rows.isEmpty()?dummyHash:rows.getFirst()[1]);
        if(!valid || rows.isEmpty()) throw error(HttpStatus.BAD_REQUEST,"用户名或恢复码错误");
        return replacePassword(rows.getFirst()[0],newPassword);
    }
    private String replacePassword(String id,String secret) {
        String recovery=recoveryCode();
        jdbc.update("UPDATE local_account SET password_hash=?,recovery_hash=?,version=version+1 WHERE id=?",passwords.encode(secret),passwords.encode(recovery),id);
        return recovery;
    }
    private String recoveryCode() { return (UUID.randomUUID().toString()+UUID.randomUUID()).replace("-",""); }
    /** Persistent bounded counters: restart does not bypass cooldown. */
    private void throttle(String bucket,int limit) {
        long now=System.currentTimeMillis();
        jdbc.update("DELETE FROM local_auth_attempt WHERE until_ms<?",now);
        jdbc.update("INSERT INTO local_auth_attempt(bucket,failures,until_ms) VALUES(?,1,?) ON CONFLICT(bucket) DO UPDATE SET failures=failures+1",bucket,now+900_000);
        Integer failures=jdbc.queryForObject("SELECT failures FROM local_auth_attempt WHERE bucket=?",Integer.class,bucket);
        if(failures!=null && failures>limit) throw error(HttpStatus.TOO_MANY_REQUESTS,"操作过于频繁，请在 15 分钟后重试");
    }
    public boolean firstAccount(Account account) {
        return account.id().equals(jdbc.queryForObject("SELECT id FROM local_account ORDER BY created_at,id LIMIT 1",String.class));
    }
}
