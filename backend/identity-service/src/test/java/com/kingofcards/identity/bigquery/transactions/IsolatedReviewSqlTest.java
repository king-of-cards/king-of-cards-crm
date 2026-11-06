package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.bigquery.IdentityBigQuerySchema;
import org.junit.jupiter.api.Test;

/** Regression lint for review-only SQL. It does not execute or engine-parse SQL. */
class IsolatedReviewSqlTest {
    // Historical review artifacts only. These constants are not an executable harness target.
    static final String LEGACY_USERS="`king-of-cards-509610.shared_rds_data.identity_user_it_20261030`";
    static final String LEGACY_SESSIONS="`king-of-cards-509610.shared_rds_data.identity_refresh_session_it_20261030`";
    @Test void ddlHasOnlyTwoTestTablesAndExactlyTheApprovedSchema() throws Exception {
        String sql = doc("identity-bigquery-isolated-test-tables.sql");
        assertEquals(Set.of(LEGACY_USERS, LEGACY_SESSIONS), references(sql));
        var matcher = Pattern.compile("CREATE TABLE `([^`]+)` \\((.*?)\\)\\s*CLUSTER", Pattern.DOTALL).matcher(sql);
        int tables = 0;
        while (matcher.find()) {
            tables++;
            String original = matcher.group(1).endsWith("identity_user_it_20261030") ? "identity_user" : "identity_refresh_session";
            var expected = IdentityBigQuerySchema.tables().get(original).getFields();
            var columns = matcher.group(2).strip().split(",");
            assertEquals(expected.size(), columns.length);
            for (int i = 0; i < columns.length; i++) {
                var column = Pattern.compile("(\\w+)\\s+(STRING|BOOL|TIMESTAMP)(\\s+NOT NULL)?").matcher(columns[i].strip());
                assertTrue(column.matches());
                var field = expected.get(i);
                assertEquals(field.getName(), column.group(1));
                assertEquals(field.getType(), Field.of("ignored", StandardSQLTypeName.valueOf(column.group(2))).getType());
                assertEquals(field.getMode() == Field.Mode.REQUIRED, column.group(3) != null);
            }
        }
        assertEquals(2, tables);
        assertFalse(sql.contains("OR REPLACE")); assertFalse(sql.contains("IF NOT EXISTS"));
        assertFalse(sql.contains("CREATE SCHEMA")); assertFalse(sql.contains("ALTER TABLE"));
    }
    @Test void cleanupIsLockedScopedToFrozenRunIdsAndRequiresExactApprovedCounts() throws Exception {
        String sql = doc("identity-bigquery-isolated-cleanup-review.sql");
        assertEquals(Set.of(LEGACY_USERS, LEGACY_SESSIONS), references(sql));
        assertTrue(sql.contains("@cleanup_approval = CONCAT('DELETE_ISOLATED_AUTH_RUN_', @run_id)"));
        assertTrue(sql.contains("expected_users = @approved_users AND expected_sessions = @approved_sessions"));
        assertTrue(sql.contains("GROUP BY id HAVING COUNT(*) > 1"));
        assertTrue(sql.contains("SET run_user_ids = ARRAY("));
        assertTrue(sql.contains("WHERE user_id IN UNNEST(run_user_ids)"));
        assertTrue(sql.contains("WHERE id IN UNNEST(run_user_ids)"));
        int check = sql.indexOf("ASSERT expected_users = @approved_users");
        int destructive = sql.indexOf("DELETE FROM " + LEGACY_SESSIONS + "\n");
        assertTrue(check > 0 && check < destructive);
        assertTrue(sql.indexOf("DELETE FROM " + LEGACY_USERS + " WHERE FALSE;")
            < sql.indexOf("DELETE FROM " + LEGACY_SESSIONS + " WHERE FALSE;"));
        assertFalse(sql.contains("TRUNCATE")); assertFalse(sql.contains("DROP TABLE"));
        assertFalse(sql.contains("EXECUTE IMMEDIATE"));
    }
    private String doc(String name) throws Exception {
        var path = Path.of(System.getProperty("basedir", "."), "..", "docs", name);
        return Files.readString(path).replace("\r\n", "\n").replaceAll("(?m)^--.*$", "");
    }
    @Test void productionNamedCleanupIsOnlyExactManifestBoundDeletes() throws Exception {
        String sql=doc("identity-bigquery-verification-cleanup.sql");
        assertEquals(Set.of(IsolatedAuthTarget.users(),IsolatedAuthTarget.sessions()),references(sql));
        var statements=Pattern.compile("DELETE FROM (`[^`]+`)(.*?);",Pattern.DOTALL).matcher(sql);
        int locks=0,deletes=0;
        while(statements.find()) {
            String predicate=statements.group(2).strip();
            if(predicate.equals("WHERE FALSE")){locks++;continue;}
            deletes++;assertTrue(predicate.contains("WHERE EXISTS"));assertTrue(predicate.contains("row_fingerprint"));
            assertTrue(predicate.contains("id = e.id"));
        }
        assertEquals(2,locks);assertEquals(2,deletes);
        assertFalse(sql.contains("TRUNCATE"));assertFalse(sql.contains("DROP TABLE"));assertFalse(sql.contains("EXECUTE IMMEDIATE"));
    }
    @Test void productionNamedCleanupRequiresOwnershipFingerprintCountsAndSeparateApproval() throws Exception {
        String sql=doc("identity-bigquery-verification-cleanup.sql");
        assertTrue(sql.contains("DELETE_AUTH_VERIFICATION_RUN_"));
        assertTrue(sql.contains("ARRAY_LENGTH(expected_users) = @approved_user_count"));
        assertTrue(sql.contains("s.user_id = e.user_id AND s.token_family_id = e.token_family_id"));
        assertTrue(sql.contains("u.email = e.email AND STARTS_WITH(u.email, CONCAT('it+', @run_id, '-'))"));
        assertTrue(sql.contains("ASSERT @@row_count = @approved_session_count"));
        assertTrue(sql.contains("ASSERT @@row_count = @approved_user_count"));
        assertTrue(sql.indexOf("DELETE FROM "+IsolatedAuthTarget.sessions()+" AS s")
            < sql.indexOf("DELETE FROM "+IsolatedAuthTarget.users()+" AS u"));
        assertTrue(sql.indexOf("Session ownership or fingerprint changed")
            < sql.indexOf("DELETE FROM "+IsolatedAuthTarget.sessions()+" AS s"));
    }
    private Set<String> references(String sql) {
        var result = new HashSet<String>();
        var matcher = Pattern.compile("`[^`]+`").matcher(sql);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
}
