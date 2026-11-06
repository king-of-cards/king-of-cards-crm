package com.kingofcards.crm.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import com.kingofcards.crm.dto.*;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.*;
import com.kingofcards.crm.repository.LeadRepository;

@Service
public class LeadService {
    private final LeadRepository repository;
    private final VendorDirectory vendors;
    private final Clock clock;
    public LeadService(LeadRepository repository, VendorDirectory vendors, Clock clock) {
        this.repository = repository; this.vendors = vendors; this.clock = clock;
    }

    @PreAuthorize("hasAnyRole('CRM_ADMIN','CRM_VENDOR')")
    public LeadPageResponse list(LeadSearch search, Authentication authentication) {
        Actor actor = actor(authentication, false);
        if (!actor.admin() && search.vendorSubjectId() != null) throw new AccessDeniedException("Vendor filter is administrator-only.");
        if (!actor.admin()) vendors.requireSelfAssignable(actor.subject(),((JwtAuthenticationToken)authentication).getToken().getTokenValue());
        var records = repository.search(search, actor.admin() ? null : actor.subject());
        if (!actor.admin() && records.stream().anyMatch(row -> !actor.subject().equals(row.vendorSubjectId()))) {
            throw LeadApiException.unavailable();
        }
        return new LeadPageResponse(records.stream().limit(search.size()).map(LeadResponse::from).toList(),
            search.page(), search.size(), records.size() > search.size());
    }

    @PreAuthorize("hasAnyRole('CRM_ADMIN','CRM_VENDOR')")
    public LeadResponse get(UUID id, Authentication authentication) {
        Actor actor = actor(authentication, false);
        if (!actor.admin()) vendors.requireSelfAssignable(actor.subject(),((JwtAuthenticationToken)authentication).getToken().getTokenValue());
        var lead = repository.find(id, actor.admin() ? null : actor.subject())
            .filter(row -> row.leadId().equals(id) && (actor.admin() || row.vendorSubjectId().equals(actor.subject())))
            .orElseThrow(() -> new LeadApiException(HttpStatus.NOT_FOUND, "LEAD_NOT_FOUND", "Lead was not found."));
        return LeadResponse.from(lead);
    }

    @PreAuthorize("hasAnyRole('CRM_ADMIN','CRM_VENDOR')")
    public LeadResponse create(CreateLeadRequest request, Authentication authentication) {
        Actor actor = actor(authentication, false);
        if (request == null || !valid(request.name(), 120) || !valid(request.service(), 80)
                || !valid(request.source(), 120) || request.stage() == null
                || request.phone() == null || request.phone().length() > 40) throw LeadApiException.badRequest("Required lead fields are invalid.");
        String phone = request.phone().replaceAll("[\\s().-]", "");
        if (!phone.matches("\\+[1-9][0-9]{7,14}")) throw LeadApiException.badRequest("Phone must include an international country code and 8 to 15 digits.");
        UUID owner;
        String token=((JwtAuthenticationToken) authentication).getToken().getTokenValue();
        if (actor.admin()) {
            if (request.vendorSubjectId()==null) throw LeadApiException.badRequest("Administrator must select a vendor.");
            owner=request.vendorSubjectId();
        } else {
            if (request.vendorSubjectId()!=null) throw new AccessDeniedException("Vendor ownership is assigned from the verified JWT.");
            owner=actor.subject();
        }
        repository.assertWritesEnabled();
        if (actor.admin()) vendors.requireAssignable(owner,token);
        else vendors.requireSelfAssignable(owner,token);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var lead = new CrmLeadRecord(UUID.randomUUID(), request.name(), phone, request.service(), request.stage(),
            request.source(), owner, actor.subject(), now, now);
        repository.insert(lead);
        return LeadResponse.from(lead);
    }

    private static boolean valid(String value, int length) { return value != null && !value.isBlank() && value.length() <= length; }
    private static Actor actor(Authentication authentication, boolean requireAdmin) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()) throw new AccessDeniedException("Verified JWT required.");
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CRM_ADMIN"));
        boolean vendor = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CRM_VENDOR"));
        if (!admin && (requireAdmin || !vendor)) throw new AccessDeniedException("Lead access is not allowed.");
        try {
            String subject = jwt.getToken().getSubject();
            UUID id = UUID.fromString(subject);
            if (!id.toString().equals(subject)) throw new IllegalArgumentException();
            return new Actor(id, admin);
        } catch (IllegalArgumentException | NullPointerException exception) { throw new AccessDeniedException("Valid Auth subject required."); }
    }
    private record Actor(UUID subject, boolean admin) {}
}
