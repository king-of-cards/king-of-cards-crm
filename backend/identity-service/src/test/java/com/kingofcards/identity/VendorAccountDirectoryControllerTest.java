package com.kingofcards.identity;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.controller.VendorAccountDirectoryController;
import com.kingofcards.identity.controller.VendorAdminController;
import com.kingofcards.identity.dto.*;
import com.kingofcards.identity.service.VendorAccountDirectoryService;
import com.kingofcards.identity.service.VendorDirectoryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;

@WebMvcTest({VendorAccountDirectoryController.class,VendorAdminController.class})
@Import({SecurityConfig.class, com.kingofcards.identity.config.TestAccountStatusConfiguration.class,VendorAccountDirectoryControllerTest.Beans.class})
class VendorAccountDirectoryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtDecoder decoder;
    @Autowired VendorAccountDirectoryService directoryProxy;
    VendorAccountDirectoryService directory;
    static final UUID ID=UUID.fromString("00000000-0000-4000-8000-000000000001");
    @BeforeEach void setup() {
        directory=AopTestUtils.getUltimateTargetObject(directoryProxy);
        reset(decoder,directory);
        for(String role:List.of("CRM_ADMIN","CRM_VENDOR","CRM_MANAGER","CRM_AGENT"))
            when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role).header("alg","RS256")
                .subject(UUID.randomUUID().toString()).claim("roles",List.of(role)).build());
        when(decoder.decode("invalid")).thenThrow(new BadJwtException("Invalid synthetic token"));
    }
    @Test void adminListsAndViewsBlockedAccountsWithoutSecrets() throws Exception {
        var vendor=new VendorResponse(ID,"Blocked Vendor",false,List.of("CRM_VENDOR"));
        when(directory.list(0,20)).thenReturn(new VendorPageResponse(List.of(vendor),0,20,1,1));
        when(directory.get(ID)).thenReturn(vendor);
        mvc.perform(get("/admin/vendors/accounts").header("Authorization","Bearer CRM_ADMIN"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.items[0].enabled").value(false))
            .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
        mvc.perform(get("/admin/vendors/accounts/"+ID).header("Authorization","Bearer CRM_ADMIN"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.subjectId").value(ID.toString()))
            .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.email").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings={"CRM_VENDOR","CRM_MANAGER","CRM_AGENT"})
    void nonAdminsCannotListOrViewAnotherVendor(String role) throws Exception {
        for(String path:List.of("/admin/vendors/accounts","/admin/vendors/accounts/"+ID))
            mvc.perform(get(path).header("Authorization","Bearer "+role)).andExpect(status().isForbidden());
        verifyNoInteractions(directory);
    }
    @Test void missingAndInvalidTokensAreUnauthorized() throws Exception {
        mvc.perform(get("/admin/vendors/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/vendors/accounts").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
        verifyNoInteractions(directory);
    }
    @TestConfiguration static class Beans {
        @Bean JwtDecoder jwtDecoder(){return mock(JwtDecoder.class);}
        @Bean VendorAccountDirectoryService vendorAccountDirectoryService(){return mock(VendorAccountDirectoryService.class);}
        @Bean VendorDirectoryService vendorDirectoryService(){return mock(VendorDirectoryService.class);}
    }
}
