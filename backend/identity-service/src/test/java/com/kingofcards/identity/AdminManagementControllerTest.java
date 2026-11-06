package com.kingofcards.identity;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.controller.AdminManagementController;
import com.kingofcards.identity.dto.*;
import com.kingofcards.identity.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminManagementController.class)
@Import({SecurityConfig.class, com.kingofcards.identity.config.TestAccountStatusConfiguration.class,AdminManagementControllerTest.Beans.class})
class AdminManagementControllerTest {
    @Autowired MockMvc mvc;@Autowired JwtDecoder decoder;
    @Autowired VendorAccountDirectoryService proxy;VendorAccountDirectoryService directory;
    @Autowired AdminManagementService operations;
    static final UUID ID=UUID.fromString("00000000-0000-4000-8000-000000000001");
    @BeforeEach void setup(){directory=AopTestUtils.getUltimateTargetObject(proxy);reset(directory,decoder);
        for(String role:List.of("CRM_ADMIN","CRM_VENDOR","CRM_MANAGER"))when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256").subject(ID.toString()).claim("roles",List.of(role)).build());}
    @Test void administratorCanReadSafeAccountDetails() throws Exception {
        var account=new VendorResponse(ID,"Admin",true,List.of("CRM_ADMIN"));
        when(directory.listAdministrators(0,20)).thenReturn(new VendorPageResponse(List.of(account),0,20,1,1));
        when(directory.getAdministrator(ID)).thenReturn(account);
        mvc.perform(get("/admin/administrators").header("Authorization","Bearer CRM_ADMIN")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].roles[0]").value("CRM_ADMIN"));
        mvc.perform(get("/admin/administrators/"+ID).header("Authorization","Bearer CRM_ADMIN")).andExpect(status().isOk()).andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings={"CRM_VENDOR","CRM_MANAGER"})
    void nonAdminsCannotReadInviteOrChangeStatus(String role) throws Exception {
        mvc.perform(get("/admin/administrators").header("Authorization","Bearer "+role)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/administrators/invitations").header("Authorization","Bearer "+role).contentType("application/json").content("{\"email\":\"new@example.test\",\"name\":\"New\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/admin/administrators/"+ID+"/enabled").header("Authorization","Bearer "+role).contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isForbidden());
    }
    @Test void anonymousAccessIsUnauthorized() throws Exception {mvc.perform(get("/admin/administrators")).andExpect(status().isUnauthorized());}
    @TestConfiguration static class Beans {
        @Bean JwtDecoder jwtDecoder(){return mock(JwtDecoder.class);}
        @Bean VendorAccountDirectoryService directory(){return mock(VendorAccountDirectoryService.class);}
        @Bean AdminManagementService operations(){return mock(AdminManagementService.class);}
    }
}
