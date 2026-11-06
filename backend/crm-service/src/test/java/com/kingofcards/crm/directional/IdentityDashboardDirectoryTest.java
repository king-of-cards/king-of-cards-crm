package com.kingofcards.crm.directional;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdentityDashboardDirectoryTest {
    private static final String BASE = "http://identity-service";
    private final UUID vendorId = UUID.fromString("c78f6f86-4c9f-47eb-9582-cb87b4b919c2");

    @Test void adminVendorNameComesFromTheIdentityAdminRecord() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/admin/vendors/accounts/" + vendorId))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt"))
            .andRespond(withSuccess(record(vendorId, "Vendor from Identity"), MediaType.APPLICATION_JSON));

        var vendor = new IdentityDashboardDirectory(builder.build(), BASE).vendor(vendorId, true, "admin-jwt");

        assertThat(vendor.displayName()).isEqualTo("Vendor from Identity");
        server.verify();
    }

    @Test void vendorNameComesFromTheAuthenticatedSelfProfileAndSubjectMustMatch() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/auth/vendor-profile"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer vendor-jwt"))
            .andRespond(withSuccess(record(vendorId, "Authenticated vendor"), MediaType.APPLICATION_JSON));

        var vendor = new IdentityDashboardDirectory(builder.build(), BASE).vendor(vendorId, false, "vendor-jwt");

        assertThat(vendor.subjectId()).isEqualTo(vendorId);
        assertThat(vendor.displayName()).isEqualTo("Authenticated vendor");
        server.verify();

        var mismatchBuilder = RestClient.builder();
        var mismatchServer = MockRestServiceServer.bindTo(mismatchBuilder).build();
        UUID anotherVendor = UUID.randomUUID();
        mismatchServer.expect(requestTo(BASE + "/auth/vendor-profile"))
            .andRespond(withSuccess(record(anotherVendor, "Do not accept this name"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new IdentityDashboardDirectory(mismatchBuilder.build(), BASE)
            .vendor(vendorId, false, "vendor-jwt"))
            .hasMessageContaining("Vendor was not found");
        mismatchServer.verify();
    }

    private static String record(UUID subject, String name) {
        return "{\"subjectId\":\"" + subject + "\",\"displayName\":\"" + name
            + "\",\"enabled\":true,\"roles\":[\"CRM_VENDOR\"]}";
    }
    @Test void absentSelfProfileEndpointIsACompatibilityFailureNotAMissingVendor() {
        for(boolean admin:java.util.List.of(false,true)){
            var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(BASE+(admin?"/admin/vendors/accounts/"+vendorId:"/auth/vendor-profile")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound());
            assertThatThrownBy(()->new IdentityDashboardDirectory(builder.build(),BASE).vendor(vendorId,admin,"offline-token"))
                .isInstanceOfSatisfying(com.kingofcards.crm.exception.LeadApiException.class,error->{assertThat(error.code()).isEqualTo(admin?"VENDOR_NOT_FOUND":"IDENTITY_VENDOR_PROFILE_UNAVAILABLE");assertThat(error.status().value()).isEqualTo(admin?404:503);});
            server.verify();
        }
    }
}
