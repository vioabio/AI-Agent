package com.vio.vioaiagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Only the first local account may explicitly claim the former anonymous workspace. */
@Service @Profile("local & !prod & !demo")
@ConditionalOnProperty(name="vio.local-accounts.enabled",havingValue="true")
public class LocalDataClaimService {
    private final LocalAccountService accounts;
    private final String history,teams;
    public LocalDataClaimService(LocalAccountService accounts,
            @Value("${vio.local-history.path:${user.dir}/.local-data/history.sqlite}") String history,
            @Value("${vio.local-teams.path:${user.dir}/.local-data/teams.sqlite}") String teams) { this.accounts=accounts;this.history=history;this.teams=teams; }
    public synchronized Map<String,Object> preview(LocalAccountService.Account account) {
        boolean eligible=accounts.firstAccount(account);
        return Map.of("eligible",eligible,"conversations",eligible?count(history,"local_conversation","user_id"):0,"teams",eligible?count(teams,"local_team","owner_id"):0);
    }
    private int count(String file,String table,String owner) {
        if(!Files.isRegularFile(Path.of(file)))return 0;
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+Path.of(file).toAbsolutePath());var s=c.createStatement()) {
            try(var exists=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='"+table+"'")){if(!exists.next()||exists.getInt(1)==0)return 0;}
            try(var r=s.executeQuery("SELECT count(*) FROM "+table+" WHERE "+owner+"='anonymous'")){return r.next()?r.getInt(1):0;}
        }catch(SQLException e){throw new IllegalStateException("无法检查旧数据",e);}
    }
    public synchronized Map<String,Object> claim(LocalAccountService.Account account) {
        if(!accounts.firstAccount(account))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"只有首次创建的本机账号可以认领旧数据");
        // Each file is transactional and the operation is retry-safe: only anonymous rows are moved.
        if(count(history,"local_conversation","user_id")>0) {
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+Path.of(history).toAbsolutePath())) {
                c.setAutoCommit(false);
                try(var messages=c.prepareStatement("UPDATE local_message SET user_id=? WHERE user_id='anonymous' AND conversation_id IN (SELECT id FROM local_conversation WHERE user_id='anonymous')");
                    var conversations=c.prepareStatement("UPDATE local_conversation SET user_id=? WHERE user_id='anonymous'")) {
                    messages.setString(1,account.id());messages.executeUpdate();conversations.setString(1,account.id());conversations.executeUpdate();c.commit();
                }
            }catch(SQLException e){throw new IllegalStateException("旧聊天认领失败，可重试",e);}
        }
        if(count(teams,"local_team","owner_id")>0) {
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+Path.of(teams).toAbsolutePath());var s=c.prepareStatement("UPDATE local_team SET owner_id=?,payload=json_set(payload,'$.ownerId',?) WHERE owner_id='anonymous'")) {
                s.setString(1,account.id());s.setString(2,account.id());s.executeUpdate();
            }catch(SQLException e){throw new IllegalStateException("旧队伍认领失败，可重试",e);}
        }
        return preview(account);
    }
}
