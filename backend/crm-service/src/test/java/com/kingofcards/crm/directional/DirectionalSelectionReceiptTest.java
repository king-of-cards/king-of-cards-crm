package com.kingofcards.crm.directional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.LeadStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the real BigQuery adapter and immutable job reconstruction with SDK mocks. */
class DirectionalSelectionReceiptTest {
    static final String SCOPE="a".repeat(64);
    static class Fixture {
        final DirectionalBigQuerySafetyTest f=new DirectionalBigQuerySafetyTest();
        final BigQueryDirectionalRepository repo;
        final LeadDirection direction;
        final Instant submitted=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final Draft draft;
        final QueryJobConfiguration query;
        final JobId id;
        final Job job=mock(Job.class);
        final JobStatus status=mock(JobStatus.class);
        Fixture(LeadDirection direction,String scope,boolean importing) throws Exception {
            this.direction=direction;f.metadata();repo=f.repo(true);
            draft=new Draft(direction.normalize(Map.of("Name","Synthetic receipt", "Phone","001234567890")),new Metadata("photography",LeadStage.NEW,direction.name()));
            query=BigQueryDirectionalRepository.bindSelection(repo.plan(f.vendor,direction,f.actor,f.op,submitted,null,0,List.of(draft),importing,"Original trusted name"),scope);
            id=JobId.newBuilder().setProject(f.properties.projectId()).setLocation(f.properties.location()).setJob("koc_direction_v1_"+f.actor.toString().replace("-","")+"_"+f.op.toString().replace("-","")).build();
            when(job.getJobId()).thenReturn(id);when(job.getConfiguration()).thenReturn(query);when(job.getStatus()).thenReturn(status);when(status.getState()).thenReturn(JobStatus.State.DONE);when(f.client.getJob(id)).thenReturn(job);
            var row=mock(FieldValueList.class);
            when(row.get("row_index")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"1"));
            when(row.get("status")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"IMPORTED"));
            when(row.get("lead_id")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,query.getNamedParameters().get("id_0").getValue()));
            when(row.get("error")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,null));
            var result=mock(TableResult.class);when(result.iterateAll()).thenReturn(List.of(row));when(job.getQueryResults()).thenReturn(result);
        }
    }
    @ParameterizedTest @EnumSource(LeadDirection.class)
    void legacyAndSelectionBoundJobsReconcileToCommittedWithoutNewSubmission(LeadDirection direction) throws Exception {
        for(String scope:Arrays.asList(null,SCOPE)){
            var x=new Fixture(direction,scope,true);var receipt=x.repo.operation(x.f.vendor,direction,x.f.actor,x.f.op);
            assertThat(receipt.state()).isEqualTo("COMMITTED");assertThat(receipt.imported()).isEqualTo(1);assertThat(receipt.operationId()).isEqualTo(x.f.op);
            verify(x.f.client,never()).create(any(JobInfo.class));verify(x.f.client,never()).query(any(QueryJobConfiguration.class),any(JobId.class));
        }
    }
    @Test void successfulSelectedImportAndIdempotentRetryReturnReceiptWithoutFalseConflict() throws Exception {
        var x=new Fixture(LeadDirection.KOC_TO_VENDOR,SCOPE,true);
        when(x.f.client.getJob(x.id)).thenReturn(null,x.job);when(x.f.client.create(any(JobInfo.class))).thenReturn(x.job);
        var first=x.repo.writeScopedImport(x.f.vendor,x.direction,x.f.actor,x.f.op,x.submitted,List.of(x.draft),"Original trusted name",SCOPE);
        assertThat(first.state()).isEqualTo("COMMITTED");
        assertThat(x.repo.writeScopedImport(x.f.vendor,x.direction,x.f.actor,x.f.op,x.submitted,List.of(x.draft),"Current renamed vendor",SCOPE)).isEqualTo(first);
        assertThatThrownBy(()->x.repo.writeScopedImport(x.f.vendor,x.direction,x.f.actor,x.f.op,x.submitted,List.of(x.draft),"Original trusted name","b".repeat(64))).isInstanceOf(LeadApiException.class).hasMessageContaining("different request");
        verify(x.f.client,times(1)).create(any(JobInfo.class));
    }
    @Test void modifiedSqlOrSelectionOnSingleLeadIsNeverAcceptedAsCommitted() throws Exception {
        var altered=new Fixture(LeadDirection.KOC_TO_VENDOR,SCOPE,true);
        when(altered.job.getConfiguration()).thenReturn(altered.query.toBuilder().setQuery(altered.query.getQuery()+"\nSELECT 'untrusted';").build());
        assertThatThrownBy(()->altered.repo.operation(altered.f.vendor,altered.direction,altered.f.actor,altered.f.op)).isInstanceOf(LeadApiException.class).hasMessageContaining("different request");
        var single=new Fixture(LeadDirection.KOC_TO_VENDOR,SCOPE,false);
        assertThatThrownBy(()->single.repo.operation(single.f.vendor,single.direction,single.f.actor,single.f.op)).isInstanceOf(LeadApiException.class).hasMessageContaining("scope mismatch");
        verify(altered.f.client,never()).create(any(JobInfo.class));verify(single.f.client,never()).create(any(JobInfo.class));
    }
    @Test void failedSelectedJobStillRequiresReviewRatherThanAReplacementWrite() throws Exception {
        var x=new Fixture(LeadDirection.KOC_TO_VENDOR,SCOPE,true);when(x.status.getError()).thenReturn(new BigQueryError("invalidQuery",null,"Synthetic test failure"));
        assertThatThrownBy(()->x.repo.operation(x.f.vendor,x.direction,x.f.actor,x.f.op)).isInstanceOf(LeadApiException.class).satisfies(error->assertThat(((LeadApiException)error).code()).isEqualTo("DIRECTIONAL_JOB_FAILED_RECONCILE_REQUIRED"));
        verify(x.f.client,never()).create(any(JobInfo.class));
    }
}
