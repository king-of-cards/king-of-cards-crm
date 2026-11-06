package com.kingofcards.identity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.controller.AdminPasswordSetupController;
import com.kingofcards.identity.service.*;
import com.kingofcards.identity.exception.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminPasswordSetupController.class)
@Import({SecurityConfig.class, com.kingofcards.identity.config.TestAccountStatusConfiguration.class,AdminPasswordSetupService.class,ApiExceptionHandler.class,AdminPasswordSetupControllerTest.Beans.class})
class AdminPasswordSetupControllerTest {
    @Autowired MockMvc mvc;
    @Test void anonymousSetupIsTokenProtectedAndUnverifiedMutationsStillFailClosed() throws Exception {
        mvc.perform(post("/auth/admin-password-setup").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/admin-password-setup").contentType("application/json").content("{\"token\":\""+"a".repeat(43)+"\",\"password\":\"synthetic-long-password\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ADMIN_MUTATIONS_UNVERIFIED"));
    }
    @TestConfiguration static class Beans {
        @Bean JwtDecoder jwtDecoder(){return mock(JwtDecoder.class);}
        @Bean AdminMutationStore adminMutations(){return new UnverifiedAdminMutationStore();}
        @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder(4);}
    }
}
