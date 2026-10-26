package com.kingofcards.identity.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.repository.IdentityRoleRepository;
import com.kingofcards.identity.repository.IdentityUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class AdminBootstrapService {
    private static final String ADMIN_ROLE = "CRM_ADMIN";
    private final IdentityUserRepository users;
    private final IdentityRoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrapService(IdentityUserRepository users, IdentityRoleRepository roles,
                                 PasswordEncoder passwordEncoder) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void createFirstAdministrator(String email, String password) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password) ||
            email.length() > 254 || password.length() > 72) {
            throw new IllegalArgumentException("A valid bootstrap email and password are required.");
        }
        var adminRole = roles.findByCodeForUpdate(ADMIN_ROLE)
            .orElseThrow(() -> new IllegalStateException("CRM_ADMIN role is missing; apply Identity migrations first."));
        if (users.count() != 0) {
            throw new IllegalStateException("Administrator bootstrap is allowed only for an empty user store.");
        }
        String normalized = AuthenticationService.normalizeEmail(email);
        if (users.findByNormalizedEmail(normalized).isPresent()) {
            throw new IllegalStateException("The bootstrap account already exists.");
        }
        IdentityUser admin = new IdentityUser(email.trim(), normalized, passwordEncoder.encode(password), true);
        admin.addRole(adminRole);
        users.save(admin);
    }
}