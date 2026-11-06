package com.kingofcards.crm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import com.kingofcards.crm.config.SecurityConfig;
import com.kingofcards.crm.controller.LeadController;
import com.kingofcards.crm.exception.*;
import com.kingofcards.crm.model.*;
import com.kingofcards.crm.repository.LeadRepository;
import com.kingofcards.crm.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LeadController.class)
@Import({SecurityConfig.class, com.kingofcards.crm.config.TestAccountStatusConfiguration.class, LeadService.class, LeadExceptionHandler.class, LeadControllerTest.TimeConfig.class})
class LeadControllerTest {
    @Test void offlineVendorCreateListDetailAndCrossVendorDenialWorkflow() throws Exception {
        var saved=new java.util.concurrent.atomic.AtomicReference<CrmLeadRecord>();
        doAnswer(call->{saved.set(call.getArgument(0));return null;}).when(repository).insert(any());
        when(repository.search(any(),eq(VENDOR))).thenAnswer(call->List.of(saved.get()));
        when(repository.find(any(),any())).thenAnswer(call->
            saved.get().vendorSubjectId().equals(call.getArgument(1))?Optional.of(saved.get()):Optional.empty());
        String self=body().replace(",\"vendorSubjectId\":\""+VENDOR+"\"","");
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_VENDOR").contentType(MediaType.APPLICATION_JSON).content(self))
            .andExpect(status().isCreated());
        mvc.perform(get("/leads").header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(saved.get().leadId().toString()));
        mvc.perform(get("/leads/"+saved.get().leadId()).header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isOk());
        when(decoder.decode("OTHER_VENDOR")).thenReturn(Jwt.withTokenValue("OTHER_VENDOR").header("alg","RS256")
            .subject(OTHER.toString()).claim("roles",List.of("CRM_VENDOR")).build());
        mvc.perform(get("/leads/"+saved.get().leadId()).header("Authorization","Bearer OTHER_VENDOR"))
            .andExpect(status().isNotFound());
    }
    static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID VENDOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LeadRepository repository;
    @MockitoBean VendorDirectory vendors;
    @TestConfiguration static class TimeConfig { @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); } }
    @BeforeEach void setup() {
        for (String role : List.of("CRM_ADMIN", "CRM_VENDOR", "CRM_MANAGER", "CRM_AGENT")) {
            Jwt jwt = Jwt.withTokenValue(role).header("alg", "RS256").subject((role.equals("CRM_VENDOR") ? VENDOR : ADMIN).toString())
                .claim("roles", List.of(role)).build();
            when(decoder.decode(role)).thenReturn(jwt);
        }
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));
    }
    private String body() { return """
        {"name":" Synthetic Lead ","phone":"+91 98765 43210","service":"Photography",
         "stage":"CONTACTED","source":"Referral","vendorSubjectId":"%s"}
        """.formatted(VENDOR); }
    private CrmLeadRecord row(UUID vendor) { return new CrmLeadRecord(ID,"Synthetic Lead","+919876543210","Photography",LeadStage.CONTACTED,"Referral",vendor,ADMIN,NOW,NOW); }
    @Test void adminCreatesAndValidatesAuthoritativeVendor() throws Exception {
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_ADMIN").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.name").value("Synthetic Lead")).andExpect(jsonPath("$.phone").value("+919876543210"))
            .andExpect(jsonPath("$.vendorSubjectId").value(VENDOR.toString())).andExpect(jsonPath("$.createdBySubjectId").value(ADMIN.toString()))
            .andExpect(jsonPath("$.displayName").doesNotExist());
        var captured = ArgumentCaptor.forClass(CrmLeadRecord.class);
        verify(repository).assertWritesEnabled(); verify(vendors).requireAssignable(VENDOR,"CRM_ADMIN");
        verify(repository).insert(captured.capture());
        assertThat(captured.getValue().leadId()).isNotNull().isNotEqualTo(ID);
        assertThat(captured.getValue().createdAt()).isEqualTo(NOW);
        assertThat(captured.getValue().updatedAt()).isEqualTo(NOW);
    }
    @Test void adminSearchFiltersAndPagination() throws Exception {
        when(repository.search(any(), isNull())).thenReturn(List.of(row(VENDOR),row(OTHER)));
        mvc.perform(get("/leads").header("Authorization","Bearer CRM_ADMIN").param("page","1").param("size","1")
            .param("q","Synthetic").param("service","Photography").param("stage","CONTACTED").param("vendorSubjectId",VENDOR.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasNext").value(true));
        verify(repository).search(new LeadSearch(1,1,"Synthetic","Photography",LeadStage.CONTACTED,VENDOR),null);
    }
    @Test void vendorListAlwaysUsesVerifiedSubject() throws Exception {
        when(repository.search(any(),eq(VENDOR))).thenReturn(List.of(row(VENDOR)));
        mvc.perform(get("/leads").header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isOk());
        verify(repository).search(new LeadSearch(0,20,null,null,null,null),VENDOR);
    }
    @Test void vendorCannotSupplyOwnerFilter() throws Exception {
        mvc.perform(get("/leads").header("Authorization","Bearer CRM_VENDOR").param("vendorSubjectId",OTHER.toString()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(repository,vendors);
    }
    @Test void disabledVendorCannotReadOrCreateUsingAnOtherwiseValidJwt() throws Exception {
        doThrow(new LeadApiException(HttpStatus.FORBIDDEN,"VENDOR_ACCOUNT_INELIGIBLE","Vendor account is not active."))
            .when(vendors).requireSelfAssignable(VENDOR,"CRM_VENDOR");
        mvc.perform(get("/leads").header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isForbidden());
        mvc.perform(get("/leads/"+ID).header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isForbidden());
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_VENDOR").contentType(MediaType.APPLICATION_JSON)
            .content(body().replace(",\"vendorSubjectId\":\""+VENDOR+"\"",""))).andExpect(status().isForbidden());
        verify(repository,never()).search(any(),any());verify(repository,never()).find(any(),any());verify(repository,never()).insert(any());
    }
    @Test void vendorCannotReadAnotherVendorsDetailEvenIfRepositoryReturnsIt() throws Exception {
        when(repository.find(ID,VENDOR)).thenReturn(Optional.of(row(OTHER)));
        mvc.perform(get("/leads/"+ID).header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isNotFound());
        verify(repository).find(ID,VENDOR);
    }
    @Test void vendorOwnDetailAndAdminDetail() throws Exception {
        when(repository.find(ID,VENDOR)).thenReturn(Optional.of(row(VENDOR)));
        when(repository.find(ID,null)).thenReturn(Optional.of(row(VENDOR)));
        mvc.perform(get("/leads/"+ID).header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isOk());
        mvc.perform(get("/leads/"+ID).header("Authorization","Bearer CRM_ADMIN")).andExpect(status().isOk());
    }
    @ParameterizedTest @ValueSource(strings={"CRM_MANAGER","CRM_AGENT"})
    void unapprovedRolesCannotRead(String role) throws Exception {
        mvc.perform(get("/leads").header("Authorization","Bearer "+role)).andExpect(status().isForbidden());
        mvc.perform(get("/leads/"+ID).header("Authorization","Bearer "+role)).andExpect(status().isForbidden());
        verifyNoInteractions(repository,vendors);
    }
    @ParameterizedTest @ValueSource(strings={"CRM_MANAGER","CRM_AGENT"})
    void unapprovedRolesCannotCreate(String role) throws Exception {
        mvc.perform(post("/leads").header("Authorization","Bearer "+role).contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(repository,vendors);
    }
    @Test void vendorCreatesOnlyForItsVerifiedSubjectAndChecksCurrentIdentity() throws Exception {
        String self=body().replace(",\"vendorSubjectId\":\""+VENDOR+"\"","");
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_VENDOR").contentType(MediaType.APPLICATION_JSON).content(self))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.vendorSubjectId").value(VENDOR.toString()))
            .andExpect(jsonPath("$.createdBySubjectId").value(VENDOR.toString()));
        verify(vendors).requireSelfAssignable(VENDOR,"CRM_VENDOR");verify(vendors,never()).requireAssignable(any(),any());
        verify(repository).insert(argThat(row->row.vendorSubjectId().equals(VENDOR) && row.createdBySubjectId().equals(VENDOR)));
    }
    @Test void vendorCannotSupplyAnyAssignmentEvenItsOwn() throws Exception {
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_VENDOR").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isForbidden());verifyNoInteractions(repository,vendors);
    }
    @Test void adminMustStillSelectVendor() throws Exception {
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_ADMIN").contentType(MediaType.APPLICATION_JSON)
            .content(body().replace(",\"vendorSubjectId\":\""+VENDOR+"\"",""))).andExpect(status().isBadRequest());
        verifyNoInteractions(repository,vendors);
    }
    @Test void vendorIdentityFailureCannotInsert() throws Exception {
        doThrow(LeadApiException.unavailable()).when(vendors).requireSelfAssignable(any(),any());
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_VENDOR").contentType(MediaType.APPLICATION_JSON)
            .content(body().replace(",\"vendorSubjectId\":\""+VENDOR+"\"",""))).andExpect(status().isServiceUnavailable());
        verify(repository,never()).insert(any());
    }
    @Test void missingAndInvalidJwtRejected() throws Exception {
        mvc.perform(get("/leads")).andExpect(status().isUnauthorized());
        mvc.perform(get("/leads").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository,vendors);
    }
    @ParameterizedTest @ValueSource(strings={"missing","bad-phone","bad-stage","missing-vendor","long-name"})
    void validatesCreation(String scenario) throws Exception {
        String json = switch(scenario) {
            case "missing" -> "{}";
            case "bad-phone" -> body().replace("+91 98765 43210","9876543210");
            case "bad-stage" -> body().replace("CONTACTED","Contacted");
            case "missing-vendor" -> body().replace(VENDOR.toString(),"not-a-uuid");
            default -> body().replace("Synthetic Lead","x".repeat(121));
        };
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_ADMIN").contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LEAD_REQUEST"));
        verifyNoInteractions(repository,vendors);
    }
    @ParameterizedTest @ValueSource(strings={"size=101","size=0","page=-1","page=100001","stage=NOT_INTERESTED"})
    void rejectsUnboundedOrInvalidFilters(String query) throws Exception {
        String[] pair=query.split("=");
        mvc.perform(get("/leads").header("Authorization","Bearer CRM_ADMIN").param(pair[0],pair[1]))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
    @Test void invalidVendorPreventsInsert() throws Exception {
        doThrow(new LeadApiException(HttpStatus.UNPROCESSABLE_CONTENT,"INVALID_VENDOR_ASSIGNMENT","Selected vendor is not assignable."))
            .when(vendors).requireAssignable(VENDOR,"CRM_ADMIN");
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_ADMIN").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isUnprocessableEntity());
        verify(repository,never()).insert(any());
    }
    @Test void bigQueryFailureIsSafe() throws Exception {
        when(repository.search(any(),isNull())).thenThrow(LeadApiException.unavailable());
        mvc.perform(get("/leads").header("Authorization","Bearer CRM_ADMIN"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CRM_DATA_UNAVAILABLE"));
    }
    @Test void disabledWritesDoNotContactIdentity() throws Exception {
        doThrow(LeadApiException.unavailable()).when(repository).assertWritesEnabled();
        mvc.perform(post("/leads").header("Authorization","Bearer CRM_ADMIN").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isServiceUnavailable());
        verifyNoInteractions(vendors); verify(repository,never()).insert(any());
    }
}
