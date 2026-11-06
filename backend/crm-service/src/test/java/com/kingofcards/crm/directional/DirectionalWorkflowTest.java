package com.kingofcards.crm.directional;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.model.LeadStage;
import com.kingofcards.crm.exception.LeadApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

class DirectionalWorkflowTest {
    final UUID vendor=UUID.randomUUID(),admin=UUID.randomUUID();
    final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    final LocalDirectionalRepository repo=new LocalDirectionalRepository(clock);
    final DirectionalService service=new DirectionalService(repo,(id,isAdmin,token)->new Vendor(id,"Test Vendor",true,List.of("CRM_VENDOR")),new DirectionalProperties(true,false,100,200),clock);
    JwtAuthenticationToken auth(UUID id,String role){return new JwtAuthenticationToken(Jwt.withTokenValue("synthetic-offline-only").header("alg","RS256").subject(id.toString()).build(),List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    Draft draft(LeadDirection direction,String name){return new Draft(direction.normalize(Map.of("Name",name,"Phone","001234567890")),new Metadata("Existing service",LeadStage.CONTACTED,"Existing source"));}
    Receipt create(LeadDirection direction,Draft draft){return service.write(vendor,direction,null,new Command(UUID.randomUUID(),now,0,draft),auth(direction==LeadDirection.KOC_TO_VENDOR?admin:vendor,direction==LeadDirection.KOC_TO_VENDOR?"CRM_ADMIN":"CRM_VENDOR"));}
    @ParameterizedTest @EnumSource(LeadDirection.class) void exactHeadersAndNullableAmounts(LeadDirection direction){
        assertThat(direction.headers()).hasSize(12).doesNotHaveDuplicates();var value=draft(direction,"Test");assertThat(value.fields().keySet()).containsExactlyElementsOf(direction.headers());assertThat(value.fields().get("Phone")).isEqualTo("001234567890");
        if(direction==LeadDirection.KOC_TO_VENDOR)assertThat(value.fields()).containsEntry("Quoted amount",null).containsEntry("occasion_date",null);
    }
    @Test void validatesMoneyDatesUnknownFieldsAndPhone(){
        var fields=new HashMap<>(draft(LeadDirection.KOC_TO_VENDOR,"Test").fields());fields.put("Quoted amount","12345678901234567890.123456789");assertThat(LeadDirection.KOC_TO_VENDOR.normalize(fields).get("Quoted amount")).isEqualTo(fields.get("Quoted amount"));
        for(String bad:List.of("-1","1e100000","1.1234567890x")){fields.put("Quoted amount",bad);assertThatThrownBy(()->LeadDirection.KOC_TO_VENDOR.normalize(fields)).isInstanceOf(LeadApiException.class);}
        fields.put("Quoted amount",null);fields.put("occasion_date","2026-02-30");assertThatThrownBy(()->LeadDirection.KOC_TO_VENDOR.normalize(fields)).isInstanceOf(LeadApiException.class);
        assertThatThrownBy(()->LeadDirection.KOC_TO_VENDOR.normalize(Map.of("Name","T","Phone","=1234567890"))).isInstanceOf(LeadApiException.class);
    }
    @ParameterizedTest @EnumSource(LeadDirection.class) void correctWriterAndBothReaders(LeadDirection direction){
        var receipt=create(direction,draft(direction,"Customer"));UUID id=receipt.rows().getFirst().leadId();
        assertThat(service.get(vendor,direction,id,auth(admin,"CRM_ADMIN")).fields()).containsEntry("Name","Customer");
        assertThat(service.get(vendor,direction,id,auth(admin,"CRM_ADMIN")).vendorName()).isEqualTo("Test Vendor");
        assertThat(service.get(vendor,direction,id,auth(vendor,"CRM_VENDOR")).vendorSubjectId()).isEqualTo(vendor);
        String forbidden=direction==LeadDirection.KOC_TO_VENDOR?"CRM_VENDOR":"CRM_ADMIN";UUID actor=forbidden.equals("CRM_ADMIN")?admin:vendor;
        assertThatThrownBy(()->service.write(vendor,direction,null,new Command(UUID.randomUUID(),now,0,draft(direction,"Other")),auth(actor,forbidden))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.preview(vendor,direction,null,null,false,auth(actor,forbidden))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.operation(vendor,direction,UUID.randomUUID(),auth(actor,forbidden))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.write(vendor,direction,id,new Command(UUID.randomUUID(),now,1,draft(direction,"Edit")),auth(actor,forbidden))).isInstanceOf(AccessDeniedException.class);
    }
    @ParameterizedTest @EnumSource(LeadDirection.class) void crossVendorCannotReadWriteImportOrExport(LeadDirection direction){
        var other=auth(UUID.randomUUID(),"CRM_VENDOR");var id=create(direction,draft(direction,"Customer")).rows().getFirst().leadId();
        assertThatThrownBy(()->service.get(vendor,direction,id,other)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.list(vendor,direction,new Search(0,20,null,null,null),other)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.export(vendor,direction,new Search(0,20,null,null,null),"csv",other)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.importFile(vendor,direction,null,null,false,UUID.randomUUID(),now,"x",other)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void unapprovedRolesAndMissingJwtRejected(){for(String role:List.of("CRM_MANAGER","CRM_AGENT","VENDOR"))assertThatThrownBy(()->service.schema(auth(admin,role))).isInstanceOf(AccessDeniedException.class);assertThatThrownBy(()->service.schema(null)).isInstanceOf(AccessDeniedException.class);}
    @ParameterizedTest @EnumSource(LeadDirection.class) void revisionAuditAndIdempotentRetry(LeadDirection direction){
        var draft=draft(direction,"Original");UUID op=UUID.randomUUID();var first=repo.write(vendor,direction,admin,op,now,null,0,List.of(draft),true,"Original trusted name");
        assertThat(repo.write(vendor,direction,admin,op,now,null,0,List.of(draft),true,"Changed trusted name")).isEqualTo(first);
        assertThat(repo.get(vendor,direction,first.rows().getFirst().leadId()).orElseThrow().vendorName()).isEqualTo("Original trusted name");
        assertThatThrownBy(()->repo.write(vendor,direction,admin,op,now,null,0,List.of(draft(direction,"Changed")),true,"Original trusted name")).isInstanceOf(LeadApiException.class);
        UUID id=first.rows().getFirst().leadId();repo.write(vendor,direction,vendor,UUID.randomUUID(),now,id,1,List.of(draft(direction,"Edited")),false,"Updated trusted name");
        var edited=repo.get(vendor,direction,id).orElseThrow();assertThat(edited.revision()).isEqualTo(2);assertThat(edited.createdBySubjectId()).isEqualTo(admin);assertThat(edited.updatedBySubjectId()).isEqualTo(vendor);assertThat(edited.importBatchId()).isEqualTo(op);
        assertThatThrownBy(()->repo.write(vendor,direction,admin,UUID.randomUUID(),now,id,1,List.of(draft),false,"Updated trusted name")).isInstanceOf(LeadApiException.class);
        var exportedReimport=repo.write(vendor,direction,admin,UUID.randomUUID(),now,null,0,List.of(new Draft(edited.fields(),edited.metadata())),true,"Updated trusted name");assertThat(exportedReimport.skipped()).isEqualTo(1);assertThat(exportedReimport.rows().getFirst().leadId()).isEqualTo(id);
        var oldRow=repo.write(vendor,direction,admin,UUID.randomUUID(),now,null,0,List.of(draft),true,"Original trusted name");assertThat(oldRow.failed()).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(LeadDirection.class) void csvAndXlsxPreviewImportExportRoundTrip(LeadDirection direction){
        create(direction,draft(direction,"Customer, \"Quoted\""));var caller=auth(direction==LeadDirection.KOC_TO_VENDOR?admin:vendor,direction==LeadDirection.KOC_TO_VENDOR?"CRM_ADMIN":"CRM_VENDOR");
        for(String format:List.of("csv","xlsx")){
            byte[] bytes=service.export(vendor,direction,new Search(5,1,null,null,null),format,caller);
            var file=new MockMultipartFile("file","leads."+format,"application/octet-stream",bytes);
            var preview=service.preview(vendor,direction,file,draft(direction,"T").metadata(),true,caller);
            assertThat(preview.rows()).hasSize(1);assertThat(preview.rows().getFirst().errors()).isEmpty();assertThat(preview.rows().getFirst().fields()).containsEntry("Phone","001234567890").containsEntry("Name","Customer, \"Quoted\"");
            var imported=service.importFile(vendor,direction,file,draft(direction,"T").metadata(),true,preview.operationId(),preview.submittedAt(),preview.fingerprint(),caller);assertThat(imported.skipped()).isEqualTo(1);
        }
    }
    @Test void filteringPaginationAndCompleteExport(){
        for(String name:List.of("Alpha","Beta","Gamma"))create(LeadDirection.KOC_TO_VENDOR,draft(LeadDirection.KOC_TO_VENDOR,name));
        var auth=auth(admin,"CRM_ADMIN");assertThat(service.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,1,null,null,null),auth).hasNext()).isTrue();
        assertThat(service.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,20,"ALP",null,null),auth).items()).hasSize(1);
        assertThat(service.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,20,null,"__source","Missing"),auth).items()).isEmpty();
        String csv=new String(service.export(vendor,LeadDirection.KOC_TO_VENDOR,new Search(2,1,null,null,null),"csv",auth),java.nio.charset.StandardCharsets.UTF_8);assertThat(csv).contains("Alpha","Beta","Gamma");
        assertThatThrownBy(()->new Search(100000,100,null,null,null)).isInstanceOf(LeadApiException.class);
        assertThatThrownBy(()->service.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,20,null,"SQL injection","x"),auth)).isInstanceOf(LeadApiException.class);
    }
    @ParameterizedTest @EnumSource(LeadDirection.class) void bothRolesReadStableVendorScopedPagesAndFilters(LeadDirection direction){
        for(String name:List.of("Page Alpha","Page Beta","Page Gamma"))create(direction,draft(direction,name));
        UUID otherVendor=UUID.randomUUID();
        service.write(otherVendor,direction,null,new Command(UUID.randomUUID(),now,0,draft(direction,"Other vendor")),auth(direction==LeadDirection.KOC_TO_VENDOR?admin:otherVendor,direction==LeadDirection.KOC_TO_VENDOR?"CRM_ADMIN":"CRM_VENDOR"));
        for(var reader:List.of(auth(admin,"CRM_ADMIN"),auth(vendor,"CRM_VENDOR"))){
            var first=service.list(vendor,direction,new Search(0,2,null,null,null),reader);
            var second=service.list(vendor,direction,new Search(1,2,null,null,null),reader);
            assertThat(first.items()).hasSize(2).allMatch(l->l.vendorSubjectId().equals(vendor)&&l.direction()==direction);
            assertThat(first.hasNext()).isTrue();assertThat(second.items()).hasSize(1);assertThat(second.hasNext()).isFalse();
            assertThat(first.items()).extracting(Lead::id).doesNotContain(second.items().getFirst().id());
            assertThat(service.list(vendor,direction,new Search(0,20,"ALPHA","__service","Existing service"),reader).items()).hasSize(1);
            assertThat(service.list(vendor,direction,new Search(0,20,null,"__source",direction.name()),reader).items()).hasSize(3);
            assertThat(service.list(vendor,direction,new Search(0,20,null,"__service","Missing"),reader).items()).isEmpty();
        }
    }
    @ParameterizedTest @EnumSource(LeadDirection.class) void invalidPreviewCannotCommitAnyRows(LeadDirection direction){
        var caller=auth(direction==LeadDirection.KOC_TO_VENDOR?admin:vendor,direction==LeadDirection.KOC_TO_VENDOR?"CRM_ADMIN":"CRM_VENDOR");
        var file=new MockMultipartFile("file","invalid.csv","text/csv","Name,Phone\nValid,001234567890\nInvalid,not-a-phone\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var metadata=new Metadata("photography",null,null);var preview=service.preview(vendor,direction,file,metadata,false,caller);
        assertThat(preview.rows()).extracting(PreviewRow::classification).containsExactly("NEW","INVALID");
        assertThat(preview.rows()).extracting(PreviewRow::row).containsExactly(2,3);
        assertThatThrownBy(()->service.importFile(vendor,direction,file,metadata,false,preview.operationId(),preview.submittedAt(),preview.fingerprint(),caller)).isInstanceOf(LeadApiException.class);
        assertThat(service.list(vendor,direction,new Search(0,20,null,null,null),caller).items()).isEmpty();
    }
    @Test void invalidPreviewAndDuplicateRows(){
        var direction=LeadDirection.VENDOR_TO_KOC;var draft=draft(direction,"Customer");var first=create(direction,draft).rows().getFirst().leadId();var lead=repo.get(vendor,direction,first).orElseThrow();
        byte[] bytes=SheetFiles.export(direction,List.of(lead,lead),"csv");var file=new MockMultipartFile("file","rows.csv","text/csv",bytes);var caller=auth(vendor,"CRM_VENDOR");var preview=service.preview(vendor,direction,file,draft.metadata(),true,caller);assertThat(preview.rows().get(1).duplicate()).isTrue();
        assertThatThrownBy(()->service.importFile(vendor,direction,file,draft.metadata(),true,preview.operationId(),now,"changed",caller)).isInstanceOf(LeadApiException.class);
        var imported=service.importFile(vendor,direction,file,draft.metadata(),true,preview.operationId(),now,preview.fingerprint(),caller);assertThat(imported.skipped()).isEqualTo(2);
    }
    private Draft completeKocDraft(String name,String phone){
        var fields=new LinkedHashMap<String,String>();for(var column:LeadDirection.KOC_TO_VENDOR.columns())fields.put(column.header(),null);
        fields.put("Relation","Bride");fields.put("Timestamp","2026-11-01T09:00:00+05:30");fields.put("Name",name);fields.put("Phone",phone);fields.put("Branch","HSR Layout");fields.put("Interested (Spin the wheel)","Photography");fields.put("occasion_date","2026-12-01");fields.put("occasion","Wedding");fields.put("Updates","Called");fields.put("Additional comments (optional)","Follow up");fields.put("Quoted amount","125.00");fields.put("Date Quoted","2026-11-01");
        return new Draft(LeadDirection.KOC_TO_VENDOR.normalize(fields),new Metadata("photography",LeadStage.NEW,LeadDirection.KOC_TO_VENDOR.name()));
    }
    @Test void exactDuplicatePreviewUsesEveryBusinessFieldAndScopesVendorDirection(){
        var baseline=completeKocDraft("Known","001234567890");repo.write(vendor,LeadDirection.KOC_TO_VENDOR,admin,UUID.randomUUID(),now,null,0,List.of(baseline),true,"Test Vendor");
        assertThat(repo.exactExistingRows(vendor,LeadDirection.KOC_TO_VENDOR,List.of(baseline))).containsExactly(0);
        for(var column:LeadDirection.KOC_TO_VENDOR.columns()){
            var changed=new LinkedHashMap<>(baseline.fields());String replacement=switch(column.header()){
                case "Relation"->"Groom";case "Timestamp"->"2026-11-01T10:00:00Z";case "Name"->"Different";case "Phone"->"001234567891";case "Branch"->"Jayanagar";case "Interested (Spin the wheel)"->"Other";case "occasion_date"->"2026-12-02";case "occasion"->"Reception";case "Updates"->"Emailed";case "Additional comments (optional)"->"Different note";case "Quoted amount"->"126";case "Date Quoted"->"2026-11-02";default->throw new AssertionError(column.header());};
            changed.put(column.header(),replacement);var different=new Draft(LeadDirection.KOC_TO_VENDOR.normalize(changed),baseline.metadata());assertThat(repo.exactExistingRows(vendor,LeadDirection.KOC_TO_VENDOR,List.of(different))).as(column.header()).isEmpty();
        }
        var otherService=new Draft(baseline.fields(),new Metadata("videography",baseline.metadata().stage(),baseline.metadata().source()));assertThat(repo.exactExistingRows(vendor,LeadDirection.KOC_TO_VENDOR,List.of(otherService))).isEmpty();
        assertThat(repo.exactExistingRows(UUID.randomUUID(),LeadDirection.KOC_TO_VENDOR,List.of(baseline))).isEmpty();assertThat(repo.exactExistingRows(vendor,LeadDirection.VENDOR_TO_KOC,List.of(draft(LeadDirection.VENDOR_TO_KOC,"Known")))).isEmpty();
    }
    @Test void nullTimestampIsAValidNullSafeExactDuplicate(){
        var fields=new LinkedHashMap<>(completeKocDraft("Known","001234567890").fields());fields.put("Timestamp",null);var nullTimestamp=new Draft(LeadDirection.KOC_TO_VENDOR.normalize(fields),new Metadata("photography",LeadStage.NEW,LeadDirection.KOC_TO_VENDOR.name()));
        repo.write(vendor,LeadDirection.KOC_TO_VENDOR,admin,UUID.randomUUID(),now,null,0,List.of(nullTimestamp),true,"Test Vendor");assertThat(repo.exactExistingRows(vendor,LeadDirection.KOC_TO_VENDOR,List.of(nullTimestamp))).containsExactly(0);
        var timestamp=new LinkedHashMap<>(fields);timestamp.put("Timestamp","2026-11-01T09:00:00Z");assertThat(repo.exactExistingRows(vendor,LeadDirection.KOC_TO_VENDOR,List.of(new Draft(LeadDirection.KOC_TO_VENDOR.normalize(timestamp),nullTimestamp.metadata())))).isEmpty();
    }
    private static String csvRow(LeadDirection direction,Map<String,String> values){return String.join(",",direction.headers().stream().map(header->Objects.toString(values.get(header),"")).toList());}
    @Test void previewClassifiesExistingExcelDuplicateNewAndInvalidRows(){
        var direction=LeadDirection.KOC_TO_VENDOR;var existing=completeKocDraft("Known","001234567890");repo.write(vendor,direction,admin,UUID.randomUUID(),now,null,0,List.of(existing),true,"Test Vendor");
        var known=existing.fields();var different=completeKocDraft("New customer","001234567891").fields();var invalid=new LinkedHashMap<String,String>(different);invalid.put("Phone",null);
        String csv=String.join(",",direction.headers())+"\n"+csvRow(direction,known)+"\n"+csvRow(direction,known)+"\n"+csvRow(direction,different)+"\n"+csvRow(direction,invalid);
        var file=new MockMultipartFile("file","mixed.csv","text/csv",csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));var preview=service.preview(vendor,direction,file,new Metadata("photography",LeadStage.NEW,direction.name()),false,auth(admin,"CRM_ADMIN"));
        assertThat(preview.rows()).extracting(PreviewRow::classification).containsExactly("EXISTING_IN_BIGQUERY","DUPLICATE_IN_FILE","NEW","INVALID");assertThat(preview.rows().get(1).duplicateOfRow()).isEqualTo(2);assertThat(preview.rows().get(0).row()).isEqualTo(2);assertThat(preview.rows().get(3).row()).isEqualTo(5);assertThat(preview.rows().get(3).errors()).isNotEmpty();
        assertThat(repo.list(vendor,direction,new Search(0,20,null,null,null),21,false)).hasSize(1);
    }
    @Test void repeatedUploadPreviewFindsExistingAndTransactionalImportStillSkips(){
        var direction=LeadDirection.KOC_TO_VENDOR;var draft=completeKocDraft("Known","001234567890");repo.write(vendor,direction,admin,UUID.randomUUID(),now,null,0,List.of(draft),true,"Test Vendor");
        var file=new MockMultipartFile("file","one.csv","text/csv",(String.join(",",direction.headers())+"\n"+csvRow(direction,draft.fields())).getBytes(java.nio.charset.StandardCharsets.UTF_8));var metadata=new Metadata("photography",LeadStage.NEW,direction.name());var caller=auth(admin,"CRM_ADMIN");
        var preview=service.preview(vendor,direction,file,metadata,false,caller);assertThat(preview.rows().getFirst().classification()).isEqualTo("EXISTING_IN_BIGQUERY");
        var receipt=service.importFile(vendor,direction,file,metadata,false,preview.operationId(),preview.submittedAt(),preview.fingerprint(),caller);assertThat(receipt.state()).isEqualTo("COMMITTED");assertThat(receipt.imported()).isZero();assertThat(receipt.skipped()).isEqualTo(1);
        assertThat(service.preview(vendor,direction,file,metadata,false,caller).rows().getFirst().classification()).isEqualTo("EXISTING_IN_BIGQUERY");
    }
    @Test void concurrentLocalImportsHaveOneRecordNotCloudProof() throws Exception {
        try(var threads=Executors.newFixedThreadPool(2)){var task=(Callable<Receipt>)()->repo.write(vendor,LeadDirection.KOC_TO_VENDOR,admin,UUID.randomUUID(),now,null,0,List.of(draft(LeadDirection.KOC_TO_VENDOR,"Concurrent")),true,"Trusted Vendor");var results=threads.invokeAll(List.of(task,task));assertThat(results.get(0).get().imported()+results.get(1).get().imported()).isEqualTo(1);}
    }
    @Test void absentCloudConfigurationNeverTouchesClient(){var repo=new BigQueryDirectionalRepository(()->{throw new AssertionError("Cloud access forbidden");},new com.kingofcards.crm.config.BigQueryProperties(false,"king-of-cards-509610","shared_rds_data","asia-south1","crm_leads",false,false,false),new DirectionalProperties(false,false,100,200),clock);assertThatThrownBy(()->repo.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,20,null,null,null),21,false)).isInstanceOf(LeadApiException.class);}
}
