package com.vio.vioaiagent.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class LocalAccountServiceTest {
    @TempDir Path directory;
    private LocalAccountService open(){return new LocalAccountService(directory.resolve("accounts.sqlite").toString());}
    @Test void credentialsPersistAsHashesAndUsernameIsCaseInsensitive() throws Exception {
        var service=open();var created=service.register("Trainer","safe-test-password","小智","test");
        assertEquals(64,created.recoveryCode().length());
        assertEquals(created.account().id(),open().login("TRAINER","safe-test-password","test").id());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("accounts.sqlite"));var s=c.createStatement();var r=s.executeQuery("SELECT password_hash,recovery_hash FROM local_account")){
            assertTrue(r.next());assertTrue(r.getString(1).startsWith("$2a$"));assertNotEquals(created.recoveryCode(),r.getString(2));
        }
        assertThrows(ResponseStatusException.class,()->service.register("trainer","safe-test-password","小智","other"));
        assertThrows(ResponseStatusException.class,()->service.login("trainer","wrong","test"));
    }
    @Test void recoveryRotatesCodeAndRevokesOldVersion() {
        var service=open();var created=service.register("trainer","safe-test-password","小智","test");
        String code=service.recover("trainer",created.recoveryCode(),"replacement-password","test");
        assertNotEquals(code,created.recoveryCode());assertEquals(2,service.find(created.account().id()).orElseThrow().version());
        assertThrows(ResponseStatusException.class,()->service.login("trainer","safe-test-password","test"));
        assertThrows(ResponseStatusException.class,()->service.recover("trainer",created.recoveryCode(),"another-password","test"));
        var account=service.login("trainer","replacement-password","test");
        assertEquals(2,account.version());assertNotNull(service.changePassword(account,"replacement-password","changed-again-password"));
        assertEquals(3,service.find(account.id()).orElseThrow().version());
    }
    @Test void validationAndPersistentRateLimit() {
        var service=open();service.register("trainer","safe-test-password","小智","test");
        assertThrows(ResponseStatusException.class,()->service.register("ab","safe-test-password","昵称","other"));
        assertThrows(ResponseStatusException.class,()->service.register("next","short","昵称","other"));
        for(int i=0;i<8;i++)assertThrows(ResponseStatusException.class,()->service.login("trainer","wrong","test"));
        var restarted=open();
        assertEquals(429,assertThrows(ResponseStatusException.class,()->restarted.login("trainer","safe-test-password","test")).getStatusCode().value());
    }
    @Test void profilesAndFirstAccountAreServerOwned() {
        var service=open();var a=service.register("alice","safe-test-password","小智","test");
        var b=service.register("bob","safe-test-password","小霞","test");
        assertTrue(service.firstAccount(a.account()));assertFalse(service.firstAccount(b.account()));
        var changed=service.update(a.account(),"新昵称","pikachu");assertEquals(a.account().id(),changed.id());assertEquals("alice",changed.username());
        assertEquals("新昵称",open().find(changed.id()).orElseThrow().nickname());
        assertThrows(ResponseStatusException.class,()->service.update(a.account(),"小智","https://arbitrary.example/avatar"));
    }
}
