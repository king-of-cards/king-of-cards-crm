package com.kingofcards.identity.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.kingofcards.identity.dto.VendorDisplayNameRequest;
import com.kingofcards.identity.dto.VendorPageResponse;
import com.kingofcards.identity.dto.VendorResponse;
import com.kingofcards.identity.service.VendorDirectoryService;

@RestController
@RequestMapping("/admin/vendors")
@PreAuthorize("hasRole('CRM_ADMIN')")
public class VendorAdminController {
    private final VendorDirectoryService vendors;
    public VendorAdminController(VendorDirectoryService vendors) { this.vendors = vendors; }

    @GetMapping
    public ResponseEntity<VendorPageResponse> list(@RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vendors.list(page, size));
    }

    @GetMapping("/{subjectId}")
    public ResponseEntity<VendorResponse> get(@PathVariable("subjectId") UUID subjectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vendors.get(subjectId));
    }

    @PatchMapping("/{subjectId}/display-name")
    public ResponseEntity<VendorResponse> provision(@PathVariable("subjectId") UUID subjectId,
            @Valid @RequestBody VendorDisplayNameRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(vendors.provisionDisplayName(subjectId, request.displayName()));
    }
}
