package com.kingofcards.crm.directional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import com.kingofcards.crm.directional.DirectionalModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

/** Synthetic values under the ten supplied headers; never reads or writes cloud data. */
class KocOriginalSheetImportTest {
    static final List<String> HEADERS=List.of("Date & time","Branch","Name","Phone","Occasion / event","Event date","Updates","Additional comments (optional)","Quoted amount","Date Quoted");
    final UUID vendor=UUID.randomUUID(),admin=UUID.randomUUID();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"),ZoneOffset.UTC);
    final LocalDirectionalRepository repo=new LocalDirectionalRepository(clock);
    final DirectionalService service=new DirectionalService(repo,(id,a,t)->new Vendor(id,"Trusted synthetic vendor",true,List.of("CRM_VENDOR")),new DirectionalProperties(true,false,100,200),clock);
    JwtAuthenticationToken auth(UUID id,String role){return new JwtAuthenticationToken(Jwt.withTokenValue("synthetic-local-only").header("alg","RS256").subject(id.toString()).build(),List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    List<String> row(){return List.of("2026-11-18T10:00:00+05:30","Synthetic Branch","Synthetic Person","+91 00000 00000","wedding","18/11/2026","","","4,000","11/18/2026");}
    List<String> changed(List<String> row,int column,String value){var copy=new ArrayList<>(row);copy.set(column,value);return copy;}
    static String xml(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    static String column(int index){var result=new StringBuilder();for(int n=index+1;n>0;n=(n-1)/26)result.insert(0,(char)('A'+(n-1)%26));return result.toString();}
    MockMultipartFile file(boolean xlsx,List<String> headers,List<List<String>> rows) throws Exception {
        var data=new ArrayList<List<String>>();data.add(headers);data.addAll(rows);
        if(!xlsx){var csv=new StringBuilder();for(var cells:data){csv.append(String.join(",",cells.stream().map(v->"\""+v.replace("\"","\"\"")+"\"").toList())).append('\n');}return new MockMultipartFile("file","original.csv","text/csv",csv.toString().getBytes(StandardCharsets.UTF_8));}
        var sheet=new StringBuilder("<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData>");
        for(int i=0;i<data.size();i++){sheet.append("<row r='").append(i+1).append("'>");for(int c=0;c<data.get(i).size();c++){sheet.append("<c r='").append(column(c)).append(i+1).append("' t='inlineStr'><is><t>").append(xml(data.get(i).get(c))).append("</t></is></c>");}sheet.append("</row>");}sheet.append("</sheetData></worksheet>");
        var parts=Map.of("xl/workbook.xml","<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Leads' sheetId='1' r:id='r1'/></sheets></workbook>","xl/_rels/workbook.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='r1' Target='worksheets/sheet1.xml'/></Relationships>","xl/worksheets/sheet1.xml",sheet.toString());
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var part:parts.entrySet()){zip.putNextEntry(new ZipEntry(part.getKey()));zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        return new MockMultipartFile("file","original.xlsx","application/octet-stream",out.toByteArray());
    }
    Preview preview(boolean xlsx,List<String> headers,List<List<String>> rows) throws Exception {return service.preview(vendor,LeadDirection.KOC_TO_VENDOR,file(xlsx,headers,rows),new Metadata("photography",null,null),false,auth(admin,"CRM_ADMIN"));}

    @ParameterizedTest @ValueSource(booleans={false,true}) void originalTenHeadersAndValuesNormalizeWithoutManualRenaming(boolean xlsx) throws Exception {
        var headers=new ArrayList<>(HEADERS);headers.add("");headers.add("");var values=new ArrayList<>(row());values.add("");values.add("");
        var result=preview(xlsx,headers,List.of(values)).rows().getFirst();assertThat(result.errors()).isEmpty();assertThat(result.classification()).isEqualTo("NEW");
        assertThat(result.fields()).containsEntry("Timestamp","2026-11-18T04:30:00Z").containsEntry("occasion","wedding").containsEntry("occasion_date","2026-11-18").containsEntry("Date Quoted","2026-11-18").containsEntry("Quoted amount","4000").containsEntry("Phone","+91 00000 00000").containsEntry("Additional comments (optional)",null).containsEntry("Relation",null).containsEntry("Interested (Spin the wheel)",null);
        assertThat(repo.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,20,null,null,null),21,false)).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void aliasesMatchCaseWhitespacePunctuationAndRejectDuplicateAliases(boolean xlsx) throws Exception {
        var headers=new ArrayList<>(HEADERS);headers.set(0," DATE_and_TIME ");headers.set(4,"OCCASION-event");headers.set(5,"EVENT_DATE");
        assertThat(preview(xlsx,headers,List.of(row())).rows().getFirst().errors()).isEmpty();
        headers.add("Timestamp");var values=new ArrayList<>(row());values.add("");assertThatThrownBy(()->preview(xlsx,headers,List.of(values))).hasMessageContaining("Duplicate or ambiguous header for Timestamp");
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void manyEmptyTrailingColumnsAreStreamCheckedWithoutDroppingLateValues(boolean xlsx) throws Exception {
        var headers=new ArrayList<>(HEADERS);headers.addAll(Collections.nCopies(40,""));
        var values=new ArrayList<>(row());values.addAll(Collections.nCopies(40,""));
        assertThat(preview(xlsx,headers,List.of(values)).rows().getFirst().errors()).isEmpty();
        values.set(49,"Late unexpected data");
        assertThatThrownBy(()->preview(xlsx,headers,List.of(values))).hasMessageContaining("Row 2, column 50");
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void trailingColumnsMustBeCompletelyEmptyAndUnknownHeadersAreRejected(boolean xlsx) throws Exception {
        var headers=new ArrayList<>(HEADERS);headers.add("");var safe=new ArrayList<>(row());safe.add("");var unsafe=new ArrayList<>(row());unsafe.add("Do not discard");
        assertThatThrownBy(()->preview(xlsx,headers,List.of(safe,unsafe))).hasMessageContaining("Row 3, column 11");
        headers.set(10,"Unknown notes");assertThatThrownBy(()->preview(xlsx,headers,List.of(safe))).hasMessageContaining("Unrecognized header in column 11");
        headers.set(10,"Vendor Name");assertThat(preview(xlsx,headers,List.of(safe)).rows().getFirst().errors()).isEmpty();
        assertThatThrownBy(()->preview(xlsx,List.of("Name","","Phone"),List.of(List.of("Synthetic","","001234567890")))).hasMessageContaining("Unrecognized header in column 2");
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void mixedDatesAreSafeOptionalBlanksStayNullAndInvalidRowsRetainSourceNumbers(boolean xlsx) throws Exception {
        var unambiguous=changed(row(),0,"18/11/2026 10:00:00+05:30");
        var ambiguous=changed(row(),5,"05/11/2026");var dateOnly=changed(row(),0,"2026-11-18");var local=changed(row(),0,"2026-11-18 10:00:00");
        var blank=new ArrayList<>(Collections.nCopies(10,""));blank.set(2,"Optional blanks");blank.set(3,"001234567890");
        var rows=preview(xlsx,HEADERS,List.of(unambiguous,ambiguous,dateOnly,local,blank)).rows();
        assertThat(rows).extracting(PreviewRow::row).containsExactly(2,3,4,5,6);assertThat(rows.get(0).errors()).isEmpty();
        assertThat(rows.get(1).errors().getFirst()).containsIgnoringCase("row 3").contains("ambiguous day/month");
        assertThat(rows.get(2).errors()).isEmpty();assertThat(rows.get(2).fields().get("Timestamp")).isNull();assertThat(rows.get(2).sourceDateTime().date()).isEqualTo(java.time.LocalDate.of(2026,11,18));assertThat(rows.get(2).sourceDateTime().timeSupplied()).isFalse();
        assertThat(rows.get(3).errors().getFirst()).containsIgnoringCase("row 5").contains("explicit timezone offset");
        assertThat(rows.get(4).errors()).isEmpty();assertThat(rows.get(4).fields()).containsEntry("Timestamp",null).containsEntry("occasion_date",null).containsEntry("Quoted amount",null).containsEntry("Phone","001234567890");
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void normalizedAmountsAndDatesUseExistingFileAndDatabaseDuplicateChecks(boolean xlsx) throws Exception {
        var initial=preview(xlsx,HEADERS,List.of(row()));var fields=initial.rows().getFirst().fields();
        repo.write(vendor,LeadDirection.KOC_TO_VENDOR,admin,UUID.randomUUID(),clock.instant(),null,0,List.of(new Draft(fields,new Metadata("photography",com.kingofcards.crm.model.LeadStage.NEW,"KOC_TO_VENDOR"))),false,"Trusted synthetic vendor");
        var normalized=changed(changed(changed(row(),8,"4000"),5,"2026-11-18"),9,"2026-11-18");
        var newLead=changed(row(),2,"New synthetic person");var invalid=changed(row(),8,"4,00");
        var result=preview(xlsx,HEADERS,List.of(row(),normalized,newLead,invalid));
        assertThat(result.rows()).extracting(PreviewRow::classification).containsExactly("EXISTING_IN_BIGQUERY","DUPLICATE_IN_FILE","NEW","INVALID");assertThat(result.rows().get(1).duplicateOfRow()).isEqualTo(2);assertThat(result.rows().get(3).errors().getFirst()).contains("Quoted amount", "ambiguous comma grouping");
        assertThat(repo.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,20,null,null,null),21,false)).hasSize(1);
    }
    @Test void vendorCannotUseKocAliasesToBypassDirectionOrOwnership() throws Exception {
        var file=file(false,HEADERS,List.of(row()));
        assertThatThrownBy(()->service.preview(vendor,LeadDirection.KOC_TO_VENDOR,file,new Metadata("photography",null,null),false,auth(vendor,"CRM_VENDOR"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.preview(UUID.randomUUID(),LeadDirection.VENDOR_TO_KOC,file,new Metadata("photography",null,null),false,auth(vendor,"CRM_VENDOR"))).isInstanceOf(AccessDeniedException.class);
    }
}
