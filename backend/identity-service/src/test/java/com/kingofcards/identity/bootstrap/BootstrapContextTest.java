package com.kingofcards.identity.bootstrap;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.kingofcards.identity.service.AdminBootstrapService;
import com.kingofcards.identity.exception.IdentityPersistenceException;

class BootstrapContextTest {
    @Test void bootstrapContextNeedsNeitherWebServerNorJwtKeysAndRemainsGated() {
        new ApplicationContextRunner().withUserConfiguration(BootstrapAdminApplication.BootstrapConfiguration.class)
            .withPropertyValues("identity.bigquery.enabled=false").run(context->{
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("identitySecurityFilterChain"));
                assertFalse(context.containsBean("jwtEncoder"));
                assertThrows(IdentityPersistenceException.class,()->context.getBean(AdminBootstrapService.class)
                    .createFirstAdministrator("synthetic@example.test","synthetic-password"));
            });
    }
    @Test void refusesCommandLineCredentialsBeforeStartingAnyContext() {
        assertThrows(IllegalArgumentException.class,()->BootstrapAdminApplication.main(new String[]{"--password=synthetic"}));
    }
}
