package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalAccountModeTest {
    @Test void localAccountCannotDisableEnterpriseChainOutsideLocalProfile(){
        for(String[] profiles:new String[][]{{"prod"},{"demo"},{"test"},{"local","prod"}}){
            var env=new MockEnvironment().withProperty("vio.local-accounts.enabled","true");env.setActiveProfiles(profiles);
            assertThrows(IllegalStateException.class,()->new OidcSecurityConfiguration(mock(OidcProperties.class),env).validate());
        }
        var env=new MockEnvironment().withProperty("vio.local-accounts.enabled","true");env.setActiveProfiles("local");
        assertDoesNotThrow(()->new OidcSecurityConfiguration(mock(OidcProperties.class),env).validate());
    }
}
