package com.kingofcards.identity.bigquery.production;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import com.kingofcards.identity.entity.IdentityUser;
import org.junit.jupiter.api.Test;

class ProductionAuthPlannerTest {
    private final ProductionAuthPlanner planner=new ProductionAuthPlanner(Clock.fixed(ProductionFixture.NOW,ZoneOffset.UTC));
    private IdentityUser user(String email){return IdentityUser.restore(UUID.randomUUID(),email,"Account Name",
        ProductionFixture.BCRYPT,"CRM_ADMIN",true,ProductionFixture.NOW,ProductionFixture.NOW);}
    @Test void realAccountEmailUsesIndependentProductionScriptAndJobNamespace(){
        var plan=planner.createUser(UUID.randomUUID(),user("admin@example.org"),true);
        assertEquals("admin@example.org",plan.query().getNamedParameters().get("email").getValue());
        assertFalse(plan.query().getNamedParameters().containsKey("verification_run_id"));
        assertTrue(plan.jobId().getJob().startsWith("koc_auth_runtime_"));
        assertFalse(plan.toString().contains("admin@example.org"));
        assertFalse(plan.query().getQuery().contains("@verification_run_id"));
        assertFalse(plan.query().getQuery().contains("VENDOR_INVITE"));
        assertFalse(plan.query().getQuery().contains("VENDOR_SETUP"));
    }
    @Test void productionSqlPreservesBothLocksVersionChecksAndAffectedRowAssertions(){
        String sql=planner.createUser(UUID.randomUUID(),user("admin@example.org"),true).query().getQuery();
        assertTrue(sql.contains("DELETE FROM `king-of-cards-509610.shared_rds_data.identity_user` WHERE FALSE"));
        assertTrue(sql.contains("DELETE FROM `king-of-cards-509610.shared_rds_data.identity_refresh_session` WHERE FALSE"));
        assertTrue(sql.contains("updated_at = @expected_updated_at"));
        assertTrue(sql.contains("ASSERT @@row_count = 1"));
        assertTrue(sql.contains("COMMIT TRANSACTION"));
        assertFalse(sql.contains("CREATE TABLE"));assertFalse(sql.contains("ALTER TABLE"));assertFalse(sql.contains("DROP TABLE"));
        var references=java.util.regex.Pattern.compile("`([^`]+)`").matcher(sql);
        while(references.find())assertTrue(Set.of("king-of-cards-509610.shared_rds_data.identity_user",
            "king-of-cards-509610.shared_rds_data.identity_refresh_session").contains(references.group(1)));
    }
    @Test void invalidRealAccountInputAndArbitrarySqlCannotBePlanned(){
        assertThrows(IllegalArgumentException.class,()->planner.createUser(UUID.randomUUID(),user("invalid"),false));
        var plan=planner.createUser(UUID.randomUUID(),user("admin@example.org"),true);
        assertThrows(IllegalArgumentException.class,()->new ProductionAuthPlan(UUID.randomUUID(),plan.deadline(),
            com.google.cloud.bigquery.QueryJobConfiguration.newBuilder("DELETE FROM unrelated WHERE TRUE").build()));
    }
}
