package com.kingofcards.crm.directional;
import java.time.*;
import java.util.*;
import com.kingofcards.crm.config.BigQueryProperties;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.model.LeadStage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
class DirectionalSqlTest {
    @ParameterizedTest @EnumSource(LeadDirection.class) void transactionIsParameterizedScopedAndContainsNoPermanentDdl(LeadDirection direction){
        var repository=new BigQueryDirectionalRepository(()->{throw new AssertionError("No cloud");},new BigQueryProperties(false,"king-of-cards-509610","shared_rds_data","asia-south1","crm_leads",false,false,false),new DirectionalProperties(true,false,100,200),Clock.systemUTC());
        UUID vendor=UUID.randomUUID(),actor=UUID.randomUUID(),operation=UUID.randomUUID();
        var draft=new Draft(direction.normalize(Map.of("Name","SQL ' ; -- text","Phone","00123456789")),new Metadata("Service",LeadStage.CONTACTED,"Existing source"));
        var query=repository.plan(vendor,direction,actor,operation,Instant.now(),null,0,List.of(draft),true,"Verified vendor");
        assertThat(query.getQuery()).contains("BEGIN TRANSACTION","COMMIT TRANSACTION","@@row_count","m.vendor_subject_id=@vendor","m.lead_direction=@direction","m.revision!=@expected","exact_matches","vendor_name=i.vendor_name","m.vendor_name IS DISTINCT FROM d.vendor_name").doesNotContain("SQL ' ; -- text","ALTER TABLE","DROP TABLE","TRUNCATE","CREATE TABLE");
        assertThat(query.getQuery()).contains("INSERT INTO `king-of-cards-509610.shared_rds_data.crm_leads` (lead_id,customer_name,phone,service,stage,source,vendor_subject_id,created_by_subject_id,updated_by_subject_id,created_at,updated_at,lead_direction,import_batch_id,revision,vendor_name)");
        String extras=direction==LeadDirection.KOC_TO_VENDOR?",sheet_date,sheet_time_supplied,sheet_source_value":"";
        String detailColumns=String.join(",",direction.detailColumns().stream().map(LeadDirection.Column::column).toList())+extras+",vendor_name";
        String detailValues=String.join(",",direction.detailColumns().stream().map(c->"i."+c.column()).toList())+extras.replace(",",",i.")+",i.vendor_name";
        assertThat(query.getQuery()).contains("INSERT INTO `king-of-cards-509610.shared_rds_data."+direction.table()+"` (lead_id,"+detailColumns+") SELECT i.lead_id,"+detailValues);
        assertThat(query.getNamedParameters().get("customer_name_0").getValue()).isEqualTo("SQL ' ; -- text");
        assertThat(query.getNamedParameters().get("phone_0").getValue()).isEqualTo("00123456789");
        assertThat(query.getNamedParameters().get("vendor_name_0").getValue()).isEqualTo("Verified vendor");
        assertThat(query.getMaximumBytesBilled()).isEqualTo(536870912L);assertThat(query.useLegacySql()).isFalse();assertThat(query.getCreateDisposition()).isNull();
        assertThat(query.getQuery()).contains(direction.table()).doesNotContain(direction==LeadDirection.KOC_TO_VENDOR?"crm_vendor_to_koc_details":"crm_koc_to_vendor_details");
    }
    @ParameterizedTest @EnumSource(LeadDirection.class) void previewDuplicateQueryMatchesTheTransactionPredicateAndIsReadOnly(LeadDirection direction){
        var repository=new BigQueryDirectionalRepository(()->{throw new AssertionError("No cloud");},new BigQueryProperties(false,"king-of-cards-509610","shared_rds_data","asia-south1","crm_leads",false,false,false),new DirectionalProperties(true,false,100,200),Clock.systemUTC());
        UUID vendor=UUID.randomUUID();var fields=new LinkedHashMap<String,String>();for(var column:direction.columns())fields.put(column.header(),null);fields.put("Name","Matching customer");fields.put("Phone","00123456789");
        var draft=new Draft(direction.normalize(fields),new Metadata("photography",LeadStage.NEW,direction.name()));var preview=repository.exactMatchQuery(vendor,direction,List.of(draft),0,1).build();
        var transaction=repository.plan(vendor,direction,UUID.randomUUID(),UUID.randomUUID(),Instant.now(),null,0,List.of(draft),true,"Verified vendor");
        assertThat(preview.getQuery()).contains("m.vendor_subject_id=@vendor","m.lead_direction=@direction","m.customer_name IS NOT DISTINCT FROM i.customer_name","m.phone IS NOT DISTINCT FROM i.phone","m.service=i.service","m.stage=i.stage","m.source=i.source").doesNotContain("INSERT","UPDATE","DELETE","BEGIN TRANSACTION","COMMIT TRANSACTION");
        assertThat(preview.getQuery()).contains(String.join(" AND ",direction.detailColumns().stream().map(c->"d."+c.column()+" IS NOT DISTINCT FROM i."+c.column()).toList()));
        assertThat(preview.getQuery()).contains("`king-of-cards-509610.shared_rds_data.crm_leads`","`king-of-cards-509610.shared_rds_data."+direction.table()+"`");
        assertThat(preview.getNamedParameters().get("vendor")).isEqualTo(transaction.getNamedParameters().get("vendor"));assertThat(preview.getNamedParameters().get("direction")).isEqualTo(transaction.getNamedParameters().get("direction"));
        if(direction==LeadDirection.KOC_TO_VENDOR){assertThat(preview.getNamedParameters().get("sheet_timestamp_0").getValue()).isNull();assertThat(preview.getQuery()).contains("d.sheet_timestamp IS NOT DISTINCT FROM i.sheet_timestamp");}
    }
}
