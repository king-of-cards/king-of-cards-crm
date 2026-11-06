package com.kingofcards.identity.service;

import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import com.kingofcards.identity.entity.IdentityRole;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.repository.IdentityUserRepository;

@Service
public class AdminBootstrapService {
    private final IdentityUserRepository users;
    private final PasswordEncoder encoder;
    public AdminBootstrapService(IdentityUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }
    public void createFirstAdministrator(String email, String password) {
        create(email, password, null);
    }
    /** Named provisioning for the operator console; nullable legacy accounts remain readable. */
    public void createFirstAdministrator(String email, String password, String displayName) {
        if (displayName == null || displayName.isBlank() || displayName.strip().length() > 120)
            throw new IllegalArgumentException("Administrator name must contain 1 to 120 characters.");
        create(email, password, displayName.strip());
    }
    private void create(String email, String password, String displayName) {
        if (!StringUtils.hasText(email) || !email.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || !StringUtils.hasText(password)
                || email.length() > 254 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("A valid bootstrap email and password are required.");
        String normalized = AuthenticationService.normalizeEmail(email);
        var user = new IdentityUser(normalized, normalized, encoder.encode(password), true);
        if (displayName != null) user.setDisplayName(displayName);
        user.addRole(new IdentityRole("CRM_ADMIN", "CRM Administrator"));
        // No count()==0 shortcut: emptiness + uniqueness + insertion is ONE operation.
        users.createFirstAdministrator(user);
    }
}
