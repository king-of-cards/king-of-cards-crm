package com.kingofcards.crm.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import jakarta.validation.Validation;
import com.kingofcards.crm.exception.LeadApiException;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class LeadImportServiceTest {
    static final String VENDOR="00000000-0000-4000-8000-000000000001";
    static final String HEADER=String.join(",",LeadUploadParser.HEADERS)+"\r\n";
    static final String ROW="Synthetic,+919876543210,Service,CONTACTED,ExistingSource,"+VENDOR;
    final AtomicLeadImportStore store=mock(AtomicLeadImportStore.class);
    final jakarta.validation.ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
    final LeadUploadParser parser=new LeadUploadParser(200,2097152);
    final LeadImportService service=new LeadImportService(parser,factory.getValidator(),store);
    @AfterEach void close(){factory.close();}
    MockMultipartFile csv(String content){return new MockMultipartFile("file","leads.csv","text/csv",content.getBytes(StandardCharsets.UTF_8));}
    JwtAuthenticationToken auth(String role){return new JwtAuthenticationToken(Jwt.withTokenValue("synthetic").header("alg","RS256").subject(VENDOR).build(),List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    @Test void csvPreviewSupportsQuotingAndPreservesUnknownSourceWithoutWrites() {
        var preview=service.validate(csv("\uFEFF"+HEADER+ROW.replace("Synthetic","\"Synthetic, Name\"")+"\r\n"));
        assertEquals("Synthetic, Name",preview.rows().getFirst().lead().name());
        assertEquals("ExistingSource",preview.rows().getFirst().lead().source());
        assertTrue(preview.rows().getFirst().errors().isEmpty());assertFalse(preview.vendorAssignmentsVerified());
        assertEquals("FILE_ONLY",preview.duplicateScope());verifyNoInteractions(store);
    }
    @Test void invalidRowsAndNormalizedExactDuplicatesAreReportedWithRowNumbers() {
        var preview=service.validate(csv(HEADER+ROW+"\n"+ROW.replace("+919876543210","+91 98765 43210")+"\n"+ROW.replace("CONTACTED","NEW").replace("+919876543210","123")));
        assertTrue(preview.rows().get(1).errors().contains("Duplicate row in this upload."));
        assertEquals(3,preview.rows().get(1).row());assertFalse(preview.rows().get(2).errors().isEmpty());
    }
    @Test void importRequiresUnchangedErrorFreePreviewAndVerifiedAdministrator() {
        var file=csv(HEADER+ROW);var preview=service.validate(file);
        assertThrows(LeadApiException.class,()->service.importFile(file,preview.operationId(),"tampered",auth("CRM_ADMIN")));
        assertThrows(AccessDeniedException.class,()->service.importFile(file,preview.operationId(),preview.fingerprint(),auth("CRM_VENDOR")));
        verifyNoInteractions(store);
        when(store.importBatch(any(),any(),anyString(),anyList())).thenReturn(new AtomicLeadImportStore.Result(1,1,0));
        assertEquals(1,service.importFile(file,preview.operationId(),preview.fingerprint(),auth("CRM_ADMIN")).imported());
        verify(store,times(1)).importBatch(eq(preview.operationId()),any(),eq(preview.fingerprint()),anyList());
    }
    @Test void uncertainPersistenceIsNotRetriedAndRealDefaultIsDisabled() {
        var file=csv(HEADER+ROW);var preview=service.validate(file);
        when(store.importBatch(any(),any(),anyString(),anyList())).thenThrow(LeadApiException.unavailable());
        assertThrows(LeadApiException.class,()->service.importFile(file,preview.operationId(),preview.fingerprint(),auth("CRM_ADMIN")));
        verify(store,times(1)).importBatch(any(),any(),anyString(),anyList());
        assertThrows(LeadApiException.class,()->new UnverifiedLeadImportStore().importBatch(UUID.randomUUID(),UUID.randomUUID(),"fingerprint",List.of()));
    }
    @Test void malformedHeadersLimitsAndQuotingAreRejected() {
        for(String text:List.of("wrong\n"+ROW,HEADER+"\"unclosed",HEADER))assertThrows(LeadApiException.class,()->service.validate(csv(text)));
        assertFalse(service.validate(csv(HEADER+ROW+",extra")).rows().getFirst().errors().isEmpty());
        assertThrows(LeadApiException.class,()->new LeadUploadParser(1,10000).parse(csv(HEADER+ROW+"\n"+ROW)));
        assertThrows(LeadApiException.class,()->parser.parse(new MockMultipartFile("file","file.csv","text/csv",new byte[2097153])));
    }
    byte[] workbook(String sheet,String workbook) throws Exception {
        var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){
            for(var entry:Map.of("xl/workbook.xml",workbook,"xl/_rels/workbook.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Target='worksheets/sheet1.xml'/></Relationships>","xl/worksheets/sheet1.xml",sheet).entrySet()){
                zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}}
        return out.toByteArray();
    }
    String worksheet(){var xml=new StringBuilder("<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheetData>");
        var rows=List.of(LeadUploadParser.HEADERS,Arrays.asList(ROW.split(",")));
        for(int i=0;i<rows.size();i++){xml.append("<row r='").append(i+1).append("'>");for(int j=0;j<6;j++)xml.append("<c r='").append((char)('A'+j)).append(i+1).append("' t='inlineStr'><is><t>").append(rows.get(i).get(j)).append("</t></is></c>");xml.append("</row>");}
        return xml.append("</sheetData></worksheet>").toString();}
    static final String BOOK="<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Leads' r:id='rId1'/></sheets></workbook>";
    @Test void xlsxValueOnlyWorkbookIsParsedWithoutCloudOrExternalDependencies() throws Exception {
        var preview=service.validate(new MockMultipartFile("file","leads.xlsx","application/octet-stream",workbook(worksheet(),BOOK)));
        assertTrue(preview.rows().getFirst().errors().isEmpty());assertEquals("Synthetic",preview.rows().getFirst().lead().name());
    }
    @Test void xlsxFormulasAndExternalEntitiesAreRejected() throws Exception {
        var formula=workbook(worksheet().replace("<is><t>Synthetic</t></is>","<f>1+1</f><v>2</v>"),BOOK);
        assertThrows(LeadApiException.class,()->parser.parse(new MockMultipartFile("file","leads.xlsx","application/octet-stream",formula)));
        var external=workbook(worksheet(),"<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///not-permitted'>]>"+BOOK);
        assertThrows(LeadApiException.class,()->parser.parse(new MockMultipartFile("file","leads.xlsx","application/octet-stream",external)));
    }
    @Test void oversizedZipExpansionIsRejectedBeforeWorkbookParsing() throws Exception {
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){
            zip.putNextEntry(new ZipEntry("oversized.xml"));zip.write(new byte[9_000_000]);zip.closeEntry();
        }
        var file=new MockMultipartFile("file","leads.xlsx","application/octet-stream",output.toByteArray());
        assertThrows(LeadApiException.class,()->parser.parse(file));
    }
}
