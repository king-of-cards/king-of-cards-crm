package com.kingofcards.crm.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.kingofcards.crm.exception.LeadApiException;

/** Relays only the verified administrator token to the fixed, discovery-resolved Identity endpoint. */
public final class IdentityVendorDirectory implements VendorDirectory {
    private final RestClient client;
    private final String base;
    public IdentityVendorDirectory(RestClient client) { this(client,"http://identity-service"); }
    public IdentityVendorDirectory(RestClient client,String base) {
        if(!base.equals("http://identity-service") && !base.equals("http://127.0.0.1:18081"))throw new IllegalArgumentException("Identity endpoint is not allowlisted.");
        this.client=client;this.base=base;
    }

    @Override public void requireAssignable(UUID subjectId, String token) {
        try {
            var vendor = client.get().uri(base+"/admin/vendors/{subjectId}", subjectId)
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(VendorIdentity.class);
            if (vendor == null || !subjectId.equals(vendor.subjectId())) throw unavailable();
            if (!vendor.enabled() || vendor.roles() == null || !vendor.roles().contains("CRM_VENDOR")
                    || vendor.displayName() == null || vendor.displayName().isBlank()) throw invalidVendor();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 409) throw invalidVendor();
            throw unavailable();
        } catch (RestClientException | IllegalStateException exception) { throw unavailable(); }
    }
    @Override public void requireSelfAssignable(UUID subject, String token) {
        try {
            var current=client.get().uri(base+"/auth/me")
                .headers(headers -> headers.setBearerAuth(token)).retrieve().body(CurrentVendor.class);
            if (current==null || !subject.equals(current.id())) throw unavailable();
            if (current.roles()==null || !current.roles().equals(List.of("CRM_VENDOR"))) throw inactiveVendor();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value()==401 || exception.getStatusCode().value()==403) throw inactiveVendor();
            throw unavailable();
        } catch (RestClientException | IllegalStateException exception) { throw unavailable(); }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record CurrentVendor(UUID id, List<String> roles) {}
    private static LeadApiException inactiveVendor() {
        return new LeadApiException(HttpStatus.FORBIDDEN,"VENDOR_ACCOUNT_INELIGIBLE","Vendor account is not active.");
    }
    private static LeadApiException invalidVendor() {
        return new LeadApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_VENDOR_ASSIGNMENT", "Selected vendor is not assignable.");
    }
    private static LeadApiException unavailable() {
        return new LeadApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_LOOKUP_UNAVAILABLE", "Vendor identity validation is unavailable.");
    }
    public record VendorIdentity(UUID subjectId, String displayName, boolean enabled, List<String> roles) {}
}
