package com.kingofcards.identity.bigquery.production;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

/** Simulated SDK receipts only, not proof of the BigQuery concurrency protocol. */
class BigQueryAdminStatusTransactionTest {
    final BigQuery client=mock(BigQuery.class);
    final UUID operation=UUID.randomUUID(),actor=UUID.randomUUID(),target=UUID.randomUUID();
    final java.time.Instant deadline=java.time.Instant.now().plusSeconds(60);
    final Map<JobId,Job> jobs=new HashMap<>();QueryJobConfiguration submitted;
    final BigQueryAdminStatusTransaction adapter=new BigQueryAdminStatusTransaction(client,
        new IdentityBigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1"));
    String outcome="UPDATED";
    @BeforeEach void setup(){when(client.getJob(any(JobId.class))).thenAnswer(call->jobs.get(call.getArgument(0)));
        when(client.create(any(JobInfo.class))).thenAnswer(call->{JobInfo info=call.getArgument(0);submitted=info.getConfiguration();
            Job job=mock(Job.class);when(job.getJobId()).thenReturn(info.getJobId());when(job.getConfiguration()).thenReturn(submitted);
            var status=mock(JobStatus.class);when(status.getState()).thenReturn(JobStatus.State.DONE);when(job.getStatus()).thenReturn(status);
            var result=mock(TableResult.class);var row=mock(FieldValueList.class);
            when(row.get("status")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,outcome));
            when(row.get("actor")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,actor.toString()));
            when(row.get("target")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,target.toString()));
            when(row.get("enabled")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,submitted.getNamedParameters().get("enabled").getValue()));
            when(result.iterateAll()).thenReturn(List.of(row));when(job.getQueryResults()).thenReturn(result);jobs.put(info.getJobId(),job);return job;});}
    @Test void updateUsesAtomicLastAdminGuardAndRevokesSessionsWithoutChangingCredentials() {
        adapter.setEnabled(operation,actor,target,false,deadline);
        assertTrue(submitted.getQuery().contains("WHERE role='CRM_ADMIN' AND enabled=TRUE)<=1"));
        assertTrue(submitted.getQuery().contains("SET revoked_at=now_ts WHERE user_id=@target"));
        assertTrue(submitted.getQuery().contains("ASSERT @@row_count=1"));
        assertFalse(submitted.getQuery().contains("password_hash="));assertFalse(submitted.getQuery().contains("email="));
        adapter.setEnabled(operation,actor,target,false,deadline);verify(client,times(1)).create(any(JobInfo.class));
    }
    @Test void selfBlockNeverReachesSdk() {
        assertThrows(AccessDeniedException.class,()->adapter.setEnabled(operation,actor,actor,false,deadline));verifyNoInteractions(client);
    }
    @Test void simulatedLastAdminAndBlockedActorOutcomesAreNotSuccessfulWrites() {
        outcome="LAST_ACTIVE_ADMIN";
        assertEquals("LAST_ACTIVE_ADMIN",assertThrows(IdentityPersistenceException.class,()->adapter.setEnabled(operation,actor,target,false,deadline)).code());
        outcome="ACTOR_FORBIDDEN";
        assertThrows(AccessDeniedException.class,()->adapter.setEnabled(UUID.randomUUID(),actor,target,false,deadline));
    }
    @Test void unknownSubmissionCannotBeReplayedUnderAnotherJob() {
        when(client.create(any(JobInfo.class))).thenThrow(new BigQueryException(503,"Synthetic uncertain submission"));
        assertThrows(IdentityPersistenceException.class,()->adapter.setEnabled(operation,actor,target,true,deadline));
        verify(client,times(1)).create(any(JobInfo.class));
    }
}
