package com.kingofcards.identity.controller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.kingofcards.identity.config.AuthCookieSettings;
import com.kingofcards.identity.dto.*;
import com.kingofcards.identity.service.VendorPasswordSetupService;

@RestController
@org.springframework.context.annotation.Profile("legacy-vendor-setup")
public class VendorPasswordSetupController {
    private final VendorPasswordSetupService setup;
    private final AuthCookieSettings cookies;
    public VendorPasswordSetupController(VendorPasswordSetupService setup, AuthCookieSettings cookies) {
        this.setup=setup; this.cookies=cookies;
    }
    @PostMapping("/admin/vendors/invitations")
    @PreAuthorize("hasRole('CRM_ADMIN')")
    public ResponseEntity<VendorInvitationResponse> invite(@Valid @RequestBody VendorInvitationRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(setup.invite(request.email(),request.displayName()));
    }
    @PostMapping("/auth/vendor-password-setup")
    public ResponseEntity<Void> complete(@Valid @RequestBody VendorPasswordSetupRequest request, HttpServletRequest http) {
        String origin=http.getHeader("Origin");
        if (origin!=null && !origin.equals(cookies.allowedOrigin())) throw new AccessDeniedException("Request origin is not allowed.");
        setup.complete(request.setupToken(),request.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
