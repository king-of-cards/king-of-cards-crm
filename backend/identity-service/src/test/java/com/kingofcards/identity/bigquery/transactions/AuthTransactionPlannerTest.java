package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.kingofcards.identity.entity.*;

class AuthTransactionPlannerTest {
    @Test void disabledSnapshotFailsLocallyWhileStaleEnabledVersionRemainsBoundForServerRecheck() {
        var enabled=user();
        var disabled=IdentityUser.restore(enabled.getId(),enabled.getEmail(),enabled.getDisplayName(),BCRYPT,
            "CRM_VENDOR",false,NOW,NOW.plusSeconds(1));
        var session=new RefreshTokenSession(enabled.getId(),UUID.randomUUID(),HASH,CSRF,NOW,NOW.plusSeconds(60));
        assertThrows(IllegalArgumentException.class,()->planner.createSession(UUID.randomUUID(),disabled,session));
        var plan=planner.createSession(UUID.randomUUID(),enabled,session);
        assertEquals(com.google.cloud.bigquery.QueryParameterValue.timestamp(NOW.getEpochSecond()*1_000_000L),
            plan.query().getNamedParameters().get("expected_updated_at"));
        assertTrue(plan.query().getQuery().contains("id = @id AND enabled = TRUE"));
        assertTrue(plan.query().getQuery().contains("updated_at = @expected_updated_at"));
    }
    @Test void verificationUsesOnlyProductionNamedAuthTablesAndRequiresExplicitRunOwnership() {
        assertEquals("identity_user",IsolatedAuthTarget.USERS);
        assertEquals("identity_refresh_session",IsolatedAuthTarget.SESSIONS);
        UUID run=UUID.randomUUID();
        var scoped=new AuthTransactionPlanner(Clock.fixed(NOW,ZoneOffset.UTC),100_000_000L,run);
        var user=IdentityUser.restore(UUID.randomUUID(),"it+"+run+"-vendor@example.test","Synthetic",BCRYPT,"CRM_VENDOR",true,NOW,NOW);
        var plan=scoped.createUser(UUID.randomUUID(),user,false);
        assertEquals(run.toString(),plan.query().getNamedParameters().get("verification_run_id").getValue());
        assertTrue(plan.query().getQuery().contains("IDENTITY_UNRELATED_ACCOUNT_PRESENT"));
        assertFalse(plan.query().getQuery().contains("identity_user_it_20261030"));
        assertFalse(plan.query().getQuery().contains("identity_refresh_session_it_20261030"));
        assertThrows(IllegalArgumentException.class,()->scoped.createUser(UUID.randomUUID(),user(),false));
    }
    @Test void legacyOfflinePlannerCannotExecuteWithoutSqlVerificationRunGuard() {
        var plan=planner.logout(UUID.randomUUID(),HASH,CSRF);
        assertEquals("",plan.query().getNamedParameters().get("verification_run_id").getValue());
        assertTrue(plan.query().getQuery().indexOf("IDENTITY_VERIFICATION_RUN_REQUIRED")
            < plan.query().getQuery().indexOf("BEGIN TRANSACTION;"));
    }
    @Test void disabledAccountCannotPlanANewLoginSession() {
        var disabled=IdentityUser.restore(UUID.randomUUID(),"disabled@example.test","Disabled",BCRYPT,
            "CRM_VENDOR",false,NOW,NOW);
        var session=new RefreshTokenSession(disabled.getId(),UUID.randomUUID(),HASH,CSRF,NOW,NOW.plusSeconds(60));
        assertThrows(IllegalArgumentException.class,()->planner.createSession(UUID.randomUUID(),disabled,session));
    }
    @Test void planCannotBypassBudgetDialectCacheOrDeadlineBinding() {
        var valid=planner.logout(UUID.randomUUID(),HASH,CSRF);
        for(var altered:List.of(valid.query().toBuilder().setUseLegacySql(true).build(),
                valid.query().toBuilder().setUseQueryCache(true).build(),
                valid.query().toBuilder().setMaximumBytesBilled(Long.MAX_VALUE).build(),
                valid.query().toBuilder().setJobTimeoutMs(90_000L).build()))
            assertThrows(IllegalArgumentException.class,()->new AuthTransactionPlan(UUID.randomUUID(),valid.deadline(),altered));
        assertThrows(IllegalArgumentException.class,()->new AuthTransactionPlan(UUID.randomUUID(),valid.deadline().plusSeconds(1),valid.query()));
    }
    @Test void consumedOrRevokedCredentialsCannotBeReintroducedAsNewSessions() {
        var vendor=user();var session=new RefreshTokenSession(vendor.getId(),UUID.randomUUID(),HASH,CSRF,NOW,NOW.plusSeconds(60));
        session.consumeAt(NOW);
        assertThrows(IllegalArgumentException.class,()->planner.createSession(UUID.randomUUID(),vendor,session));
    }
    @Test void plansAtomicInvitationAndOneTimePasswordSetupWithoutExtraTablesOrRawTokens() {
        var vendor=user();
        var setup=new RefreshTokenSession(vendor.getId(),UUID.randomUUID(),HASH,CSRF,NOW,NOW.plusSeconds(1800));
        var invite=planner.createVendorInvitation(UUID.randomUUID(),vendor,setup);
        assertEquals("VENDOR_INVITE",invite.query().getNamedParameters().get("operation").getValue());
        assertEquals("1800000000",invite.query().getNamedParameters().get("ttl_micros").getValue());
        var finish=planner.completeVendorPasswordSetup(UUID.randomUUID(),HASH,CSRF,BCRYPT);
        assertEquals("VENDOR_SETUP",finish.query().getNamedParameters().get("operation").getValue());
        assertTrue(finish.query().getQuery().contains("AND s.used_at IS NULL AND s.revoked_at IS NULL AND s.expires_at > v_now"));
        assertTrue(finish.query().getQuery().contains("WHERE user_id = v_owner AND revoked_at IS NULL"));
        assertTrue(finish.query().getQuery().contains("WHERE id = v_owner AND enabled = TRUE AND role = 'CRM_VENDOR'"));
        assertThrows(IllegalArgumentException.class,()->planner.completeVendorPasswordSetup(UUID.randomUUID(),HASH,CSRF,"plaintext"));
        var wrong=new RefreshTokenSession(UUID.randomUUID(),UUID.randomUUID(),HASH,CSRF,NOW,NOW.plusSeconds(1800));
        assertThrows(IllegalArgumentException.class,()->planner.createVendorInvitation(UUID.randomUUID(),vendor,wrong));
        var tooLong=new RefreshTokenSession(vendor.getId(),UUID.randomUUID(),HASH,CSRF,NOW,NOW.plusSeconds(1801));
        assertThrows(IllegalArgumentException.class,()->planner.createVendorInvitation(UUID.randomUUID(),vendor,tooLong));
    }
    static final Instant NOW = Instant.parse("2026-10-30T00:00:00Z");
    static final String HASH = "a".repeat(64), CSRF = "b".repeat(64), NEXT = "c".repeat(64);
    static final String BCRYPT = "$2a$12$" + "A".repeat(53);
    final AuthTransactionPlanner planner = new AuthTransactionPlanner(Clock.fixed(NOW, ZoneOffset.UTC));
    static IdentityUser user() {
        return IdentityUser.restore(UUID.randomUUID(), "it+synthetic@example.test", "Synthetic Vendor", BCRYPT,
            "CRM_VENDOR", true, NOW, NOW);
    }
    @Test void everySecurityTransitionIsPlannedWithoutCloudAccess() {
        var user = user();
        var session = new RefreshTokenSession(user.getId(), UUID.randomUUID(), HASH, CSRF, NOW, NOW.plusSeconds(60));
        var admin = IdentityUser.restore(UUID.randomUUID(), "it+admin@example.test", null, BCRYPT,
            "CRM_ADMIN", true, NOW, NOW);
        var plans = List.of(planner.createUser(UUID.randomUUID(), admin, true),
            planner.createUser(UUID.randomUUID(), user, false), planner.updateAccount(UUID.randomUUID(), user),
            planner.createSession(UUID.randomUUID(), user, session),
            planner.rotate(UUID.randomUUID(), HASH, CSRF, UUID.randomUUID(), NEXT, HASH, Duration.ofMinutes(5)),
            planner.logout(UUID.randomUUID(), HASH, CSRF), planner.revokeFamily(UUID.randomUUID(), user.getId(), session.getTokenFamilyId()));
        assertEquals(Set.of("BOOTSTRAP", "CREATE_USER", "UPDATE_ACCOUNT", "CREATE_SESSION", "ROTATE", "REVOKE_TOKEN", "REVOKE_FAMILY"),
            plans.stream().map(p -> p.query().getNamedParameters().get("operation").getValue()).collect(java.util.stream.Collectors.toSet()));
        for (var plan : plans) {
            assertEquals(IsolatedAuthTarget.PROJECT, plan.jobId().getProject());
            assertEquals(IsolatedAuthTarget.LOCATION, plan.jobId().getLocation());
            assertFalse(plan.query().getQuery().contains(user.getEmail()));
            assertFalse(plan.toString().contains(BCRYPT));
        }
    }
    @Test void rejectsRealAccountsInvalidHashesWrongOwnerAndEmptyLifetimeLocally() {
        var real = IdentityUser.restore(UUID.randomUUID(), "admin@company.com", null, BCRYPT, "CRM_ADMIN", true, NOW, NOW);
        assertThrows(IllegalArgumentException.class, () -> planner.createUser(UUID.randomUUID(), real, false));
        assertThrows(IllegalArgumentException.class, () -> planner.createUser(UUID.randomUUID(), user(), true));
        assertThrows(IllegalArgumentException.class, () -> planner.logout(UUID.randomUUID(), "raw-token", CSRF));
        assertThrows(IllegalArgumentException.class, () -> planner.rotate(UUID.randomUUID(), HASH, CSRF,
            UUID.randomUUID(), NEXT, HASH, Duration.ZERO));
        var session = new RefreshTokenSession(UUID.randomUUID(), UUID.randomUUID(), HASH, CSRF, NOW, NOW.plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> planner.createSession(UUID.randomUUID(), user(), session));
    }
    @Test void retryIdentityIsBoundToOperationNotARandomNewJob() {
        var op = UUID.randomUUID();
        var plan = planner.logout(op, HASH, CSRF);
        assertEquals(plan.jobId(), planner.logout(op, HASH, CSRF).jobId());
        assertNotEquals(plan.jobId(), planner.logout(UUID.randomUUID(), HASH, CSRF).jobId());
        assertEquals(NOW.plusSeconds(300), plan.deadline());
    }
    @Test void sqlSecurityLintRequiresSerializationRowCountsAndPostCommitDenials() {
        String sql = planner.logout(UUID.randomUUID(), HASH, CSRF).query().getQuery();
        assertTrue(sql.indexOf("DELETE FROM " + IsolatedAuthTarget.users() + " WHERE FALSE")
            < sql.indexOf("DELETE FROM " + IsolatedAuthTarget.sessions() + " WHERE FALSE"));
        assertTrue(sql.indexOf("BEGIN TRANSACTION;") < sql.indexOf("DELETE FROM"));
        assertTrue(sql.indexOf("COMMIT TRANSACTION;") < sql.indexOf("SELECT v_status AS status"));
        assertTrue(sql.contains("SET v_status = 'REUSED'"));
        assertTrue(sql.contains("ASSERT @@row_count = v_expected"));
        assertTrue(sql.contains("WHERE id = @id AND updated_at = @expected_updated_at"));
        assertTrue(sql.contains("TIMESTAMP_ADD(updated_at, INTERVAL 1 MICROSECOND)"));
        assertTrue(sql.contains("COUNT(DISTINCT user_id) > 1"));
        assertFalse(sql.contains("FOR UPDATE"));
        assertFalse(sql.contains("CREATE TABLE"));
        assertFalse(sql.contains("TRUNCATE"));
        assertFalse(sql.contains("EXCEPTION WHEN"));
        for (var line : sql.lines().toList()) if (line.strip().startsWith("DELETE FROM"))
            assertTrue(line.endsWith("WHERE FALSE;"), "Transaction plans must not delete records.");
        var refs = java.util.regex.Pattern.compile("`([^`]+)`").matcher(sql);
        while (refs.find()) assertTrue(Set.of(IsolatedAuthTarget.users(), IsolatedAuthTarget.sessions()).contains("`" + refs.group(1) + "`"));
    }
    @Test void parameterValuesCannotBecomeSqlAndAuditVersionRemainsBound() {
        var user = user();
        user.setDisplayName("Synthetic '; DROP TABLE anything; --");
        var plan = planner.updateAccount(UUID.randomUUID(), user);
        assertFalse(plan.query().getQuery().contains(user.getDisplayName()));
        assertEquals(user.getDisplayName(), plan.query().getNamedParameters().get("name").getValue());
        assertNotNull(plan.query().getNamedParameters().get("expected_updated_at").getValue());
    }
    @Test void planCannotBeReplacedWithArbitraryOrProductionSql() {
        assertThrows(IllegalArgumentException.class, () -> new AuthTransactionPlan(UUID.randomUUID(), NOW.plusSeconds(30),
            com.google.cloud.bigquery.QueryJobConfiguration.of("DELETE FROM `king-of-cards-509610.shared_rds_data.identity_user` WHERE TRUE")));
    }
    @Test void allWireParametersAreNonNullAndMissingNamesStaySqlNull() {
        var noName = IdentityUser.restore(UUID.randomUUID(), "it+nameless@example.test", null, BCRYPT,
            "CRM_VENDOR", true, NOW, NOW);
        var creation = planner.createUser(UUID.randomUUID(), noName, false);
        var update = planner.updateAccount(UUID.randomUUID(), noName);
        var logout = planner.logout(UUID.randomUUID(), HASH, CSRF);
        for (var plan : List.of(creation, update, logout))
            for (var parameter : plan.query().getNamedParameters().values()) assertNotNull(parameter.getValue());
        assertEquals("", creation.query().getNamedParameters().get("name").getValue());
        assertEquals("false", creation.query().getNamedParameters().get("name_present").getValue());
        assertTrue(creation.query().getQuery().contains("IF(@name_present, @name, NULL)"));
        var named = planner.createUser(UUID.randomUUID(), user(), false);
        assertEquals("true", named.query().getNamedParameters().get("name_present").getValue());
        var blank = IdentityUser.restore(UUID.randomUUID(), "it+blank@example.test", "", BCRYPT,
            "CRM_VENDOR", true, NOW, NOW);
        assertThrows(IllegalArgumentException.class, () -> planner.createUser(UUID.randomUUID(), blank, false));
    }
    @Test void byteBudgetDefaultsStayUnchangedAndOverridesAreExplicitAndBounded() {
        var ordinary = planner.logout(UUID.randomUUID(), HASH, CSRF);
        assertEquals(100_000_000L, ordinary.query().getMaximumBytesBilled());
        var approved = new AuthTransactionPlanner(Clock.fixed(NOW, ZoneOffset.UTC), 268_435_456L);
        assertEquals(268_435_456L, approved.logout(UUID.randomUUID(), HASH, CSRF).query().getMaximumBytesBilled());
        for (long invalid : new long[] {0, -1, 99_999_999L, 536_870_913L, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new AuthTransactionPlanner(Clock.systemUTC(), invalid));
    }
}
