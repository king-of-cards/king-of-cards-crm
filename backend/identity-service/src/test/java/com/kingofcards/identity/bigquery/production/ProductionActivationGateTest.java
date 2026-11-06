package com.kingofcards.identity.bigquery.production;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionActivationGateTest {
    @TempDir Path directory;
    @Test void reviewedEngineEvidenceMatchesTheCompiledAuthenticationArtifacts(){
        assertDoesNotThrow(()->ProductionActivationGate.verifyEvidence(Path.of("../docs/identity-production-verification-evidence.json")));
    }
    @Test void missingOrInventedEvidenceCannotActivateMutations()throws Exception{
        assertThrows(IllegalStateException.class,()->ProductionActivationGate.verifyEvidence(directory.resolve("absent.json")));
        var fake=directory.resolve("fake.json");Files.writeString(fake,"{\"allTwelveScenariosPassed\":true,\"productionContractPassed\":true}");
        assertThrows(IllegalStateException.class,()->ProductionActivationGate.verifyEvidence(fake));
    }
    @Test void syntheticCredentialsAndOrphanSessionsPreventActivation()throws Exception{
        var client=mock(BigQuery.class);var results=mock(TableResult.class);var row=mock(FieldValueList.class);
        when(results.iterateAll()).thenReturn(List.of(row));when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenReturn(results);
        var properties=new IdentityBigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1");
        for(var counts:List.of(new long[]{1,0},new long[]{0,1})){
            when(row.get("synthetic_users")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,Long.toString(counts[0])));
            when(row.get("orphan_sessions")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,Long.toString(counts[1])));
            assertEquals("IDENTITY_SYNTHETIC_CLEANUP_OR_INTEGRITY_REVIEW_REQUIRED",assertThrows(IdentityPersistenceException.class,
                ()->ProductionActivationGate.verifyNoSyntheticOrOrphanRecords(client,properties)).code());
        }
        verify(client,never()).create(any(JobInfo.class));
    }
}
