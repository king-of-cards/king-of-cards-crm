package com.kingofcards.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.kingofcards.identity.dto.VendorInvitationResponse;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.exception.VendorDirectoryException;
import org.springframework.http.HttpStatus;

@Service
@org.springframework.context.annotation.Profile("legacy-vendor-setup")
public class VendorPasswordSetupService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final IdentityUserRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;
    public VendorPasswordSetupService(IdentityUserRepository users, PasswordEncoder passwords, Clock clock) {
        this.users=users; this.passwords=passwords; this.clock=clock;
    }
    @PreAuthorize("hasRole('CRM_ADMIN')")
    public VendorInvitationResponse invite(String email, String displayName) {
        if (email == null || email.length()>254 || !email.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new VendorDirectoryException(HttpStatus.BAD_REQUEST,"INVALID_VENDOR_SETUP","A valid vendor email is required.");
        String normalized=AuthenticationService.normalizeEmail(email);
        // This credential is never delivered: the account cannot log in before setup.
        var user=new IdentityUser(normalized, normalized, passwords.encode(randomToken()), true);
        user.addRole(new IdentityRole("CRM_VENDOR", "Vendor"));
        user.setDisplayName(displayName);
        String raw=randomToken();
        var now=clock.instant();
        var setup=new RefreshTokenSession(user.getId(), UUID.randomUUID(), tokenHash(raw), proofHash(raw),
            now, now.plus(Duration.ofMinutes(30)));
        users.createVendorInvitation(user,setup); // No token returned before confirmed atomic persistence.
        return new VendorInvitationResponse(user.getId(),raw,setup.getExpiresAt());
    }
    public void complete(String token, String password) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        if (password == null || password.isBlank() || password.length()<12
                || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new VendorDirectoryException(HttpStatus.BAD_REQUEST,"INVALID_VENDOR_SETUP","Password must contain at least 12 characters and at most 72 UTF-8 bytes.");
        if (!users.completeVendorPasswordSetup(tokenHash(token),proofHash(token),passwords.encode(password))) throw invalid();
    }
    // Domain separation prevents a setup token from working as a refresh token, or vice versa.
    static String tokenHash(String token) { return digest("koc-vendor-setup\0"+token); }
    static String proofHash(String token) { return digest("koc-vendor-setup-proof\0"+token); }
    private static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String randomToken() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static BadCredentialsException invalid() { return new BadCredentialsException("Invalid vendor setup credential."); }
}
