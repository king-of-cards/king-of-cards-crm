package com.kingofcards.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.controller.HealthController;

@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, HealthControllerTest.TestJwtConfiguration.class})
class HealthControllerTest {
    @Autowired MockMvc mvc;
    @Test
    void reportsServiceHealth() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("identity-service"))
            .andExpect(jsonPath("$.status").value("UP"));
    }
    @TestConfiguration
    static class TestJwtConfiguration {
        @Bean JwtDecoder jwtDecoder() {
            return token -> { throw new BadJwtException("No test token expected."); };
        }
    }
}