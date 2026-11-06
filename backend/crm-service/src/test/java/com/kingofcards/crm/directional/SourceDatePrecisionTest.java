package com.kingofcards.crm.directional;

import java.time.*;
import java.util.*;
import java.nio.file.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.LeadStage;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceDatePrecisionTest {
    final DirectionalWorkflowTest fixture=new DirectionalWorkflowTest();
    final LeadDirection direction=LeadDirection.KOC_TO_VENDOR;
    Draft draft(String timestamp,SourceDateTime source){
        var fields=new HashMap<String,String>();fields.put("Name","Synthetic source precision");fields.put("Phone","00123456789");fields.put("Timestamp",timestamp);
        return new Draft(direction.normalize(fields),new Metadata("photography",LeadStage.NEW,direction.name()),source);
    }
    SourceDateTime date(String date){return new SourceDateTime(LocalDate.parse(date),false,date);}
    @Test void explicitDateOnlyEditAndExplicitClearDoNotGetOverwrittenByHistoricalPrecision(){
        var original=draft(null,date("2026-10-13"));var id=fixture.create(direction,original).rows().getFirst().leadId();
        var changed=new Draft(original.fields(),original.metadata(),date("2026-10-14"));
        fixture.service.write(fixture.vendor,direction,id,new Command(UUID.randomUUID(),fixture.now,1,changed),fixture.auth(fixture.admin,"CRM_ADMIN"));
        var lead=fixture.repo.get(fixture.vendor,direction,id).orElseThrow();assertThat(lead.sourceDateTime().date()).isEqualTo(LocalDate.of(2026,10,14));assertThat(lead.fields().get("Timestamp")).isNull();
        fixture.service.write(fixture.vendor,direction,id,new Command(UUID.randomUUID(),fixture.now,2,new Draft(lead.fields(),lead.metadata(),null,true)),fixture.auth(fixture.admin,"CRM_ADMIN"));
        assertThat(fixture.repo.get(fixture.vendor,direction,id).orElseThrow().sourceDateTime()).isNull();
    }
    @Test void clearingCannotHideAnActualTimestamp(){
        var value=draft("2026-10-07T07:11:17Z",SourceDateTime.fromTimestamp("2026-10-07T07:11:17Z"));
        assertThatThrownBy(()->fixture.create(direction,new Draft(value.fields(),value.metadata(),null,true))).isInstanceOf(LeadApiException.class).hasMessageContaining("Clearing source precision");
    }
    @Test void dateOnlyRoundTripsWithoutTimestampAndSurvivesUnrelatedEditsAndExports(){
        var original=draft(null,date("2026-10-13"));var receipt=fixture.create(direction,original);var id=receipt.rows().getFirst().leadId();
        var lead=fixture.repo.get(fixture.vendor,direction,id).orElseThrow();assertThat(lead.fields().get("Timestamp")).isNull();assertThat(lead.sourceDateTime()).isEqualTo(original.sourceDateTime());
        var edited=new HashMap<>(lead.fields());edited.put("Branch","Changed synthetic branch");
        fixture.service.write(fixture.vendor,direction,id,new Command(UUID.randomUUID(),fixture.now,1,new Draft(edited,lead.metadata())),fixture.auth(fixture.admin,"CRM_ADMIN"));
        lead=fixture.repo.get(fixture.vendor,direction,id).orElseThrow();assertThat(lead.sourceDateTime()).isEqualTo(original.sourceDateTime());
        var export=SheetFiles.export(direction,List.of(lead),"csv");var preview=fixture.service.preview(fixture.vendor,direction,new MockMultipartFile("file","export.csv","text/csv",export),lead.metadata(),true,fixture.auth(fixture.admin,"CRM_ADMIN"));
        assertThat(preview.rows().getFirst().classification()).isEqualTo("EXISTING_IN_BIGQUERY");assertThat(preview.rows().getFirst().fields().get("Timestamp")).isNull();
    }
    @Test void dateOnlyAndActualMidnightAreDifferentBusinessValuesAndDatesStayDistinct(){
        var first=draft(null,date("2026-10-13"));fixture.create(direction,first);
        assertThat(fixture.repo.exactExistingRows(fixture.vendor,direction,List.of(first,draft(null,date("2026-10-14")),draft("2026-10-12T18:30:00Z",new SourceDateTime(LocalDate.of(2026,10,13),true,"13-10-2026 00:00"))))).containsExactly(0);
    }
    @Test void provenanceIsFrozenForIdempotencyButFormattingDoesNotCreateBusinessDuplicates(){
        var instant="2026-10-07T07:11:17Z";var a=draft(instant,new SourceDateTime(LocalDate.of(2026,10,7),true,"10-7-2026 12:41:17 PM"));var b=draft(instant,new SourceDateTime(LocalDate.of(2026,10,7),true,"2026-10-07T12:41:17+05:30"));
        assertThat(DirectionalKeys.canonical(a)).isEqualTo(DirectionalKeys.canonical(b));assertThat(DirectionalKeys.fingerprint(List.of(a))).isNotEqualTo(DirectionalKeys.fingerprint(List.of(b)));
        UUID op=UUID.randomUUID();var receipt=fixture.repo.write(fixture.vendor,direction,fixture.admin,op,fixture.now,null,0,List.of(a),true,"Verified vendor");
        assertThat(fixture.repo.write(fixture.vendor,direction,fixture.admin,op,fixture.now,null,0,List.of(a),true,"Verified vendor")).isEqualTo(receipt);
        assertThatThrownBy(()->fixture.repo.write(fixture.vendor,direction,fixture.admin,op,fixture.now,null,0,List.of(b),true,"Verified vendor")).isInstanceOf(LeadApiException.class);
    }
    @Test void inconsistentPrecisionOrVendorDirectionCannotBeSupplied(){
        assertThatThrownBy(()->date("2026-10-13").validate(direction,"2026-10-13T00:00:00Z")).isInstanceOf(LeadApiException.class);
        assertThatThrownBy(()->new SourceDateTime(LocalDate.of(2026,10,14),true,"source").validate(direction,"2026-10-13T00:00:00Z")).isInstanceOf(LeadApiException.class);
        assertThatThrownBy(()->date("2026-10-13").validate(LeadDirection.VENDOR_TO_KOC,null)).isInstanceOf(LeadApiException.class);
    }
    @Test void typedParametersAndPreviewPredicatesKeepPrecisionAndHistoricalTimestampCompatibility(){
        var fixture=new DirectionalBigQuerySafetyTest();var repo=fixture.repo(false);var value=draft(null,date("2026-10-13"));
        var query=repo.plan(fixture.vendor,direction,fixture.actor,fixture.op,Instant.now(),null,0,List.of(value),true,"Verified vendor");var p=query.getNamedParameters();
        assertThat(p.get("sheet_timestamp_0").getValue()).isNull();assertThat(p.get("sheet_date_0").getType()).isEqualTo(StandardSQLTypeName.DATE);assertThat(p.get("sheet_date_0").getValue()).isEqualTo("2026-10-13");assertThat(p.get("sheet_time_supplied_0").getValue()).isEqualTo("false");assertThat(p.get("sheet_source_value_0").getValue()).isEqualTo("2026-10-13");
        var preview=repo.exactMatchQuery(fixture.vendor,direction,List.of(value),0,1).build();
        String precision="COALESCE(d.sheet_date,DATE(d.sheet_timestamp,'Asia/Kolkata')) IS NOT DISTINCT FROM i.sheet_date";
        assertThat(preview.getQuery()).contains(precision).doesNotContain("INSERT","UPDATE","DELETE");assertThat(query.getQuery()).contains(precision,"sheet_date=i.sheet_date","sheet_time_supplied=i.sheet_time_supplied","sheet_source_value=i.sheet_source_value");
    }
    @Test void oldAndNewImmutableJobsCanBothBeReconciledWithoutSubmittingAnotherJob() throws Exception {
        for(boolean precision:List.of(false,true)){
            var f=new DirectionalBigQuerySafetyTest();f.metadata();var repo=f.repo(false);var value=precision?draft(null,date("2026-10-13")):draft("2026-10-07T07:11:17Z",null);var submitted=Instant.parse("2026-11-05T00:00:00Z");
            var query=repo.plan(f.vendor,direction,f.actor,f.op,submitted,null,0,List.of(value),true,"Verified vendor",precision);
            if(!precision)assertThat(query.getQuery()).doesNotContain("sheet_date","sheet_time_supplied","sheet_source_value");
            var id=JobId.newBuilder().setProject(f.properties.projectId()).setLocation(f.properties.location()).setJob("koc_direction_v1_"+f.actor.toString().replace("-","")+"_"+f.op.toString().replace("-","")).build();
            var job=mock(Job.class);var status=mock(JobStatus.class);when(status.getState()).thenReturn(JobStatus.State.DONE);when(job.getStatus()).thenReturn(status);when(job.getJobId()).thenReturn(id);when(job.getConfiguration()).thenReturn(query);when(f.client.getJob(id)).thenReturn(job);
            var row=mock(FieldValueList.class);when(row.get("row_index")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"1"));when(row.get("status")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"IMPORTED"));when(row.get("lead_id")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,query.getNamedParameters().get("id_0").getValue()));when(row.get("error")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,null));
            var result=mock(TableResult.class);when(result.iterateAll()).thenReturn(List.of(row));when(job.getQueryResults()).thenReturn(result);
            assertThat(repo.operation(f.vendor,direction,f.actor,f.op).state()).isEqualTo("COMMITTED");verify(f.client,never()).create(any(JobInfo.class));verify(f.client,never()).query(any(QueryJobConfiguration.class),any(JobId.class));
        }
    }
    @Test void bigQueryReadPreservesDateOnlyAndHistoricalTimestampRows() throws Exception {
        for(boolean legacy:List.of(false,true)){
            var f=new DirectionalBigQuerySafetyTest();f.metadata();UUID id=UUID.randomUUID();var values=new HashMap<String,String>();
            values.put("lead_id",id.toString());values.put("detail_id",id.toString());values.put("vendor_subject_id",f.vendor.toString());values.put("lead_direction",direction.name());
            values.put("master_vendor_name","Verified vendor");values.put("detail_vendor_name","Verified vendor");values.put("service","photography");values.put("stage","NEW");values.put("source",direction.name());values.put("revision","1");values.put("customer_name","Synthetic");values.put("phone","00123456789");
            values.put("created_by_subject_id",f.actor.toString());values.put("updated_by_subject_id",f.actor.toString());values.put("created_at","1");values.put("updated_at","1");
            if(legacy)values.put("sheet_timestamp","1");else {values.put("sheet_date","2026-10-13");values.put("sheet_time_supplied","false");values.put("sheet_source_value","13-10-2026");}
            var row=mock(FieldValueList.class);when(row.get(anyString())).thenAnswer(call->FieldValue.of(FieldValue.Attribute.PRIMITIVE,values.get(call.getArgument(0))));
            var result=mock(TableResult.class);when(result.iterateAll()).thenReturn(List.of(row));when(f.client.query(any(QueryJobConfiguration.class),any(JobId.class))).thenReturn(result);
            var lead=f.repo(false).get(f.vendor,direction,id).orElseThrow();
            if(legacy){assertThat(lead.fields().get("Timestamp")).isNotNull();assertThat(lead.sourceDateTime()).isNull();}
            else {assertThat(lead.fields().get("Timestamp")).isNull();assertThat(lead.sourceDateTime().date()).isEqualTo(LocalDate.of(2026,10,13));assertThat(lead.sourceDateTime().timeSupplied()).isFalse();}
            verify(f.client,never()).create(any(JobInfo.class));
        }
    }
    @Test void missingAdditiveColumnsFailsClosedWithoutSchemaOrDataChanges() throws Exception {
        var f=new DirectionalBigQuerySafetyTest();f.metadata();var table=mock(Table.class);
        var fields=DirectionalSchema.detail(direction).getFields().stream().filter(field->!field.getName().equals("sheet_date")).toList();
        when(table.getDefinition()).thenReturn(StandardTableDefinition.of(Schema.of(fields)));when(f.client.getTable(TableId.of(f.properties.projectId(),f.properties.dataset(),direction.table()))).thenReturn(table);
        assertThatThrownBy(()->f.repo(false).get(f.vendor,direction,UUID.randomUUID())).isInstanceOf(LeadApiException.class).hasMessageContaining("sheet_date");
        verify(f.client,never()).create(any(JobInfo.class));verify(f.client,never()).query(any(QueryJobConfiguration.class),any(JobId.class));
    }
    @Test void actualCsvPreviewReportsEveryRemainingInvalidRowWithoutCloudAccess() throws Exception {
        String source=System.getProperty("crm.confirmed-source.csv");Assumptions.assumeTrue(source!=null&&!source.isBlank());var path=Path.of(source);byte[] bytes=Files.readAllBytes(path);
        var offlineService=new DirectionalService(fixture.repo,(id,admin,token)->new Vendor(id,"Trusted offline vendor",true,List.of("CRM_VENDOR")),new DirectionalProperties(true,false,100,2000),fixture.clock);
        var preview=offlineService.preview(fixture.vendor,direction,new MockMultipartFile("file",path.getFileName().toString(),"text/csv",bytes),new Metadata("photography",null,null),false,fixture.auth(fixture.admin,"CRM_ADMIN"));
        long invalid=preview.rows().stream().filter(r->!r.errors().isEmpty()).count(),duplicates=preview.rows().stream().filter(r->r.duplicate()).count();
        assertThat(preview.rows()).hasSize(615);assertThat(invalid).isEqualTo(40);assertThat(preview.rows().stream().filter(r->r.sourceDateTime()!=null&&!r.sourceDateTime().timeSupplied()).count()).isEqualTo(465);
        assertThat(preview.rows().stream().filter(r->r.errors().stream().anyMatch(e->e.contains("Phone"))).map(PreviewRow::row)).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(145,177).boxed().toList());
        assertThat(preview.rows().stream().filter(r->r.errors().stream().anyMatch(e->e.contains("occasion_date"))).map(PreviewRow::row)).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(610,616).boxed().toList());
        System.out.println("OFFLINE_CONFIRMED_CSV: total=615 valid="+(615-invalid)+" invalid="+invalid+" file_duplicates="+duplicates+" date_only=465 timed=150 phone_errors=33 event_date_errors=7; no database lookup or write");
        System.out.println("OFFLINE_FILE_DUPLICATES="+preview.rows().stream().filter(PreviewRow::duplicate).map(r->r.row()+" matches "+r.duplicateOfRow()).toList());
        assertThat(Files.readAllBytes(path)).isEqualTo(bytes);
    }
}
