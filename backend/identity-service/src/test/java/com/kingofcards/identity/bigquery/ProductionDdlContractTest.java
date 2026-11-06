package com.kingofcards.identity.bigquery;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import com.google.cloud.bigquery.Field;
import org.junit.jupiter.api.Test;

/** Offline review contract; does not parse/execute SQL on BigQuery or assert engine behavior. */
class ProductionDdlContractTest {
    private String ddl() throws Exception {
        return Files.readString(Path.of("..","docs","identity-bigquery-production-setup.sql"));
    }
    @Test void approvedDdlMatchesAllSeventeenJavaFieldsIncludingNullability() throws Exception {
        var matcher=Pattern.compile("CREATE TABLE IF NOT EXISTS `king-of-cards-509610\\.shared_rds_data\\.(\\w+)` \\((.*?)\\)\\s*CLUSTER BY",Pattern.DOTALL).matcher(ddl());
        var names=new HashSet<String>();
        var schemas=IdentityBigQuerySchema.tables();
        while(matcher.find()) {
            String table=matcher.group(1); assertTrue(schemas.containsKey(table)); assertTrue(names.add(table));
            String[] definitions=matcher.group(2).strip().split(",");
            assertEquals(schemas.get(table).getFields().size(),definitions.length);
            for (String definition:definitions) {
                var tokens=definition.strip().split("\\s+");
                var field=schemas.get(table).getFields().get(tokens[0]);
                assertEquals(field.getType().getStandardType().name(),tokens[1]);
                assertEquals(field.getMode()==Field.Mode.REQUIRED,definition.strip().endsWith("NOT NULL"));
            }
        }
        assertEquals(schemas.keySet(),names);
    }
    @Test void ddlIsReviewOnlyAndCannotMutateRowsOrCreateAdditionalResources() throws Exception {
        String sql=ddl().replaceAll("(?m)--.*$", "");
        assertFalse(Pattern.compile("(?m)^\\s*(INSERT|UPDATE|DELETE|MERGE|ALTER|DROP|TRUNCATE|CREATE\\s+OR\\s+REPLACE)\\b",Pattern.CASE_INSENSITIVE).matcher(sql).find());
        assertFalse(sql.contains("CREATE SCHEMA")); assertFalse(sql.contains("crm_leads"));
        assertTrue(sql.contains("Existing resource or schema mismatch"));
        assertTrue(sql.contains("Both required tables must exist"));
    }
}
