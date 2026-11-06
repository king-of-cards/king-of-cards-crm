package com.kingofcards.identity.controller;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.kingofcards.identity.dto.*;
import com.kingofcards.identity.service.VendorProvisioningService;

@RestController
@RequestMapping("/admin/vendors")
@PreAuthorize("hasRole('CRM_ADMIN')")
public class VendorProvisioningController {
    private final VendorProvisioningService vendors;
    public VendorProvisioningController(VendorProvisioningService vendors) { this.vendors = vendors; }

    @PostMapping
    public ResponseEntity<VendorResponse> create(@Valid @RequestBody CreateVendorRequest request) {
        var vendor = vendors.create(request.email(), request.password(), request.displayName());
        return ResponseEntity.created(URI.create("/api/identity/admin/vendors/" + vendor.subjectId()))
            .cacheControl(CacheControl.noStore()).body(vendor);
    }

    @PatchMapping("/{subjectId}/enabled")
    public ResponseEntity<VendorResponse> enabled(@PathVariable("subjectId") UUID subjectId,
            @Valid @RequestBody VendorEnabledRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vendors.setEnabled(subjectId, request.enabled()));
    }
}
