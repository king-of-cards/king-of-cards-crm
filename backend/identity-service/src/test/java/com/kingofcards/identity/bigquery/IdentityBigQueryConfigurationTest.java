package com.kingofcards.identity.bigquery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IdentityBigQueryConfigurationTest {
    @Test void unexpectedRequiredColumnsAreIncompatibleWithoutChangingTables() {
        var client=metadataClient("asia-south1");
        when(client.getTable(any(TableId.class))).thenAnswer(call->{
            var fields=new java.util.ArrayList<>(IdentityBigQuerySchema.tables().get(call.getArgument(0,TableId.class).getTable()).getFields());
            fields.add(Field.newBuilder("unapproved_required",StandardSQLTypeName.STRING).setMode(Field.Mode.REQUIRED).build());
            var table=mock(Table.class);when(table.getDefinition()).thenReturn(StandardTableDefinition.of(Schema.of(fields)));return table;
        });
        assertEquals("IDENTITY_SCHEMA_MISMATCH",assertThrows(IdentityPersistenceException.class,
            ()->new IdentityBigQueryValidator(client,BigQueryIdentityStoreTest.properties(true)).validate()).code());
        verify(client,never()).create(any(TableInfo.class));
    }
    @Test void disabledConfigHasRepositoriesButNoGoogleClientOrStartupRunner() {
        new ApplicationContextRunner().withUserConfiguration(IdentityBigQueryConfiguration.class)
            .withPropertyValues("identity.bigquery.enabled=false", "identity.bigquery.writes-enabled=true")
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertNotNull(context.getBean(BigQueryIdentityStore.class));
                assertTrue(context.getBeansOfType(BigQuery.class).isEmpty());
                assertTrue(context.getBeansOfType(org.springframework.boot.ApplicationRunner.class).isEmpty());
            });
    }
    @Test void noJdbcJpaOrFlywayOnIdentityClasspath() {
        for (String type : new String[] {"org.postgresql.Driver", "jakarta.persistence.Entity",
                "org.flywaydb.core.Flyway", "org.springframework.data.jpa.repository.JpaRepository"})
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
    }
    @Test void singleRolePolicyRejectsAccidentalPermissionLossAndPreservesUuid() {
        var user = new IdentityUser("a@example.test", "a@example.test", "hash", true);
        user.addRole(new IdentityRole("CRM_ADMIN", "Admin"));
        assertThrows(IllegalStateException.class, () -> user.addRole(new IdentityRole("CRM_VENDOR", "Vendor")));
        assertEquals("CRM_ADMIN", user.getRoles().iterator().next().getCode());
    }
    @Test void schemasContainOnlyTwoApprovedTablesAndRequiredSecurityFields() {
        var schemas = IdentityBigQuerySchema.tables();
        assertEquals(2, schemas.size());
        assertEquals(8, schemas.get("identity_user").getFields().size());
        assertEquals(9, schemas.get("identity_refresh_session").getFields().size());
        assertEquals(Field.Mode.NULLABLE, schemas.get("identity_user").getFields().get("name").getMode());
        assertEquals(Field.Mode.REQUIRED, schemas.get("identity_refresh_session").getFields().get("csrf_token_hash").getMode());
    }
    @Test void metadataValidationAcceptsCompatibleTablesWithoutMutation() {
        var client = metadataClient("asia-south1");
        new IdentityBigQueryValidator(client, BigQueryIdentityStoreTest.properties(true)).validate();
        verify(client).getDataset(any(DatasetId.class));
        verify(client, times(2)).getTable(any(TableId.class));
        verifyNoMoreInteractions(client);
    }
    @Test void missingDatasetFailsBeforeTableAccess() {
        var client = mock(BigQuery.class);
        var failure = assertThrows(IdentityPersistenceException.class,
            () -> new IdentityBigQueryValidator(client, BigQueryIdentityStoreTest.properties(true)).validate());
        assertEquals("IDENTITY_DATASET_MISSING", failure.code());
        verify(client, never()).getTable(any(TableId.class));
    }
    @Test void wrongLocationAndMissingTableFailClosed() {
        var wrongLocation = metadataClient("US");
        assertEquals("IDENTITY_DATASET_LOCATION_MISMATCH", assertThrows(IdentityPersistenceException.class,
            () -> new IdentityBigQueryValidator(wrongLocation, BigQueryIdentityStoreTest.properties(true)).validate()).code());
        var missingTable = metadataClient("asia-south1");
        when(missingTable.getTable(any(TableId.class))).thenReturn(null);
        assertEquals("IDENTITY_TABLE_MISSING", assertThrows(IdentityPersistenceException.class,
            () -> new IdentityBigQueryValidator(missingTable, BigQueryIdentityStoreTest.properties(true)).validate()).code());
    }
    @Test void schemaMismatchAndPermissionFailureAreSanitized() {
        var client = metadataClient("asia-south1");
        var table = mock(Table.class);
        when(table.getDefinition()).thenReturn(StandardTableDefinition.of(Schema.of(Field.of("wrong", StandardSQLTypeName.STRING))));
        when(client.getTable(any(TableId.class))).thenReturn(table);
        assertEquals("IDENTITY_SCHEMA_MISMATCH", assertThrows(IdentityPersistenceException.class,
            () -> new IdentityBigQueryValidator(client, BigQueryIdentityStoreTest.properties(true)).validate()).code());
        when(client.getDataset(any(DatasetId.class))).thenThrow(new BigQueryException(403, "sensitive"));
        var failure = assertThrows(IdentityPersistenceException.class,
            () -> new IdentityBigQueryValidator(client, BigQueryIdentityStoreTest.properties(true)).validate());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("sensitive"));
    }
    private BigQuery metadataClient(String location) {
        var client = mock(BigQuery.class);
        var dataset = mock(Dataset.class);
        when(dataset.getLocation()).thenReturn(location);
        when(client.getDataset(any(DatasetId.class))).thenReturn(dataset);
        when(client.getTable(any(TableId.class))).thenAnswer(call -> {
            var table = mock(Table.class);
            var id = call.getArgument(0, TableId.class);
            when(table.getDefinition()).thenReturn(StandardTableDefinition.of(IdentityBigQuerySchema.tables().get(id.getTable())));
            return table;
        });
        return client;
    }
}
