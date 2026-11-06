package com.kingofcards.crm.config;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.*;
import com.kingofcards.crm.exception.LeadApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class IdentityAccountStatusVerifierTest {
    final UUID id=UUID.randomUUID();
    final RestClient.Builder builder=RestClient.builder();
    final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    final IdentityAccountStatusVerifier verifier=new IdentityAccountStatusVerifier(builder.build());
    Jwt token(){return Jwt.withTokenValue("synthetic").header("alg","RS256").subject(id.toString()).claim("roles",List.of("CRM_ADMIN")).build();}
    @Test void previouslyIssuedAdminTokenIsRecheckedAndRejectedAfterBlock() {
        server.expect(requestTo("http://identity-service/auth/me")).andExpect(header("Authorization","Bearer synthetic"))
            .andRespond(withSuccess("{\"id\":\""+id+"\",\"roles\":[\"CRM_ADMIN\"]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://identity-service/auth/me")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        var token=token();assertDoesNotThrow(()->verifier.verify(token));assertThrows(AccessDeniedException.class,()->verifier.verify(token));server.verify();
    }
    @Test void changedRoleOrSubjectCannotUseOldClaims() {
        server.expect(anything()).andRespond(withSuccess("{\"id\":\""+id+"\",\"roles\":[\"CRM_VENDOR\"]}",MediaType.APPLICATION_JSON));
        assertThrows(AccessDeniedException.class,()->verifier.verify(token()));server.verify();
    }
    @Test void unavailableIdentityFailsClosed() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThrows(LeadApiException.class,()->verifier.verify(token()));server.verify();
    }
    @Test void noRoleTokenIsRejectedWithoutLookup() {
        var noRole=Jwt.withTokenValue("synthetic").header("alg","RS256").subject(id.toString()).build();
        assertThrows(AccessDeniedException.class,()->verifier.verify(noRole));server.verify();
    }
}
