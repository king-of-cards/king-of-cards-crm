package com.kingofcards.crm.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.bigquery.CrmLeadSchema;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.mockito.ArgumentCaptor;

class BigQueryLeadRepositoryTest {
    @Test void sourceFilterIsParameterizedAndDoesNotInventAnEnum() throws Exception {
        ready();result(List.of());
        String source="Existing source' OR TRUE --";
        repo().search(new LeadSearch(0,20,null,null,null,null,source),null);
        assertThat(submitted.getQuery()).contains("source = @source").doesNotContain(source);
        assertThat(submitted.getNamedParameters().get("source").getValue()).isEqualTo(source);
        assertThatThrownBy(()->new LeadSearch(0,20,null,null,null,null,"x".repeat(121))).isInstanceOf(LeadApiException.class);
    }
    final BigQuery client=mock(BigQuery.class);
    final DatasetId datasetId=DatasetId.of("king-of-cards-509610","shared_rds_data");
    final TableId tableId=TableId.of("king-of-cards-509610","shared_rds_data","crm_leads");
    final UUID id=UUID.randomUUID(), vendor=UUID.randomUUID(), admin=UUID.randomUUID();
    final Instant now=Instant.parse("2026-10-29T00:00:00Z");
    QueryJobConfiguration submitted;
    BigQueryProperties props(boolean enabled,boolean writes) { return new BigQueryProperties(enabled,datasetId.getProject(),datasetId.getDataset(),"asia-south1","crm_leads",false,writes,false); }
    BigQueryLeadRepository repo() { return new BigQueryLeadRepository(()->client,props(true,true)); }
    void ready() {
        Dataset dataset=mock(Dataset.class); when(dataset.getLocation()).thenReturn("asia-south1");
        when(client.getDataset(datasetId)).thenReturn(dataset);
        Table table=mock(Table.class); when(table.getDefinition()).thenReturn(StandardTableDefinition.of(CrmLeadSchema.definition()));
        when(client.getTable(tableId)).thenReturn(table);
    }
    TableResult result(List<FieldValueList> rows) throws Exception {
        TableResult result=mock(TableResult.class); when(result.iterateAll()).thenReturn(rows);
        when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenAnswer(call->{submitted=call.getArgument(0);return result;}); return result;
    }
    void insertReceipt(Long affected) {
        when(client.getJob(any(JobId.class))).thenAnswer(call->{
            var receipt=mock(Job.class);when(receipt.getJobId()).thenReturn(call.getArgument(0,JobId.class));
            when(receipt.getConfiguration()).thenReturn(submitted);
            var status=mock(JobStatus.class);when(status.getState()).thenReturn(JobStatus.State.DONE);when(receipt.getStatus()).thenReturn(status);
            var statistics=mock(JobStatistics.QueryStatistics.class);when(statistics.getNumDmlAffectedRows()).thenReturn(affected);
            when(receipt.getStatistics()).thenReturn(statistics);return receipt;
        });
    }
    FieldValueList row(UUID owner) {
        FieldValueList row=mock(FieldValueList.class);
        Map<String,String> values=Map.of("lead_id",id.toString(),"customer_name","Synthetic Lead","phone","+919876543210",
            "service","Photography","stage","CONTACTED","source","Referral","vendor_subject_id",owner.toString(),"created_by_subject_id",admin.toString());
        values.forEach((key,value)-> { FieldValue field=mock(FieldValue.class); when(field.getStringValue()).thenReturn(value); when(row.get(key)).thenReturn(field); });
        for(String key:List.of("created_at","updated_at")) { FieldValue field=mock(FieldValue.class); when(field.getTimestampInstant()).thenReturn(now); when(row.get(key)).thenReturn(field); }
        return row;
    }
    @Test void parameterizesAllFiltersAndForcesOwnership() throws Exception {
        ready(); result(List.of(row(vendor)));
        var rows=repo().search(new LeadSearch(2,10,"O'Reilly 987","Photography",LeadStage.INTERESTED,vendor),vendor);
        assertThat(rows).hasSize(1);
        var query=ArgumentCaptor.forClass(QueryJobConfiguration.class); var job=ArgumentCaptor.forClass(JobId.class);
        verify(client).query(query.capture(),job.capture());
        var config=query.getValue();
        assertThat(config.getQuery()).contains("vendor_subject_id = @owner","vendor_subject_id = @vendor","LOWER(@q)","service = @service","stage = @stage","LIMIT @limit OFFSET @offset")
            .doesNotContain("O'Reilly","Photography",vendor.toString());
        assertThat(config.getNamedParameters().get("q").getValue()).isEqualTo("O'Reilly 987");
        assertThat(config.getNamedParameters().get("phone_search").getValue()).isEqualTo("987");
        assertThat(config.getNamedParameters().get("limit").getValue()).isEqualTo("11");
        assertThat(config.getNamedParameters().get("offset").getValue()).isEqualTo("20");
        assertThat(config.getMaximumBytesBilled()).isEqualTo(100_000_000L);
        assertThat(config.getCreateDisposition()).isEqualTo(JobInfo.CreateDisposition.CREATE_NEVER);
        assertThat(job.getValue().getLocation()).isEqualTo("asia-south1");
    }
    @Test void detailQueryScopesOwnerAndDetectsDuplicateIds() throws Exception {
        ready(); result(List.of(row(vendor),row(vendor)));
        assertThatThrownBy(()->repo().find(id,vendor)).isInstanceOfSatisfying(LeadApiException.class,e->assertThat(e.code()).isEqualTo("LEAD_ID_NOT_UNIQUE"));
        var query=ArgumentCaptor.forClass(QueryJobConfiguration.class); verify(client).query(query.capture(),any(JobId.class));
        assertThat(query.getValue().getQuery()).contains("lead_id = @id AND vendor_subject_id = @owner LIMIT 2");
    }
    @Test void detailAbsentReturnsEmpty() throws Exception { ready(); result(List.of()); assertThat(repo().find(id,vendor)).isEmpty(); }
    @Test void rejectsRowsOutsideOwnerScope() throws Exception {
        ready(); result(List.of(row(UUID.randomUUID())));
        assertThatThrownBy(()->repo().search(new LeadSearch(0,20,null,null,null,null),vendor)).isInstanceOf(LeadApiException.class);
    }
    @Test void insertsTenExplicitParameterizedValues() throws Exception {
        ready(); result(List.of());insertReceipt(1L);
        repo().insert(new CrmLeadRecord(id,"Synthetic Lead","+919876543210","Photography",LeadStage.CONTACTED,"Referral",vendor,admin,now,now));
        var query=ArgumentCaptor.forClass(QueryJobConfiguration.class); verify(client).query(query.capture(),any(JobId.class));
        assertThat(query.getValue().getQuery()).startsWith("INSERT INTO `king-of-cards-509610.shared_rds_data.crm_leads`")
            .doesNotContain("Synthetic Lead","+919876543210");
        assertThat(query.getValue().getNamedParameters()).hasSize(10);
        assertThat(query.getValue().getNamedParameters().get("vendor").getValue()).isEqualTo(vendor.toString());
        verify(client).getDataset(datasetId); verify(client).getTable(tableId);verify(client).getJob(any(JobId.class)); verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @NullSource @ValueSource(longs={0,2})
    void insertRequiresExactlyOneConfirmedAffectedRow(Long affected) throws Exception {
        ready();result(List.of());insertReceipt(affected);
        assertThatThrownBy(()->repo().insert(new CrmLeadRecord(id,"Name","+12025550123","Service",LeadStage.CONTACTED,
            "Referral",vendor,admin,now,now))).isInstanceOf(LeadApiException.class);
        verify(client,times(1)).query(any(QueryJobConfiguration.class),any(JobId.class));
    }
    @Test void listRejectsDuplicateStoredIds() throws Exception {
        ready();result(List.of(row(vendor),row(vendor)));
        assertThatThrownBy(()->repo().search(new LeadSearch(0,20,null,null,null,null),vendor)).isInstanceOf(LeadApiException.class);
    }
    @Test void noncanonicalStoredUuidFailsClosed() throws Exception {
        ready();var row=row(vendor);var value=mock(FieldValue.class);when(value.getStringValue()).thenReturn("1-1-1-1-1");
        when(row.get("lead_id")).thenReturn(value);result(List.of(row));
        assertThatThrownBy(()->repo().find(UUID.fromString("1-1-1-1-1"),vendor)).isInstanceOf(LeadApiException.class);
    }
    @Test void missingCommittedReceiptDoesNotCauseAnInsertRetry() throws Exception {
        ready();result(List.of());
        assertThatThrownBy(()->repo().insert(new CrmLeadRecord(id,"Name","+12025550123","Service",LeadStage.CONTACTED,
            "Referral",vendor,admin,now,now))).isInstanceOf(LeadApiException.class);
        verify(client,times(1)).query(any(QueryJobConfiguration.class),any(JobId.class));verify(client).getJob(any(JobId.class));
    }
    @Test void searchRejectsMoreRowsThanItsBoundedQueryRequested() throws Exception {
        ready();var a=row(vendor);var b=row(vendor);var c=row(vendor);
        for(var value:List.of(a,b,c)){var field=mock(FieldValue.class);when(field.getStringValue()).thenReturn(UUID.randomUUID().toString());when(value.get("lead_id")).thenReturn(field);}
        result(List.of(a,b,c));
        assertThatThrownBy(()->repo().search(new LeadSearch(0,1,null,null,null,null),vendor)).isInstanceOf(LeadApiException.class);
    }
    @Test void disabledMakesNoGoogleCalls() {
        var repository=new BigQueryLeadRepository(()->{throw new AssertionError("Client must not resolve");},props(false,false));
        assertThatThrownBy(()->repository.find(id,vendor)).isInstanceOf(LeadApiException.class);
        assertThatThrownBy(repository::assertWritesEnabled).isInstanceOf(LeadApiException.class);
        verifyNoInteractions(client);
    }
    @Test void disabledWritesMakeNoGoogleCalls() {
        var repository=new BigQueryLeadRepository(()->client,props(true,false));
        assertThatThrownBy(()->repository.insert(new CrmLeadRecord(id,"Name","+919876543210","Photography",LeadStage.CONTACTED,"Referral",vendor,admin,now,now)))
            .isInstanceOf(LeadApiException.class); verifyNoInteractions(client);
    }
    @Test void missingClientFailsSafely() { assertThatThrownBy(()->new BigQueryLeadRepository(()->null,props(true,false)).find(id,null)).isInstanceOf(LeadApiException.class); }
    @Test void incompatibleSchemaStopsBeforeQuery() {
        ready(); Table table=mock(Table.class); when(table.getDefinition()).thenReturn(StandardTableDefinition.of(Schema.of(Field.of("legacy",StandardSQLTypeName.STRING))));
        when(client.getTable(tableId)).thenReturn(table);
        assertThatThrownBy(()->repo().find(id,null)).isInstanceOfSatisfying(LeadApiException.class,e->assertThat(e.code()).isEqualTo("BIGQUERY_SCHEMA_MISMATCH"));
        verify(client).getDataset(datasetId); verify(client).getTable(tableId); verifyNoMoreInteractions(client);
    }
    @Test void wrongLocationStopsBeforeTableAccess() {
        Dataset dataset=mock(Dataset.class); when(dataset.getLocation()).thenReturn("US"); when(client.getDataset(datasetId)).thenReturn(dataset);
        assertThatThrownBy(()->repo().find(id,null)).isInstanceOf(LeadApiException.class);
        verify(client).getDataset(datasetId); verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @ValueSource(ints={401,403,503})
    void queryFailuresDoNotLeakProviderDetails(int code) throws Exception {
        ready(); when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenThrow(new BigQueryException(code,"synthetic-sensitive-provider-detail"));
        assertThatThrownBy(()->repo().find(id,null)).isInstanceOf(LeadApiException.class).hasMessageNotContaining("synthetic-sensitive").hasNoCause();
    }
    @Test void interruptionRestoresFlag() throws Exception {
        ready(); when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenThrow(new InterruptedException());
        try { assertThatThrownBy(()->repo().find(id,null)).isInstanceOf(LeadApiException.class); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }
}
