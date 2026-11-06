package com.kingofcards.identity.controller;

import java.util.UUID;
import com.kingofcards.identity.dto.*;
import com.kingofcards.identity.service.VendorAccountDirectoryService;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/vendors/accounts")
@PreAuthorize("hasRole('CRM_ADMIN')")
public class VendorAccountDirectoryController {
    private final VendorAccountDirectoryService vendors;
    public VendorAccountDirectoryController(VendorAccountDirectoryService vendors) { this.vendors = vendors; }

    @GetMapping
    public ResponseEntity<VendorPageResponse> list(@RequestParam(name="page", defaultValue="0") int page,
            @RequestParam(name="size", defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vendors.list(page, size));
    }

    @GetMapping("/{subjectId}")
    public ResponseEntity<VendorResponse> get(@PathVariable("subjectId") UUID subjectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(vendors.get(subjectId));
    }
}
