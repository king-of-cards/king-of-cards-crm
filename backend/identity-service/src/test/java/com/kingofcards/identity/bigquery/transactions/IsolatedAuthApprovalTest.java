package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IsolatedAuthApprovalTest {
    @Test void previousIsolatedTableApprovalCannotAuthorizeProductionNamedTableWrites() {
        assertThrows(IllegalStateException.class,()->IsolatedBigQueryAuthIT.requireApproval(Map.of(
            "KOC_AUTH_IT_APPROVAL","ALLOW_ISOLATED_AUTH_12_TESTS_20261031","KOC_AUTH_IT_RUN_ID",UUID.randomUUID().toString())));
    }
    @Test void missingWrongOrIncompleteApprovalFailsBeforeAnyCloudSetup() {
        assertThrows(IllegalStateException.class,()->IsolatedBigQueryAuthIT.requireApproval(Map.of(
            "KOC_AUTH_IT_APPROVAL","ALLOW_ISOLATED_AUTH_DML_20261030","KOC_AUTH_IT_RUN_ID",UUID.randomUUID().toString())));
        assertThrows(IllegalStateException.class, () -> IsolatedBigQueryAuthIT.requireApproval(Map.of()));
        assertThrows(IllegalStateException.class, () -> IsolatedBigQueryAuthIT.requireApproval(Map.of("KOC_AUTH_IT_APPROVAL", "true")));
        assertThrows(NullPointerException.class, () -> IsolatedBigQueryAuthIT.requireApproval(
            Map.of("KOC_AUTH_IT_APPROVAL", IsolatedBigQueryAuthIT.APPROVAL)));
        assertThrows(IllegalArgumentException.class, () -> IsolatedBigQueryAuthIT.requireApproval(
            Map.of("KOC_AUTH_IT_APPROVAL", IsolatedBigQueryAuthIT.APPROVAL, "KOC_AUTH_IT_RUN_ID", "not-a-uuid")));
        assertThrows(IllegalArgumentException.class, () -> IsolatedBigQueryAuthIT.requireApproval(
            Map.of("KOC_AUTH_IT_APPROVAL", IsolatedBigQueryAuthIT.APPROVAL, "KOC_AUTH_IT_RUN_ID", "1-1-1-1-1")));
    }
    @Test void exactApprovalAndRunIdAreAcceptedWithoutInvokingTheHarness() {
        assertDoesNotThrow(() -> IsolatedBigQueryAuthIT.requireApproval(Map.of(
            "KOC_AUTH_IT_APPROVAL", IsolatedBigQueryAuthIT.APPROVAL, "KOC_AUTH_IT_RUN_ID", UUID.randomUUID().toString())));
    }
    @Test void queryBudgetRequiresAnExplicitBoundedValueAndDoesNotEnableTests() {
        assertEquals(100_000_000L, IsolatedBigQueryAuthIT.approvedByteBudget(Map.of()));
        assertEquals(268_435_456L, IsolatedBigQueryAuthIT.approvedByteBudget(
            Map.of("KOC_AUTH_IT_MAX_BYTES_BILLED", "268435456")));
        assertThrows(IllegalArgumentException.class, () -> IsolatedBigQueryAuthIT.approvedByteBudget(
            Map.of("KOC_AUTH_IT_MAX_BYTES_BILLED", "0")));
        assertThrows(IllegalArgumentException.class, () -> IsolatedBigQueryAuthIT.approvedByteBudget(
            Map.of("KOC_AUTH_IT_MAX_BYTES_BILLED", "999999999999")));
        assertThrows(IllegalStateException.class, () -> IsolatedBigQueryAuthIT.requireApproval(
            Map.of("KOC_AUTH_IT_MAX_BYTES_BILLED", "268435456")));
    }
}
