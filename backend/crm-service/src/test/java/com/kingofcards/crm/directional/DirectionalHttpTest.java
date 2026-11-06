package com.kingofcards.crm.directional;
import java.time.*;
import java.util.*;
import com.kingofcards.crm.config.*;
import com.kingofcards.crm.directional.DirectionalModels.Vendor;
import com.kingofcards.crm.exception.LeadExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller/service/security pipeline with explicit transient test persistence;
 * JWT signature handling is independently covered by the existing decoder tests. */
@WebMvcTest(DirectionalController.class)
@Import({SecurityConfig.class,TestAccountStatusConfiguration.class,DirectionalService.class,LeadExceptionHandler.class,DirectionalHttpTest.Fixture.class})
class DirectionalHttpTest {
    static final UUID VENDOR=UUID.fromString("00000000-0000-4000-8000-000000000001"), ADMIN=UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final String BASE="/lead-dashboard/vendors/"+VENDOR+"/";
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @TestConfiguration static class Fixture {
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean DirectionalRepository repository(Clock clock){return new LocalDirectionalRepository(clock);}
        @Bean DirectionalProperties properties(){return new DirectionalProperties(true,false,100,200);}
        @Bean DashboardIdentity identity(){return (id,admin,token)->new Vendor(id,"Offline Vendor",true,List.of("CRM_VENDOR"));}
    }
    @BeforeEach void jwt(){for(String role:List.of("CRM_ADMIN","CRM_VENDOR","CRM_MANAGER"))when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256").subject((role.equals("CRM_VENDOR")?VENDOR:ADMIN).toString()).claim("roles",List.of(role)).build());when(decoder.decode("invalid")).thenThrow(new BadJwtException("invalid"));}
    String body(){return """
      {"operationId":"%s","submittedAt":"%s","expectedRevision":0,
       "draft":{"fields":{"Name":"Offline HTTP fixture","Phone":"001234567890"},
       "metadata":{"service":"Existing service","stage":"CONTACTED","source":"Existing source"}}}
      """.formatted(UUID.randomUUID(),Instant.now());}
    @Test void missingAndInvalidJwtRejected() throws Exception {
        mvc.perform(get(BASE+"KOC_TO_VENDOR/leads")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE+"KOC_TO_VENDOR/leads").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
    }
    @Test void reportEndpointIsReadOnlyAuthorizedAndNeverCached() throws Exception {
        mvc.perform(get("/lead-dashboard/vendors/"+VENDOR+"/report")).andExpect(status().isUnauthorized());
        for(String role:List.of("CRM_ADMIN","CRM_VENDOR"))mvc.perform(get("/lead-dashboard/vendors/"+VENDOR+"/report").header("Authorization","Bearer "+role)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.vendorSubjectId").value(VENDOR.toString())).andExpect(jsonPath("$.directions.length()").value(2));
        mvc.perform(get("/lead-dashboard/vendors/"+ADMIN+"/report").header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isForbidden());
        mvc.perform(get("/lead-dashboard/vendors/"+VENDOR+"/report").header("Authorization","Bearer CRM_MANAGER")).andExpect(status().isForbidden());
    }
    @Test void adminCreateListAndVendorRead() throws Exception {
        mvc.perform(post(BASE+"KOC_TO_VENDOR/leads").header("Authorization","Bearer CRM_ADMIN").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("COMMITTED"));
        mvc.perform(get(BASE+"KOC_TO_VENDOR/leads").header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].fields.Phone").value("001234567890")).andExpect(jsonPath("$.items[0].vendorName").value("Offline Vendor")).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void incorrectDirectionAndCrossVendorAre403() throws Exception {
        mvc.perform(post(BASE+"KOC_TO_VENDOR/leads").header("Authorization","Bearer CRM_VENDOR").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"VENDOR_TO_KOC/leads").header("Authorization","Bearer CRM_ADMIN").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isForbidden());
        mvc.perform(get(BASE.replace(VENDOR.toString(),ADMIN.toString())+"VENDOR_TO_KOC/export").header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"VENDOR_TO_KOC/leads").header("Authorization","Bearer CRM_MANAGER")).andExpect(status().isForbidden());
    }
    @Test void vendorCreateAndValidationErrors() throws Exception {
        mvc.perform(post(BASE+"VENDOR_TO_KOC/leads").header("Authorization","Bearer CRM_VENDOR").contentType(MediaType.APPLICATION_JSON).content(body())).andExpect(status().isCreated());
        mvc.perform(post(BASE+"VENDOR_TO_KOC/leads").header("Authorization","Bearer CRM_VENDOR").contentType(MediaType.APPLICATION_JSON).content(body().replace("001234567890","bad"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LEAD_REQUEST"));
        mvc.perform(get(BASE+"KOC_TO_VENDOR/leads?size=1000").header("Authorization","Bearer CRM_ADMIN")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE+"KOC_TO_VENDOR/leads/not-a-uuid").header("Authorization","Bearer CRM_ADMIN")).andExpect(status().isBadRequest());
    }
    @Test void serviceOnlyJsonAndMultipartDoNotRequireStageOrSource() throws Exception {
        // Construct the new actual client contract without a trailing JSON comma.
        String json="{\"operationId\":\""+UUID.randomUUID()+"\",\"submittedAt\":\""+Instant.now()+"\",\"expectedRevision\":0,\"draft\":{\"fields\":{\"Name\":\"Service only fixture\",\"Phone\":\"001234567892\"},\"metadata\":{\"service\":\"Selected service\"}}}";
        mvc.perform(post(BASE+"KOC_TO_VENDOR/leads").header("Authorization","Bearer CRM_ADMIN").contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated());
        mvc.perform(get(BASE+"KOC_TO_VENDOR/leads?q=Service%20only").header("Authorization","Bearer CRM_ADMIN")).andExpect(status().isOk());
        var file=new org.springframework.mock.web.MockMultipartFile("file","sheet.csv","text/csv","NAME,PHONE,STATUS\nMultipart,001234567891,Converted\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var metadata=new org.springframework.mock.web.MockMultipartFile("metadata","","application/json","{\"service\":\"Selected service\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart(BASE+"VENDOR_TO_KOC/imports/preview").file(file).file(metadata).header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].errors").isEmpty());
    }
}
