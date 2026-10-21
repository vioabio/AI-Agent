package com.vio.vioaiagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.pokemon.repository.SqliteSavedTeamRepository;
import com.vio.vioaiagent.pokemon.domain.SavedTeam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalAccountDataTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Test void favoritesPersistAndCannotBeReadOrChangedByOtherAccounts(){
        String path=directory.resolve("favorites.sqlite").toString();var repository=new SqliteFavoriteRepository(mapper,path);
        var created=repository.create(new Favorite(0,"alice","pokemon","7","杰尼龟",Map.of(),Instant.now(),Instant.now()));
        assertEquals(created.id(),repository.create(new Favorite(0,"alice","pokemon","7","重复",Map.of(),Instant.now(),Instant.now())).id());
        var restarted=new SqliteFavoriteRepository(mapper,path);
        assertEquals(1,restarted.findForOwner("alice",10).size());assertTrue(restarted.findByIdForOwner(created.id(),"bob").isEmpty());
        assertTrue(restarted.updateForOwner(created.id(),"bob","偷改",Map.of()).isEmpty());assertFalse(restarted.deleteForOwner(created.id(),"bob"));
        assertEquals("更新",restarted.updateForOwner(created.id(),"alice","更新",Map.of()).orElseThrow().label());
    }
    @Test void firstAccountClaimIsExplicitRetrySafeAndLeavesOtherOwnersIntact(){
        var accounts=new LocalAccountService(directory.resolve("accounts.sqlite").toString());
        var alice=accounts.register("alice","fixture-password","小智","test").account();
        var bob=accounts.register("bob","fixture-password","小霞","test").account();
        String historyPath=directory.resolve("history.sqlite").toString(),teamsPath=directory.resolve("teams.sqlite").toString();
        var history=new SqliteLocalHistoryRepository(historyPath,mapper);
        history.save(new Conversation("anonymous-chat","anonymous","旧会话",Instant.now(),Instant.now()));
        history.save(new Conversation("private-chat",bob.id(),"其他账号",Instant.now(),Instant.now()));
        history.save(new ChatMessage("old-message","anonymous-chat",null,"anonymous","user","旧消息",Instant.now(),Map.of()));
        var teams=new SqliteSavedTeamRepository(mapper,teamsPath);
        var team=teams.create(new SavedTeam(0,"anonymous","旧队伍","casual-v1","pokeapi-reference-v1",List.of(new com.vio.vioaiagent.pokemon.domain.TeamMember(0,7,1))));
        var claims=new LocalDataClaimService(accounts,historyPath,teamsPath);
        assertEquals(1,claims.preview(alice).get("conversations"));assertFalse((boolean)claims.preview(bob).get("eligible"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->claims.claim(bob));
        claims.claim(alice);claims.claim(alice);
        assertEquals(1,history.findByConversationForUser("anonymous-chat",alice.id()).size());
        assertTrue(history.findByIdForUser("private-chat",bob.id()).isPresent());assertTrue(history.findByIdForUser("private-chat",alice.id()).isEmpty());
        assertTrue(teams.findByIdForOwner(team.id(),alice.id()).isPresent());assertTrue(teams.findByIdForOwner(team.id(),"anonymous").isEmpty());
        assertEquals(0,claims.preview(alice).get("conversations"));assertEquals(0,claims.preview(alice).get("teams"));
    }
    @Test void localAccountWiringSelectsDurableUserResources(){
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withInitializer(c->c.getEnvironment().setActiveProfiles("local"))
            .withBean(ObjectMapper.class,()->mapper)
            .withPropertyValues("vio.local-accounts.enabled=true","vio.local-history.enabled=true",
                "vio.local-accounts.path="+directory.resolve("accounts.sqlite"),"vio.local-history.path="+directory.resolve("history.sqlite"),
                "vio.local-teams.path="+directory.resolve("teams.sqlite"),"vio.local-favorites.path="+directory.resolve("favorites.sqlite"))
            .withUserConfiguration(LocalAccountService.class,LocalDataClaimService.class,SqliteFavoriteRepository.class,InMemoryFavoriteRepository.class,
                SqliteSavedTeamRepository.class,com.vio.vioaiagent.pokemon.repository.InMemorySavedTeamRepository.class,
                SqliteLocalHistoryRepository.class,InMemoryConversationRepository.class,InMemoryChatMessageRepository.class)
            .run(c->{org.assertj.core.api.Assertions.assertThat(c).hasNotFailed();assertInstanceOf(SqliteFavoriteRepository.class,c.getBean(FavoriteRepository.class));
                assertInstanceOf(SqliteSavedTeamRepository.class,c.getBean(com.vio.vioaiagent.pokemon.repository.SavedTeamRepository.class));
                assertInstanceOf(SqliteLocalHistoryRepository.class,c.getBean(ConversationRepository.class));});
    }
}
