package com.kingofcards.identity.service;

import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import com.kingofcards.identity.dto.VendorPageResponse;
import com.kingofcards.identity.dto.VendorResponse;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.VendorDirectoryException;
import com.kingofcards.identity.repository.IdentityUserRepository;

@Service
@PreAuthorize("hasRole('CRM_ADMIN')")

public class VendorDirectoryService {
    private final IdentityUserRepository users;

    public VendorDirectoryService(IdentityUserRepository users) { this.users = users; }

    public VendorPageResponse list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new VendorDirectoryException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                "Page must be nonnegative and size must be between 1 and 100.");
        }
        var vendors = users.findAssignableVendors(PageRequest.of(page, size, Sort.by("id").ascending()));
        return new VendorPageResponse(vendors.getContent().stream().map(this::eligibleResponse).toList(),
            vendors.getNumber(), vendors.getSize(), vendors.getTotalElements(), vendors.getTotalPages());
    }

    public VendorResponse get(UUID subjectId) { return eligibleResponse(enabledVendor(subjectId)); }

    public VendorResponse provisionDisplayName(UUID subjectId, String displayName) {
        IdentityUser vendor = enabledVendor(subjectId);
        try {
            vendor.setDisplayName(displayName);
        } catch (IllegalArgumentException exception) {
            throw new VendorDirectoryException(HttpStatus.BAD_REQUEST, "INVALID_DISPLAY_NAME",
                "Display name must contain 1 to 120 characters after trimming.");
        }
        return eligibleResponse(users.save(vendor));
    }

    private IdentityUser enabledVendor(UUID id) {
        return users.findById(id).filter(user -> user.isEnabled() && hasVendorRole(user))
            .orElseThrow(VendorDirectoryService::notFound);
    }

    private VendorResponse eligibleResponse(IdentityUser vendor) {
        if (!vendor.isEnabled() || !hasVendorRole(vendor)) throw notFound();
        String name = vendor.getDisplayName();
        if (name == null || name.isBlank()) {
            throw new VendorDirectoryException(HttpStatus.CONFLICT, "VENDOR_DISPLAY_NAME_REQUIRED",
                "Vendor display name must be explicitly provisioned by a CRM administrator.");
        }
        return new VendorResponse(vendor.getId(), name, true,
            vendor.getRoles().stream().map(role -> role.getCode()).sorted().toList());
    }

    private static boolean hasVendorRole(IdentityUser user) {
        return user.getRoles().stream().anyMatch(role -> "CRM_VENDOR".equals(role.getCode()));
    }

    private static VendorDirectoryException notFound() {
        return new VendorDirectoryException(HttpStatus.NOT_FOUND, "VENDOR_NOT_FOUND",
            "Enabled vendor account was not found.");
    }
}
