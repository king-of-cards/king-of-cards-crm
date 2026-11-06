package com.kingofcards.identity.controller;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.kingofcards.identity.dto.*;
import com.kingofcards.identity.service.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/administrators")
@PreAuthorize("hasRole('CRM_ADMIN')")
public class AdminManagementController {
    private final VendorAccountDirectoryService directory;
    private final AdminManagementService operations;
    public AdminManagementController(VendorAccountDirectoryService directory,AdminManagementService operations) {
        this.directory=directory;this.operations=operations;
    }
    @GetMapping public ResponseEntity<VendorPageResponse> list(@RequestParam(name="page",defaultValue="0") int page,
            @RequestParam(name="size",defaultValue="20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.listAdministrators(page,size));
    }
    @GetMapping("/{id}") public ResponseEntity<VendorResponse> get(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.getAdministrator(id));
    }
    @PostMapping("/invitations") public ResponseEntity<AdminManagementService.Invitation> invite(
            @Valid @RequestBody InviteRequest request,Authentication authentication) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(operations.invite(request.email(),request.name(),authentication));
    }
    @PatchMapping("/{id}/enabled") public ResponseEntity<Void> enabled(@PathVariable("id") UUID id,
            @Valid @RequestBody VendorEnabledRequest request,Authentication authentication) {
        operations.setEnabled(id,request.enabled(),authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    public record InviteRequest(@NotBlank @Email @Size(max=254) String email,@NotBlank @Size(max=120) String name) {}
}
