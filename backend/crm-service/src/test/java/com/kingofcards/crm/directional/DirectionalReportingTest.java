package com.kingofcards.crm.directional;

import java.time.*;
import java.util.*;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.model.LeadStage;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectionalReportingTest {
    final UUID vendor=UUID.randomUUID(),other=UUID.randomUUID(),admin=UUID.randomUUID();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"),ZoneOffset.UTC);
    final LocalDirectionalRepository repository=new LocalDirectionalRepository(clock);
    final DirectionalService service=new DirectionalService(repository,(id,a,t)->new Vendor(id,"Trusted report vendor",true,List.of("CRM_VENDOR")),new DirectionalProperties(true,false,100,200),clock);
    JwtAuthenticationToken auth(UUID id,String role){return new JwtAuthenticationToken(Jwt.withTokenValue("offline-report-token").header("alg","RS256").subject(id.toString()).build(),List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    void persist(UUID owner,LeadDirection direction,LeadStage stage,String name){
        var fields=direction.normalize(Map.of("Name",name,"Phone","001234567890"));
        repository.write(owner,direction,admin,UUID.randomUUID(),clock.instant(),null,0,List.of(new Draft(fields,new Metadata("photography",stage,direction.name()))),false,"Snapshot name");
    }
    @Test void bothReadersSeeOnlySelectedVendorWithBothDirectionsAndPersistedStages(){
        persist(vendor,LeadDirection.KOC_TO_VENDOR,LeadStage.NEW,"New");persist(vendor,LeadDirection.KOC_TO_VENDOR,LeadStage.CONTACTED,"Contacted");
        persist(vendor,LeadDirection.VENDOR_TO_KOC,LeadStage.INTERESTED,"Interested");persist(vendor,LeadDirection.VENDOR_TO_KOC,LeadStage.CONVERTED,"Converted");
        persist(other,LeadDirection.KOC_TO_VENDOR,LeadStage.CONVERTED,"Foreign");
        for(var reader:List.of(auth(admin,"CRM_ADMIN"),auth(vendor,"CRM_VENDOR"))){
            var report=service.report(vendor,reader);assertThat(report.vendorSubjectId()).isEqualTo(vendor);assertThat(report.vendorName()).isEqualTo("Trusted report vendor");
            assertThat(report.totals()).isEqualTo(new StageCounts(4,1,1,1,1,0));assertThat(report.conversionRatePercent()).isEqualByComparingTo("25.00");
            assertThat(report.directions()).extracting(DirectionReport::direction).containsExactly(LeadDirection.KOC_TO_VENDOR,LeadDirection.VENDOR_TO_KOC);
            assertThat(report.directions().getFirst().counts().total()).isEqualTo(2);assertThat(report.directions().getLast().conversionRatePercent()).isEqualByComparingTo("50.00");
        }
        assertThat(service.report(other,auth(admin,"CRM_ADMIN")).totals().total()).isEqualTo(1);
    }
    @Test void emptyReportsAndFractionalRatesAreDefined(){
        var empty=service.report(vendor,auth(vendor,"CRM_VENDOR"));assertThat(empty.totals()).isEqualTo(StageCounts.empty());assertThat(empty.directions()).hasSize(2);assertThat(empty.conversionRatePercent()).isEqualByComparingTo("0.00");
        assertThat(new StageCounts(3,1,0,0,1,1).conversionRatePercent()).isEqualByComparingTo("33.33");
        assertThatThrownBy(()->new StageCounts(1,1,1,0,0,0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unauthorizedCallersNeverReachReportRepository(){
        var repo=mock(DirectionalRepository.class);var identity=mock(DashboardIdentity.class);
        var secured=new DirectionalService(repo,identity,new DirectionalProperties(true,false,100,200),clock);
        assertThatThrownBy(()->secured.report(other,auth(vendor,"CRM_VENDOR"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->secured.report(vendor,auth(admin,"CRM_MANAGER"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->secured.report(vendor,null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repo,identity);
    }
    @Test void verifiedCommittedEditsChangeMetricsWithoutAddingAnotherLead(){
        var caller=auth(vendor,"CRM_VENDOR");var direction=LeadDirection.VENDOR_TO_KOC;
        var original=new Draft(Map.of("Name","Changing stage","Phone","001234567890","Status","INTERESTED"),new Metadata("photography",null,null));
        var receipt=service.write(vendor,direction,null,new Command(UUID.randomUUID(),clock.instant(),0,original),caller);
        assertThat(service.report(vendor,caller).totals().interested()).isEqualTo(1);
        var changed=new Draft(Map.of("Name","Changing stage","Phone","001234567890","Status","CONVERTED"),original.metadata());
        service.write(vendor,direction,receipt.rows().getFirst().leadId(),new Command(UUID.randomUUID(),clock.instant(),1,changed),caller);
        var report=service.report(vendor,caller);assertThat(report.totals().total()).isEqualTo(1);assertThat(report.totals().interested()).isZero();assertThat(report.totals().converted()).isEqualTo(1);
    }
}
