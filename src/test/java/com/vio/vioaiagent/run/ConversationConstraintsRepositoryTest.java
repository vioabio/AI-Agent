package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationConstraintsRepositoryTest {
    @TempDir Path directory;
    private ConversationConstraints choice(long revision, String notes) {
        return new ConversationConstraints(revision, ConversationConstraints.ResponseStyle.CONCISE, ConversationConstraints.TrainingBudget.LOW, true, notes);
    }
    private SqliteLocalHistoryRepository sqlite() { return new SqliteLocalHistoryRepository(directory.resolve("history.sqlite").toString(), new ObjectMapper()); }
    private void seed(ConversationRepository repository) { repository.save(new Conversation("c", "owner", "对话", Instant.now(), Instant.now())); }
    @Test void bothLocalStoresEnforceOwnerRevisionDeletionAndClear() {
        for (var repository : List.of(new InMemoryConversationRepository(), sqlite())) {
            seed(repository);
            assertTrue(repository.constraintsForUser("c", "owner").isEmpty());
            assertFalse(repository.replaceConstraintsForUser("c", "other", choice(0,"private")));
            assertTrue(repository.replaceConstraintsForUser("c", "owner", choice(0,"只推荐低成本培养")));
            assertFalse(repository.replaceConstraintsForUser("c", "owner", choice(0,"旧页面")));
            assertTrue(repository.constraintsForUser("c", "other").isEmpty());
            assertEquals(1,repository.constraintsForUser("c", "owner").orElseThrow().revision());
            var defaults=ConversationConstraints.defaults();
            assertTrue(repository.replaceConstraintsForUser("c", "owner", new ConversationConstraints(1,defaults.responseStyle(),defaults.trainingBudget(),false,"")));
            assertFalse(repository.constraintsForUser("c", "owner").orElseThrow().configured());
            repository.deleteForUser("c", "other"); assertTrue(repository.findByIdForUser("c", "owner").isPresent());
            repository.deleteForUser("c", "owner"); assertTrue(repository.constraintsForUser("c", "owner").isEmpty());
        }
    }
    @Test void sqliteReopenAndConcurrentIndependentConnectionsHaveOnlyOneWinner() throws Exception {
        var first=sqlite();seed(first);var second=sqlite();var release=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->{release.await();return first.replaceConstraintsForUser("c","owner",choice(0,"A"));});
            var b=workers.submit(()->{release.await();return second.replaceConstraintsForUser("c","owner",choice(0,"B"));});release.countDown();
            assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
            assertEquals(1,sqlite().constraintsForUser("c","owner").orElseThrow().revision());
        }
    }
    @Test void jdbcSqlAndMigrationRejectForeignDeletedAndTamperedState() throws Exception {
        var data=new SQLiteDataSource();data.setUrl("jdbc:sqlite:"+directory.resolve("jdbc.sqlite"));var jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE conversation(id TEXT PRIMARY KEY,user_id TEXT,deleted_at TEXT)");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V31__conversation_constraints.sql")));
        jdbc.update("INSERT INTO conversation(id,user_id) VALUES('c','owner')");var repository=new JdbcConversationRepository(jdbc);
        assertFalse(repository.replaceConstraintsForUser("c","other",choice(0,"foreign")));
        assertTrue(repository.replaceConstraintsForUser("c","owner",choice(0,"kept")));
        assertFalse(repository.replaceConstraintsForUser("c","owner",choice(0,"stale")));
        assertEquals("kept",repository.constraintsForUser("c","owner").orElseThrow().notes());
        jdbc.update("UPDATE conversation SET deleted_at='deleted'");
        assertTrue(repository.constraintsForUser("c","owner").isEmpty());assertFalse(repository.replaceConstraintsForUser("c","owner",choice(1,"late")));
        jdbc.update("UPDATE conversation SET deleted_at=NULL");jdbc.update("UPDATE conversation_constraints SET settings_json='{}'");
        assertThrows(IllegalStateException.class,()->repository.constraintsForUser("c","owner"));
    }
    @Test void valueValidationAndMemoryOwnershipCannotBeBypassed() {
        assertThrows(IllegalArgumentException.class,()->choice(0,"x".repeat(801)));
        assertThrows(IllegalArgumentException.class,()->choice(0,"\u0000"));
        assertThrows(IllegalStateException.class,()->ConversationConstraints.fromSnapshot(Map.of()));
        assertEquals(choice(3,"\"quoted\"\n新手"),ConversationConstraints.fromSnapshot(choice(3,"\"quoted\"\n新手").snapshot()));
        var memory=new InMemoryConversationRepository();seed(memory);
        assertThrows(IllegalStateException.class,()->memory.save(new Conversation("c","other","replace",null,null)));
        assertEquals("owner",memory.findById("c").orElseThrow().userId());
    }
}
