package com.kingofcards.identity.bigquery;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

/** SDK contract tests. No live queries and no claim about engine concurrency. */
class BigQueryIdentityStoreTest {
    @Test void vendorSetupMutationsRemainUnconditionallyGatedWithoutAnyClientCalls() {
        var client=mock(BigQuery.class);
        var gated=new BigQueryIdentityStore(()->client,new IdentityBigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1"));
        assertThrows(IdentityPersistenceException.class,()->gated.createVendorInvitation(null,null));
        assertThrows(IdentityPersistenceException.class,()->gated.completeVendorPasswordSetup("a".repeat(64),"b".repeat(64),"unused"));
        verifyNoInteractions(client);
    }
    BigQuery client;
    BigQueryIdentityStore store;
    final UUID id = UUID.randomUUID();
    @BeforeEach void setup() {
        client = mock(BigQuery.class);
        store = new BigQueryIdentityStore(() -> client, properties(true));
    }
    @Test void normalizedLookupIsParameterizedAndPreservesSubjectAndRole() throws Exception {
        results(row("email", "vendor@example.test"));
        var user = store.findByNormalizedEmail(" Vendor@Example.test ").orElseThrow();
        assertEquals(id, user.getId());
        assertEquals("CRM_VENDOR", user.getRoles().iterator().next().getCode());
        var query = ArgumentCaptor.forClass(QueryJobConfiguration.class);
        var job = ArgumentCaptor.forClass(JobId.class);
        verify(client).query(query.capture(), job.capture());
        assertTrue(query.getValue().getQuery().contains("email = @email LIMIT 2"));
        assertFalse(query.getValue().getQuery().contains("vendor@example.test"));
        assertEquals("vendor@example.test", query.getValue().getNamedParameters().get("email").getValue());
        assertEquals("king-of-cards-509610", job.getValue().getProject());
        assertEquals("asia-south1", job.getValue().getLocation());
        assertFalse(query.getValue().useQueryCache());
        assertEquals(100_000_000L, query.getValue().getMaximumBytesBilled());
    }
    @Test void idLookupUsesUuidParameterAndPreservesNullName() throws Exception {
        var row = row("name", null);
        results(row);
        assertNull(store.findById(id).orElseThrow().getDisplayName());
    }
    @Test void missingUserReturnsEmpty() throws Exception {
        results();
        assertTrue(store.findByNormalizedEmail("missing@example.test").isEmpty());
    }
    @Test void duplicateEmailRowsFailClosedInsteadOfPickingOne() throws Exception {
        results(row("email", "vendor@example.test"), row("email", "vendor@example.test"));
        assertEquals("IDENTITY_DATA_INTEGRITY_FAILURE", assertThrows(IdentityPersistenceException.class,
            () -> store.findByNormalizedEmail("vendor@example.test")).code());
    }
    @Test void duplicateSubjectRowsFailClosed() throws Exception {
        results(row("email", "vendor@example.test"), row("email", "vendor@example.test"));
        assertThrows(IdentityPersistenceException.class, () -> store.findById(id));
    }
    @Test void invalidRoleAndNonNormalizedStoredEmailFailClosed() throws Exception {
        results(row("role", "SUPERUSER"));
        assertThrows(IdentityPersistenceException.class, () -> store.findById(id));
        results(row("email", "Vendor@Example.test"));
        assertThrows(IdentityPersistenceException.class, () -> store.findById(id));
        results(row("updated_at", "0.000000"));
        assertEquals("IDENTITY_DATA_INTEGRITY_FAILURE",assertThrows(IdentityPersistenceException.class,()->store.findById(id)).code());
    }
    @Test void disabledAccountIsNotSilentlyEnabledByMapping() throws Exception {
        results(row("enabled", "false"));
        assertFalse(store.findById(id).orElseThrow().isEnabled());
    }
    @Test void crossLookupDuplicateSubjectsOrEmailsAreRejected() throws Exception {
        results(row("subject_count", "2"));
        assertThrows(IdentityPersistenceException.class, () -> store.findByNormalizedEmail("vendor@example.test"));
        results(row("email_count", "2"));
        assertThrows(IdentityPersistenceException.class, () -> store.findById(id));
    }
    @Test void sdkFailureIsSanitizedWithoutLeakingCause() throws Exception {
        when(client.query(any(QueryJobConfiguration.class), any(JobId.class)))
            .thenThrow(new BigQueryException(403, "synthetic-sensitive-error"));
        var failure = assertThrows(IdentityPersistenceException.class, () -> store.findById(id));
        assertEquals("IDENTITY_BIGQUERY_UNAVAILABLE", failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("synthetic-sensitive-error"));
    }
    @Test void interruptionRestoresThreadFlag() throws Exception {
        when(client.query(any(QueryJobConfiguration.class), any(JobId.class))).thenThrow(new InterruptedException());
        try {
            assertThrows(IdentityPersistenceException.class, () -> store.findById(id));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void disabledConfigurationDoesNotEvenResolveClient() {
        @SuppressWarnings("unchecked") Supplier<BigQuery> provider = mock(Supplier.class);
        var disabled = new BigQueryIdentityStore(provider, properties(false));
        assertEquals("IDENTITY_BIGQUERY_DISABLED", assertThrows(IdentityPersistenceException.class,
            () -> disabled.findById(id)).code());
        verifyNoInteractions(provider);
    }
    @Test void vendorsUseBoundedFixedSortingAndEligibilityPredicate() throws Exception {
        var countRow = mock(FieldValueList.class);
        when(countRow.get("total")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE, "1"));
        var count = mock(TableResult.class);
        when(count.iterateAll()).thenReturn(List.of(countRow));
        var items = mock(TableResult.class);
        var vendorRow = row("email", "vendor@example.test");
        when(items.iterateAll()).thenReturn(List.of(vendorRow));
        when(client.query(any(QueryJobConfiguration.class), any(JobId.class))).thenReturn(count, items);
        assertEquals(id, store.findAssignableVendors(PageRequest.of(0, 20)).getContent().getFirst().getId());
        var query = ArgumentCaptor.forClass(QueryJobConfiguration.class);
        verify(client, times(2)).query(query.capture(), any(JobId.class));
        assertTrue(query.getAllValues().getLast().getQuery().contains("ORDER BY id ASC LIMIT @limit OFFSET @offset"));
        assertTrue(query.getAllValues().getLast().getQuery().contains("enabled = TRUE"));
        assertThrows(IllegalArgumentException.class, () -> store.findAssignableVendors(PageRequest.of(0, 101)));
    }
    @Test void allUnverifiedMutationsFailClosedWithoutAnySdkCall() {
        var user = new IdentityUser("a@example.test", "a@example.test", "hash", true);
        var now = Instant.now();
        var session = new RefreshTokenSession(user.getId(), UUID.randomUUID(), "hash", "csrf-hash", now, now.plusSeconds(60));
        List<Runnable> operations = List.of(() -> store.createUnique(user), () -> store.createFirstAdministrator(user),
            () -> store.save(user), () -> store.createForAuthenticatedUser(user, session),
            () -> store.rotate("hash", "csrf-hash", UUID.randomUUID(), "next", "next-csrf", now, now.plusSeconds(60)),
            () -> store.revoke("hash", "csrf-hash", now));
        for (var operation : operations) assertEquals("IDENTITY_MUTATIONS_UNVERIFIED",
            assertThrows(IdentityPersistenceException.class, operation::run).code());
        verifyNoInteractions(client);
    }
    @Test void concurrentAttemptsCannotBypassMutationGate() throws Exception {
        var user = new IdentityUser("a@example.test", "a@example.test", "hash", true);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var attempts = new ArrayList<Callable<String>>();
            for (int i = 0; i < 12; i++) {
                final int operation = i % 4;
                attempts.add(() -> assertThrows(IdentityPersistenceException.class, () -> {
                    switch (operation) {
                        case 0 -> store.createUnique(user);
                        case 1 -> store.createFirstAdministrator(user);
                        case 2 -> store.rotate("hash", "csrf", UUID.randomUUID(), "next", "next-csrf",
                            Instant.now(), Instant.now().plusSeconds(60));
                        default -> store.revoke("hash", "csrf", Instant.now());
                    }
                }).code());
            }
            for (var result : executor.invokeAll(attempts)) assertEquals("IDENTITY_MUTATIONS_UNVERIFIED", result.get());
        }
        verifyNoInteractions(client);
        // This proves only the gate; not distributed duplicate-email prevention.
    }
    @Test void targetGuardNeverAllowsCrmOrUnrelatedTables() {
        assertThrows(IllegalArgumentException.class, () -> properties(true).table("crm_leads"));
        assertThrows(IllegalArgumentException.class, () -> new IdentityBigQueryProperties(true,
            "other-project", "shared_rds_data", "asia-south1").validate());
    }
    private void results(FieldValueList... rows) throws Exception {
        var result = mock(TableResult.class);
        when(result.iterateAll()).thenReturn(Arrays.asList(rows));
        when(client.query(any(QueryJobConfiguration.class), any(JobId.class))).thenReturn(result);
    }
    private FieldValueList row(String key, String value) {
        var values = new HashMap<String, String>();
        values.put("id", id.toString()); values.put("name", "Approved Vendor");
        values.put("email", "vendor@example.test"); values.put("password_hash", "$2a$12$" + "A".repeat(53));
        values.put("subject_count", "1"); values.put("email_count", "1");
        values.put("role", "CRM_VENDOR"); values.put("enabled", "true");
        values.put("created_at", "1700000000.000000"); values.put("updated_at", "1700000000.000000");
        values.put(key, value);
        var row = mock(FieldValueList.class);
        when(row.get(anyString())).thenAnswer(call -> FieldValue.of(FieldValue.Attribute.PRIMITIVE,
            values.get(call.getArgument(0, String.class))));
        return row;
    }
    static IdentityBigQueryProperties properties(boolean enabled) {
        return new IdentityBigQueryProperties(enabled, "king-of-cards-509610", "shared_rds_data", "asia-south1");
    }
}
