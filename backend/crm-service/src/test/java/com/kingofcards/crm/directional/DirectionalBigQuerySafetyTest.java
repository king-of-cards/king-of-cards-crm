package com.kingofcards.crm.directional;
import java.time.*;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.bigquery.CrmLeadSchema;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.LeadStage;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectionalBigQuerySafetyTest {
    @Test void aggregateRowsPreserveUnknownStagesAndDoNotRequireDetailRows() throws Exception {
        metadata();var row=mock(FieldValueList.class);
        var direction=mock(FieldValue.class);when(direction.getStringValue()).thenReturn("KOC_TO_VENDOR");when(row.get("lead_direction")).thenReturn(direction);
        for(var value:Map.of("total",5L,"new_leads",1L,"contacted",1L,"interested",1L,"converted",1L).entrySet()){
            var field=mock(FieldValue.class);when(field.getLongValue()).thenReturn(value.getValue());when(row.get(value.getKey())).thenReturn(field);
        }
        var result=mock(TableResult.class);when(result.iterateAll()).thenReturn(List.of(row));when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenReturn(result);
        var report=repo(false).report(vendor);assertThat(report.get(LeadDirection.KOC_TO_VENDOR)).isEqualTo(new StageCounts(5,1,1,1,1,1));assertThat(report.get(LeadDirection.VENDOR_TO_KOC)).isEqualTo(StageCounts.empty());
        assertThat(report.get(LeadDirection.KOC_TO_VENDOR).conversionRatePercent()).isEqualByComparingTo("20.00");
    }
    @Test void reportIsParameterizedMasterOnlySelectAndSafeWithWritesDisabled() throws Exception {
        metadata();var result=mock(TableResult.class);when(result.iterateAll()).thenReturn(List.of());when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenReturn(result);
        assertThat(repo(false).report(vendor).values()).containsOnly(StageCounts.empty());
        var capture=org.mockito.ArgumentCaptor.forClass(QueryJobConfiguration.class);verify(client).query(capture.capture(),any(JobId.class));var query=capture.getValue();
        assertThat(query.getQuery()).startsWith("SELECT ").contains("vendor_subject_id=@vendor","lead_direction IN UNNEST(@directions)","COUNTIF(stage=@interested_stage)").doesNotContain("JOIN","INSERT","UPDATE","DELETE","MERGE","CREATE","crm_koc_to_vendor_details","crm_vendor_to_koc_details",vendor.toString());
        assertThat(query.getNamedParameters().get("vendor").getValue()).isEqualTo(vendor.toString());assertThat(query.getNamedParameters().get("converted_stage").getValue()).isEqualTo("CONVERTED");
        assertThat(query.getMaximumBytesBilled()).isEqualTo(100000000L);assertThat(query.useLegacySql()).isFalse();verify(client,never()).create(any(JobInfo.class));
    }
    @Test void reportBigQueryFailureIsSanitized() throws Exception {
        metadata();when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenThrow(new BigQueryException(403,"private provider message"));
        assertThatThrownBy(()->repo(false).report(vendor)).isInstanceOf(LeadApiException.class).hasMessageNotContaining("private provider message");verify(client,never()).create(any(JobInfo.class));
    }
    final BigQuery client=mock(BigQuery.class);
    final BigQueryProperties properties=new BigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1","crm_leads",false,true,false);
    final UUID vendor=UUID.randomUUID(),actor=UUID.randomUUID(),op=UUID.randomUUID();
    void metadata(){
        var dataset=mock(Dataset.class);when(dataset.getLocation()).thenReturn("asia-south1");when(client.getDataset(DatasetId.of(properties.projectId(),properties.dataset()))).thenReturn(dataset);
        var fields=new ArrayList<Field>();CrmLeadSchema.definition().getFields().forEach(fields::add);
        for(String name:List.of("lead_direction","import_batch_id","updated_by_subject_id"))fields.add(Field.of(name,StandardSQLTypeName.STRING));fields.add(Field.of("revision",StandardSQLTypeName.INT64));
        var master=mock(Table.class);when(master.getDefinition()).thenReturn(StandardTableDefinition.of(Schema.of(fields)));when(client.getTable(TableId.of(properties.projectId(),properties.dataset(),"crm_leads"))).thenReturn(master);
        for(var direction:LeadDirection.values()){var detail=mock(Table.class);when(detail.getDefinition()).thenReturn(StandardTableDefinition.of(DirectionalSchema.detail(direction)));when(client.getTable(TableId.of(properties.projectId(),properties.dataset(),direction.table()))).thenReturn(detail);}
    }
    BigQueryDirectionalRepository repo(boolean writes){return new BigQueryDirectionalRepository(()->client,properties,new DirectionalProperties(true,writes,100,200),Clock.systemUTC());}
    Draft draft(){return new Draft(LeadDirection.KOC_TO_VENDOR.normalize(Map.of("Name","Offline","Phone","00123456789")),new Metadata("Service",LeadStage.CONTACTED,"Existing source"));}
    @Test void schemasIncludeServerManagedVendorNameWithoutAddingItToSheetFields(){for(var direction:LeadDirection.values()){assertThat(DirectionalSchema.detail(direction).getFields()).hasSize(direction==LeadDirection.KOC_TO_VENDOR?15:12);assertThat(DirectionalSchema.detail(direction).getFields().stream().map(Field::getName)).doesNotContain("customer_name","phone").contains("vendor_name");assertThat(direction.headers()).doesNotContain("vendor_name");}assertThat(DirectionalSchema.detail(LeadDirection.KOC_TO_VENDOR).getFields().get("quoted_amount").getType().getStandardType()).isEqualTo(StandardSQLTypeName.NUMERIC);}
    @Test void missingSchemaOrWrongLocationNeverCreatesAnything(){metadata();when(client.getTable(TableId.of(properties.projectId(),properties.dataset(),"crm_koc_to_vendor_details"))).thenReturn(null);assertThatThrownBy(()->DirectionalSchema.validate(client,properties)).isInstanceOf(LeadApiException.class).hasMessageContaining("missing");verify(client,never()).create(any(TableInfo.class));}
    @Test void wrongDatasetLocationIsRejected(){metadata();var dataset=mock(Dataset.class);when(dataset.getLocation()).thenReturn("US");when(client.getDataset(DatasetId.of(properties.projectId(),properties.dataset()))).thenReturn(dataset);assertThatThrownBy(()->DirectionalSchema.validate(client,properties)).isInstanceOf(LeadApiException.class).hasMessageContaining("location");}
    @Test void writesDisabledDoesNotEvenReadMetadata(){assertThatThrownBy(()->repo(false).write(vendor,LeadDirection.KOC_TO_VENDOR,actor,op,Instant.now(),null,0,List.of(draft()),false,"Verified Vendor")).isInstanceOf(LeadApiException.class);verifyNoInteractions(client);}
    @Test void ambiguousSubmissionLooksUpSameJobAndNeverSubmitsNewId(){metadata();when(client.create(any(JobInfo.class))).thenThrow(new BigQueryException(503,"Synthetic failure"));
        assertThatThrownBy(()->repo(true).write(vendor,LeadDirection.KOC_TO_VENDOR,actor,op,Instant.now(),null,0,List.of(draft()),false,"Verified Vendor")).isInstanceOf(LeadApiException.class).satisfies(error->assertThat(((LeadApiException)error).code()).isEqualTo("DIRECTIONAL_COMMIT_UNCERTAIN"));
        var job=org.mockito.ArgumentCaptor.forClass(JobId.class);verify(client,times(2)).getJob(job.capture());assertThat(job.getAllValues().get(0)).isEqualTo(job.getAllValues().get(1));verify(client,times(1)).create(any(JobInfo.class));
    }
    @Test void metadataPermissionFailureDoesNotReachMutationSubmission(){when(client.getDataset(any(DatasetId.class))).thenThrow(new BigQueryException(403,"Synthetic permission error"));assertThatThrownBy(()->repo(true).write(vendor,LeadDirection.KOC_TO_VENDOR,actor,op,Instant.now(),null,0,List.of(draft()),false,"Verified Vendor")).isInstanceOf(LeadApiException.class);verify(client,never()).create(any(JobInfo.class));}
}
