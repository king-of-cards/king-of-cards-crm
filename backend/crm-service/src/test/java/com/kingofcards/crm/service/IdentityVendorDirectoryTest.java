package com.kingofcards.crm.service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.kingofcards.crm.exception.LeadApiException;

class IdentityVendorDirectoryTest {
    @Test void vendorSelfLookupUsesExistingMeAndVerifiedToken() {
        server.expect(requestTo("http://identity-service/auth/me")).andExpect(header("Authorization","Bearer synthetic-vendor"))
            .andRespond(withSuccess("{\"id\":\""+id+"\",\"email\":\"vendor@example.test\",\"roles\":[\"CRM_VENDOR\"],\"futureField\":true}",MediaType.APPLICATION_JSON));
        directory.requireSelfAssignable(id,"synthetic-vendor");server.verify();
    }
    @ParameterizedTest @ValueSource(ints={401,403,500,503})
    void vendorSelfLookupFailsClosedOnDisabledAccountOrUnavailableIdentity(int status) {
        server.expect(requestTo("http://identity-service/auth/me")).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThatThrownBy(()->directory.requireSelfAssignable(id,"synthetic-vendor")).isInstanceOf(LeadApiException.class);
        server.verify();
    }
    @Test void vendorSelfLookupRejectsMismatchedIdentity() {
        server.expect(anything()).andRespond(withSuccess("{\"id\":\""+UUID.randomUUID()+"\",\"roles\":[\"CRM_VENDOR\"]}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->directory.requireSelfAssignable(id,"synthetic-vendor")).isInstanceOf(LeadApiException.class);server.verify();
    }
    final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000002");
    final RestClient.Builder builder=RestClient.builder();
    final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    final VendorDirectory directory=new IdentityVendorDirectory(builder.build());
    private String response() { return """
        {"subjectId":"%s","displayName":"Synthetic Vendor","enabled":true,"roles":["CRM_VENDOR"]}
        """.formatted(id); }
    @Test void relaysAdminTokenToFixedAuthoritativeEndpoint() {
        server.expect(requestTo("http://identity-service/admin/vendors/"+id)).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization","Bearer synthetic-admin"))
            .andRespond(withSuccess(response(),MediaType.APPLICATION_JSON));
        directory.requireAssignable(id,"synthetic-admin"); server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"disabled","nonvendor","blank","mismatch"})
    void rejectsIneligibleOrMismatchedResponses(String scenario) {
        String json=switch(scenario) {
            case "disabled" -> response().replace("true","false");
            case "nonvendor" -> response().replace("CRM_VENDOR","CRM_AGENT");
            case "blank" -> response().replace("Synthetic Vendor"," ");
            default -> response().replace(id.toString(),UUID.randomUUID().toString());
        };
        server.expect(anything()).andRespond(withSuccess(json,MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> directory.requireAssignable(id,"synthetic-admin"))
            .isInstanceOfSatisfying(LeadApiException.class,e -> assertThat(e.status().value()).isEqualTo(scenario.equals("mismatch")?503:422));
        server.verify();
    }
    @ParameterizedTest @ValueSource(ints={401,403,404,409,500,503})
    void sanitizesUpstreamFailures(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)).body("synthetic-secret-provider-detail"));
        assertThatThrownBy(() -> directory.requireAssignable(id,"synthetic-admin"))
            .hasMessageNotContaining("synthetic-secret-provider-detail").hasNoCause()
            .isInstanceOfSatisfying(LeadApiException.class,e -> assertThat(e.status().value()).isEqualTo(status==404||status==409?422:503));
        server.verify();
    }
}
