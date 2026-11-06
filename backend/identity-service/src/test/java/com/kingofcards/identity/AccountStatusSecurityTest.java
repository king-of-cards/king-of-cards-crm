package com.kingofcards.identity;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.*;
import com.kingofcards.identity.config.SecurityConfig;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import com.kingofcards.identity.repository.IdentityUserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

/** Uses the real account verifier/filter, unlike synthetic fixtures in other MVC slices. */
@WebMvcTest(AccountStatusSecurityTest.Probe.class)
@Import({SecurityConfig.class,AccountStatusSecurityTest.Beans.class,AccountStatusSecurityTest.Probe.class})
class AccountStatusSecurityTest {
    @Autowired MockMvc mvc;@Autowired JwtDecoder decoder;@Autowired IdentityUserRepository users;
    final UUID id=UUID.randomUUID();final Instant now=Instant.parse("2026-11-01T00:00:00Z");
    @BeforeEach void setup(){reset(users,decoder);when(decoder.decode("synthetic")).thenReturn(Jwt.withTokenValue("synthetic").header("alg","RS256").subject(id.toString()).expiresAt(now.plusSeconds(999999999)).claim("roles",List.of("CRM_ADMIN")).build());}
    IdentityUser account(boolean enabled,String role){return IdentityUser.restore(id,"synthetic@example.test","Synthetic","synthetic-hash",role,enabled,now,now);}
    @Test void previouslyIssuedTokenStopsWorkingImmediatelyAfterAccountBlock() throws Exception {
        when(users.findById(id)).thenReturn(Optional.of(account(true,"CRM_ADMIN")));
        mvc.perform(get("/protected-probe").header("Authorization","Bearer synthetic")).andExpect(status().isOk());
        when(users.findById(id)).thenReturn(Optional.of(account(false,"CRM_ADMIN")));
        mvc.perform(get("/protected-probe").header("Authorization","Bearer synthetic")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_ACCESS_REVOKED"));
    }
    @Test void roleChangesAndUnknownAccountsRejectOldRoleClaims() throws Exception {
        when(users.findById(id)).thenReturn(Optional.of(account(true,"CRM_VENDOR")));
        mvc.perform(get("/protected-probe").header("Authorization","Bearer synthetic")).andExpect(status().isForbidden());
        when(users.findById(id)).thenReturn(Optional.empty());
        mvc.perform(get("/protected-probe").header("Authorization","Bearer synthetic")).andExpect(status().isForbidden());
    }
    @Test void persistenceFailureFailsClosedAndDoesNotExposeCause() throws Exception {
        when(users.findById(id)).thenThrow(new IdentityPersistenceException("synthetic-private-error"));
        mvc.perform(get("/protected-probe").header("Authorization","Bearer synthetic")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("IDENTITY_ACCOUNT_CHECK_UNAVAILABLE"));
        mvc.perform(get("/protected-probe")).andExpect(status().isUnauthorized());
    }
    @RestController static class Probe {@GetMapping("/protected-probe") public String protectedProbe(){return "OK";}}
    @TestConfiguration static class Beans {
        @Bean JwtDecoder jwtDecoder(){return mock(JwtDecoder.class);}
        @Bean IdentityUserRepository users(){return mock(IdentityUserRepository.class);}
    }
}
