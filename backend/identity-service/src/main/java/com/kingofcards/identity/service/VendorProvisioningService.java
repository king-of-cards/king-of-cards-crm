package com.kingofcards.identity.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.kingofcards.identity.dto.VendorResponse;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.VendorDirectoryException;
import com.kingofcards.identity.repository.IdentityUserRepository;

@Service
@PreAuthorize("hasRole('CRM_ADMIN')")
public class VendorProvisioningService {
    private final IdentityUserRepository users;
    private final PasswordEncoder passwords;

    public VendorProvisioningService(IdentityUserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public VendorResponse create(String email, String password, String displayName) {
        if (email == null || email.isBlank() || email.length() > 254 || password == null
                || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw invalid();
        String normalized = AuthenticationService.normalizeEmail(email);
        // Validate the name before hashing; do not query for duplicates and then insert.
        var user = new IdentityUser(normalized, normalized, "", true);
        try { user.setDisplayName(displayName); } catch (IllegalArgumentException exception) { throw invalid(); }
        user = IdentityUser.restore(user.getId(), normalized, user.getDisplayName(), passwords.encode(password),
            "CRM_VENDOR", true, user.getCreatedAt(), user.getUpdatedAt());
        // The persistence contract owns distributed uniqueness. Production remains fail-closed.
        users.createUnique(user);
        return response(user);
    }

    public VendorResponse setEnabled(UUID subjectId, boolean enabled) {
        var user = users.findById(subjectId).filter(VendorProvisioningService::isVendor)
            .orElseThrow(() -> new VendorDirectoryException(HttpStatus.NOT_FOUND, "VENDOR_NOT_FOUND", "Vendor account was not found."));
        var replacement = IdentityUser.restore(user.getId(), user.getEmail(), user.getDisplayName(), user.getPasswordHash(),
            "CRM_VENDOR", enabled, user.getCreatedAt(), user.getUpdatedAt());
        // This must be an atomic version-checked update with session revocation, not a separate write.
        return response(users.save(replacement));
    }

    private static boolean isVendor(IdentityUser user) {
        return user.getRoles().size() == 1 && user.getRoles().stream().allMatch(role -> "CRM_VENDOR".equals(role.getCode()));
    }
    private static VendorResponse response(IdentityUser user) {
        return new VendorResponse(user.getId(), user.getDisplayName(), user.isEnabled(), java.util.List.of("CRM_VENDOR"));
    }
    private static VendorDirectoryException invalid() {
        return new VendorDirectoryException(HttpStatus.BAD_REQUEST, "INVALID_VENDOR", "Valid email, password and explicit display name are required.");
    }
}
