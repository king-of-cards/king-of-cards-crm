package com.kingofcards.crm;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.kingofcards.crm.config.SecurityConfig;
import com.kingofcards.crm.controller.LeadImportController;
import com.kingofcards.crm.service.LeadImportService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LeadImportController.class)
@Import({SecurityConfig.class, com.kingofcards.crm.config.TestAccountStatusConfiguration.class,LeadImportControllerTest.Beans.class})
class LeadImportControllerTest {
    @Autowired MockMvc mvc;@Autowired JwtDecoder decoder;
    @BeforeEach void setup(){for(String role:List.of("CRM_ADMIN","CRM_VENDOR"))when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256").subject(UUID.randomUUID().toString()).claim("roles",List.of(role)).build());}
    @Test void onlyAdministratorCanDownloadTemplate() throws Exception {
        mvc.perform(get("/leads/import/template")).andExpect(status().isUnauthorized());
        mvc.perform(get("/leads/import/template").header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isForbidden());
        mvc.perform(get("/leads/import/template").header("Authorization","Bearer CRM_ADMIN")).andExpect(status().isOk()).andExpect(content().string("name,phone,service,stage,source,vendorSubjectId\r\n"));
    }
    @Test void vendorCannotValidateOrImportAnotherVendorsFile() throws Exception {
        for(String path:List.of("/leads/import/validate","/leads/import"))mvc.perform(multipart(path).file(new MockMultipartFile("file","x.csv","text/csv","x".getBytes())).header("Authorization","Bearer CRM_VENDOR")).andExpect(status().isForbidden());
    }
    @TestConfiguration static class Beans {
        @Bean JwtDecoder jwtDecoder(){return mock(JwtDecoder.class);}
        @Bean LeadImportService leadImportService(){return mock(LeadImportService.class);}
    }
}
