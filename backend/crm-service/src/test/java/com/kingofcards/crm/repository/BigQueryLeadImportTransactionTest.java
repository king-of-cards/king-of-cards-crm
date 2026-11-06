package com.kingofcards.crm.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.dto.CreateLeadRequest;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.LeadStage;
import org.junit.jupiter.api.*;

/** SDK simulations and SQL inspection only; not engine/concurrency proof. */
class BigQueryLeadImportTransactionTest {
    final BigQuery client=mock(BigQuery.class);
    final UUID actor=UUID.randomUUID(),vendor=UUID.randomUUID(),operation=UUID.randomUUID();
    final String fingerprint="a".repeat(64);
    final Map<JobId,Job> jobs=new HashMap<>();
    final List<QueryJobConfiguration> submitted=new ArrayList<>();
    BigQueryLeadImportTransaction adapter;
    CreateLeadRequest row(){return new CreateLeadRequest("Synthetic", "+919876543210","Service",LeadStage.CONTACTED,"ExistingSource",vendor);}
    @BeforeEach void setup(){
        adapter=new BigQueryLeadImportTransaction(client,new BigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1","crm_leads",false,true,false));
        when(client.getJob(any(JobId.class))).thenAnswer(call->jobs.get(call.getArgument(0)));
        when(client.create(any(JobInfo.class))).thenAnswer(call->{JobInfo info=call.getArgument(0);return receipt(info,1,0);});
    }
    Job receipt(JobInfo info,int inserted,int duplicates) throws Exception {
        QueryJobConfiguration query=info.getConfiguration();submitted.add(query);
        Job job=mock(Job.class);when(job.getJobId()).thenReturn(info.getJobId());when(job.getConfiguration()).thenReturn(query);
        var status=mock(JobStatus.class);when(status.getState()).thenReturn(JobStatus.State.DONE);when(job.getStatus()).thenReturn(status);
        var rows=mock(TableResult.class);var row=mock(FieldValueList.class);
        when(row.get("fingerprint")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,query.getNamedParameters().get("fingerprint").getValue()));
        when(row.get("total")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"1"));
        when(row.get("imported")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,""+inserted));
        when(row.get("duplicates")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,""+duplicates));
        when(rows.iterateAll()).thenReturn(List.of(row));when(job.getQueryResults()).thenReturn(rows);jobs.put(info.getJobId(),job);return job;
    }
    @Test void repeatedOperationRecoversOriginalJobWithoutAnotherSubmission() {
        assertEquals(1,adapter.importBatch(operation,actor,fingerprint,List.of(row())).imported());
        assertEquals(1,adapter.importBatch(operation,actor,fingerprint,List.of(row())).imported());
        verify(client,times(1)).create(any(JobInfo.class));
        var sql=submitted.getFirst();
        assertTrue(sql.getQuery().contains("BEGIN TRANSACTION"));assertTrue(sql.getQuery().contains("DELETE FROM `king-of-cards-509610.shared_rds_data.crm_leads` WHERE FALSE"));
        assertTrue(sql.getQuery().contains("COMMIT TRANSACTION"));assertTrue(sql.getQuery().contains("WHERE NOT EXISTS"));
        assertFalse(sql.getQuery().contains("CREATE TABLE"));assertNull(sql.getCreateDisposition());
        assertEquals(536_870_912L,sql.getMaximumBytesBilled());
    }
    @Test void differentPayloadCannotReuseOperationAndRowIdsSurviveNewOperation() {
        adapter.importBatch(operation,actor,fingerprint,List.of(row()));
        var changed=new CreateLeadRequest("Changed",row().phone(),row().service(),row().stage(),row().source(),vendor);
        assertEquals("BULK_OPERATION_BINDING_MISMATCH",assertThrows(LeadApiException.class,()->adapter.importBatch(operation,actor,fingerprint,List.of(changed))).code());
        adapter.importBatch(UUID.randomUUID(),actor,fingerprint,List.of(row()));
        assertEquals(submitted.get(0).getNamedParameters().get("id_0"),submitted.get(1).getNamedParameters().get("id_0"));
    }
    @Test void ambiguousSubmissionRecoversSameReceiptAndDoesNotReplay() throws Exception {
        when(client.create(any(JobInfo.class))).thenAnswer(call->{receipt(call.getArgument(0),1,0);throw new BigQueryException(503,"Synthetic response lost");});
        assertEquals(1,adapter.importBatch(operation,actor,fingerprint,List.of(row())).imported());
        verify(client,times(1)).create(any(JobInfo.class));
    }
    @Test void unknownJobFailsClosedWithoutRetryOrPartialSuccess() {
        when(client.create(any(JobInfo.class))).thenThrow(new BigQueryException(503,"Synthetic uncertain submission"));
        assertEquals("BULK_COMMIT_UNCERTAIN",assertThrows(LeadApiException.class,()->adapter.importBatch(operation,actor,fingerprint,List.of(row()))).code());
        verify(client,times(1)).create(any(JobInfo.class));
    }
    @Test void conflictRemainsBoundToOriginalJobUntilReconciled() throws Exception {
        when(client.create(any(JobInfo.class))).thenAnswer(call->{Job job=receipt(call.getArgument(0),0,1);when(job.getStatus().getError()).thenReturn(new BigQueryError("concurrentUpdate",null,"Synthetic conflict"));return job;});
        for(int i=0;i<2;i++)assertEquals("BULK_TRANSACTION_CONFLICT_RECONCILE_REQUIRED",assertThrows(LeadApiException.class,()->adapter.importBatch(operation,actor,fingerprint,List.of(row()))).code());
        verify(client,times(1)).create(any(JobInfo.class));
    }
    @Test void confirmedDuplicateCountsAndParameterSafetyArePreserved() throws Exception {
        when(client.create(any(JobInfo.class))).thenAnswer(call->receipt(call.getArgument(0),0,1));
        var value=new CreateLeadRequest("Synthetic ' OR TRUE --",row().phone(),row().service(),row().stage(),row().source(),vendor);
        var result=adapter.importBatch(operation,actor,fingerprint,List.of(value));
        assertEquals(0,result.imported());assertEquals(1,result.duplicates());
        assertFalse(submitted.getFirst().getQuery().contains(value.name()));
        assertEquals(value.name(),submitted.getFirst().getNamedParameters().get("name_0").getValue());
    }
}
