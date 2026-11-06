package com.kingofcards.crm.directional;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.config.BigQueryProperties;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DirectionalSchemaInitializerTest {
    final BigQuery client=mock(BigQuery.class);
    final BigQueryProperties settings=new BigQueryProperties(true,"king-of-cards-509610","shared_rds_data","asia-south1","crm_leads",false,false,false);
    final DirectionalSchemaInitializer initializer=new DirectionalSchemaInitializer(client,settings);
    @BeforeEach void dataset(){var dataset=mock(Dataset.class);when(dataset.getLocation()).thenReturn("asia-south1");when(client.getDataset(DatasetId.of(settings.projectId(),settings.dataset()))).thenReturn(dataset);}
    void table(String name,Schema schema){var table=mock(Table.class);when(table.getDefinition()).thenReturn(StandardTableDefinition.of(schema));when(client.getTable(TableId.of(settings.projectId(),settings.dataset(),name))).thenReturn(table);}
    @Test void missingTablesHaveOnlyThreeAllowlistedCreates(){var plan=initializer.plan();assertThat(plan).hasSize(3).allMatch(s->s.startsWith("CREATE TABLE IF NOT EXISTS `king-of-cards-509610.shared_rds_data.crm_"));assertThat(String.join(";",plan)).contains("quoted_amount NUMERIC","revision INT64","lead_id STRING NOT NULL","campaign_sent STRING").doesNotContain("DROP ","DELETE ","TRUNCATE ","INSERT ");}
    @Test void compatibleSchemasAreNoOp(){DirectionalSchemaInitializer.schemas().forEach(this::table);assertThat(initializer.plan()).isEmpty();}
    @Test void legacyMasterAddsOnlyFourNullableFields(){DirectionalSchemaInitializer.schemas().forEach(this::table);table("crm_leads",com.kingofcards.crm.bigquery.CrmLeadSchema.definition());assertThat(initializer.plan()).hasSize(4).allMatch(s->s.startsWith("ALTER TABLE `king-of-cards-509610.shared_rds_data.crm_leads` ADD COLUMN IF NOT EXISTS ")&&!s.contains("NOT NULL"));}
    @Test void partialDetailAddsNullableFieldsNotRequiredKey(){DirectionalSchemaInitializer.schemas().forEach(this::table);table("crm_vendor_to_koc_details",Schema.of(Field.newBuilder("lead_id",StandardSQLTypeName.STRING).setMode(Field.Mode.REQUIRED).build()));assertThat(initializer.plan()).hasSize(11).allMatch(s->s.startsWith("ALTER TABLE `king-of-cards-509610.shared_rds_data.crm_vendor_to_koc_details` ADD COLUMN IF NOT EXISTS "));}
    @Test void missingRequiredKeyBlocksAllDdl() throws Exception {table("crm_vendor_to_koc_details",Schema.of(Field.of("city",StandardSQLTypeName.STRING)));assertThatThrownBy(initializer::initialize).isInstanceOf(IllegalStateException.class).hasMessageContaining("missing required column lead_id");verify(client,never()).query(any(QueryJobConfiguration.class),any(JobId.class));}
    @Test void wrongTypeBlocksAllDdl() throws Exception {table("crm_vendor_to_koc_details",Schema.of(Field.of("lead_id",StandardSQLTypeName.INT64)));assertThatThrownBy(initializer::initialize).isInstanceOf(IllegalStateException.class).hasMessageContaining("type/mode mismatch");verify(client,never()).query(any(QueryJobConfiguration.class),any(JobId.class));}
    @Test void wrongLocationBlocksDdl(){var dataset=mock(Dataset.class);when(dataset.getLocation()).thenReturn("US");when(client.getDataset(DatasetId.of(settings.projectId(),settings.dataset()))).thenReturn(dataset);assertThatThrownBy(initializer::initialize).isInstanceOf(IllegalStateException.class).hasMessageContaining("location");}
    @Test void iamFailureIsSanitized(){when(client.getDataset(DatasetId.of(settings.projectId(),settings.dataset()))).thenThrow(new BigQueryException(403,"DO_NOT_EXPOSE_RAW_ERROR"));assertThatThrownBy(initializer::initialize).hasMessageContaining("HTTP 403","bigquery.jobs.create").hasMessageNotContaining("DO_NOT_EXPOSE_RAW_ERROR");}
    @Test void approvedCreationUsesBoundedRegionalJobsAndValidatesAfterwards() throws Exception {
        when(client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenAnswer(call->{
            var query=(QueryJobConfiguration)call.getArgument(0);var job=(JobId)call.getArgument(1);
            assertThat(query.getMaximumBytesBilled()).isEqualTo(10_000_000L);assertThat(query.getJobTimeoutMs()).isEqualTo(20_000L);assertThat(job.getLocation()).isEqualTo("asia-south1");
            for(var entry:DirectionalSchemaInitializer.schemas().entrySet())if(query.getQuery().startsWith("CREATE TABLE IF NOT EXISTS `king-of-cards-509610.shared_rds_data."+entry.getKey()+"`"))table(entry.getKey(),entry.getValue());
            return null;
        });
        initializer.initialize();verify(client,times(3)).query(any(QueryJobConfiguration.class),any(JobId.class));
        assertThat(initializer.plan()).isEmpty();
    }
    @Test void autoCreateCannotSelectDisabledOrTransientStorage(){
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);var env=mock(org.springframework.core.env.Environment.class);
        var disabled=new DirectionalProperties(false,false,100,200);
        var runner=new DirectionalConfig().directionalSchemaValidation(provider,settings,disabled,env,true);
        assertThatThrownBy(()->runner.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("explicitly enabled real BigQuery");verifyNoInteractions(provider);
    }
}
