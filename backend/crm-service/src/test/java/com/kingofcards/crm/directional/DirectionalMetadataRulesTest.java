package com.kingofcards.crm.directional;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.model.LeadStage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
class DirectionalMetadataRulesTest {
    final UUID vendor=UUID.randomUUID(),admin=UUID.randomUUID();final Clock clock=Clock.systemUTC();
    final LocalDirectionalRepository repository=new LocalDirectionalRepository(clock);
    final DirectionalService service=new DirectionalService(repository,(id,a,t)->new Vendor(id,"Offline Vendor",true,List.of("CRM_VENDOR")),new DirectionalProperties(true,false,100,200),clock);
    JwtAuthenticationToken auth(boolean a){return new JwtAuthenticationToken(Jwt.withTokenValue("offline-only").header("alg","RS256").subject((a?admin:vendor).toString()).build(),List.of(new SimpleGrantedAuthority(a?"ROLE_CRM_ADMIN":"ROLE_CRM_VENDOR")));}
    @Test void adminCannotSupplyStageOrSourceAndServiceIsPreserved(){var command=new Command(UUID.randomUUID(),clock.instant(),0,new Draft(Map.of("Name","Test","Phone","001234567890"),new Metadata("Selected service",LeadStage.CONVERTED,"Untrusted source")));var receipt=service.write(vendor,LeadDirection.KOC_TO_VENDOR,null,command,auth(true));var lead=service.get(vendor,LeadDirection.KOC_TO_VENDOR,receipt.rows().getFirst().leadId(),auth(true));assertThat(lead.metadata()).isEqualTo(new Metadata("Selected service",LeadStage.NEW,"KOC_TO_VENDOR"));assertThat(lead.vendorName()).isEqualTo("Offline Vendor");}
    @Test void vendorStatusIsPreservedAndRecognizedOrDefaultsToNew(){
        for(String status:List.of("Contacted","INTERESTED","Converted","Unrecognized status","New")){
            var command=new Command(UUID.randomUUID(),clock.instant(),0,new Draft(Map.of("Name",status,"Phone","001234567890","Status",status),new Metadata("Selected service",null,null)));
            var receipt=service.write(vendor,LeadDirection.VENDOR_TO_KOC,null,command,auth(false));var lead=service.get(vendor,LeadDirection.VENDOR_TO_KOC,receipt.rows().getFirst().leadId(),auth(false));
            assertThat(lead.fields().get("Status")).isEqualTo(status);assertThat(lead.metadata().source()).isEqualTo("VENDOR_TO_KOC");assertThat(lead.metadata().stage()).isEqualTo(status.equals("Unrecognized status")?LeadStage.NEW:LeadStage.valueOf(status.toUpperCase(Locale.ROOT)));assertThat(lead.vendorName()).isEqualTo("Offline Vendor");
        }
    }
    @Test void uploadNeedsOnlyServiceAndDerivesStageIndependentlyForEachRow(){
        var metadata=new Metadata("One selected service",null,null);var bytes="NAME,PHONE,STATUS\nFirst,001234567890,Converted\nSecond,001234567891,Unknown\n".getBytes(StandardCharsets.UTF_8);var file=new MockMultipartFile("file","rows.csv","text/csv",bytes);
        var preview=service.preview(vendor,LeadDirection.VENDOR_TO_KOC,file,metadata,false,auth(false));assertThat(preview.rows()).allMatch(r->r.errors().isEmpty());
        var receipt=service.importFile(vendor,LeadDirection.VENDOR_TO_KOC,file,metadata,false,preview.operationId(),preview.submittedAt(),preview.fingerprint(),auth(false));assertThat(receipt.imported()).isEqualTo(2);
        var first=repository.get(vendor,LeadDirection.VENDOR_TO_KOC,receipt.rows().get(0).leadId()).orElseThrow();var second=repository.get(vendor,LeadDirection.VENDOR_TO_KOC,receipt.rows().get(1).leadId()).orElseThrow();
        assertThat(first.metadata().stage()).isEqualTo(LeadStage.CONVERTED);assertThat(second.metadata().stage()).isEqualTo(LeadStage.NEW);assertThat(second.metadata().service()).isEqualTo("One selected service");assertThat(first.metadata().source()).isEqualTo("VENDOR_TO_KOC");assertThat(first.vendorName()).isEqualTo("Offline Vendor");assertThat(second.vendorName()).isEqualTo("Offline Vendor");
        assertThat(service.importFile(vendor,LeadDirection.VENDOR_TO_KOC,file,metadata,false,preview.operationId(),preview.submittedAt(),preview.fingerprint(),auth(false))).isEqualTo(receipt);
    }
    @Test void differentSelectedVendorsKeepTheirTrustedNamesSeparate(){
        UUID secondVendor=UUID.randomUUID();var names=Map.of(vendor,"Vendor One",secondVendor,"Vendor Two");var multiRepo=new LocalDirectionalRepository(clock);var multi=new DirectionalService(multiRepo,(id,a,t)->new Vendor(id,names.get(id),true,List.of("CRM_VENDOR")),new DirectionalProperties(true,false,100,200),clock);
        var fields=Map.of("Name","Same customer","Phone","001234567890");var draft=new Draft(LeadDirection.KOC_TO_VENDOR.normalize(fields),new Metadata("Photography",null,null));
        var first=multi.write(vendor,LeadDirection.KOC_TO_VENDOR,null,new Command(UUID.randomUUID(),clock.instant(),0,draft),auth(true));var second=multi.write(secondVendor,LeadDirection.KOC_TO_VENDOR,null,new Command(UUID.randomUUID(),clock.instant(),0,draft),auth(true));
        assertThat(multiRepo.get(vendor,LeadDirection.KOC_TO_VENDOR,first.rows().getFirst().leadId()).orElseThrow().vendorName()).isEqualTo("Vendor One");
        assertThat(multiRepo.get(secondVendor,LeadDirection.KOC_TO_VENDOR,second.rows().getFirst().leadId()).orElseThrow().vendorName()).isEqualTo("Vendor Two");
    }
    @Test void blankTrustedIdentityNameFailsBeforeAnyLeadIsStored(){
        var guarded=new DirectionalService(repository,(id,a,t)->new Vendor(id,"  ",true,List.of("CRM_VENDOR")),new DirectionalProperties(true,false,100,200),clock);var draft=new Draft(Map.of("Name","Must not save","Phone","001234567890"),new Metadata("Photography",null,null));
        assertThatThrownBy(()->guarded.write(vendor,LeadDirection.KOC_TO_VENDOR,null,new Command(UUID.randomUUID(),clock.instant(),0,draft),auth(true))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(repository.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,20,null,null,null),20,false)).isEmpty();
    }
    @Test void vendorNameCannotBeSuppliedAsADirectionalSpreadsheetField(){
        var fields=new HashMap<String,String>();fields.put("Name","Customer");fields.put("Phone","001234567890");fields.put("vendor_name","Forged Vendor");
        assertThatThrownBy(()->LeadDirection.KOC_TO_VENDOR.normalize(fields)).isInstanceOf(com.kingofcards.crm.exception.LeadApiException.class);
    }
    @Test void singleLeadCannotOverrideIdentityServiceVendorName(){
        var fields=new HashMap<String,String>();fields.put("Name","Customer");fields.put("Phone","001234567890");fields.put("vendor_name","Forged Vendor");
        var command=new Command(UUID.randomUUID(),clock.instant(),0,new Draft(fields,new Metadata("Photography",null,null)));
        assertThatThrownBy(()->service.write(vendor,LeadDirection.KOC_TO_VENDOR,null,command,auth(true))).isInstanceOf(com.kingofcards.crm.exception.LeadApiException.class);
        assertThat(repository.list(vendor,LeadDirection.KOC_TO_VENDOR,new Search(0,20,null,null,null),20,false)).isEmpty();
    }
    @Test void serviceRemainsRequired(){assertThatThrownBy(()->new Metadata(" ",null,null)).hasMessageContaining("Select a service");}
}
