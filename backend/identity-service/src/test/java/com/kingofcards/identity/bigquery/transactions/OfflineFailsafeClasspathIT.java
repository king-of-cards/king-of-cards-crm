package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.util.UUID;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.junit.jupiter.api.Test;

/** Explicitly selected offline Failsafe regression. No credentials, clients or cloud calls. */
class OfflineFailsafeClasspathIT {
    @Test void applicationClassesAndSqlAreVisibleAfterBootRepackage() {
        assertEquals("IDENTITY_MUTATIONS_UNVERIFIED", IdentityPersistenceException.gated().code());
        String origin = IdentityPersistenceException.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertTrue(origin.replace('\\', '/').endsWith("/target/classes/"));
        var plan = new AuthTransactionPlanner(Clock.systemUTC()).logout(UUID.randomUUID(), "a".repeat(64), "b".repeat(64));
        assertTrue(plan.query().getQuery().contains(IsolatedAuthTarget.users()));
        assertTrue(plan.query().getQuery().contains(IsolatedAuthTarget.sessions()));
    }
}
