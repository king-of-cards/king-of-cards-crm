package com.kingofcards.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import com.kingofcards.identity.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Token possession, not public registration. The production store still rejects setup. */
@Service
public class AdminPasswordSetupService {
    private final AdminMutationStore store;
    private final PasswordEncoder passwords;
    public AdminPasswordSetupService(AdminMutationStore store,PasswordEncoder passwords){this.store=store;this.passwords=passwords;}
    public void complete(String token,String password) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}") || password==null || password.isBlank()
                || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new VendorDirectoryException(HttpStatus.BAD_REQUEST,"INVALID_ADMIN_SETUP","A setup credential and a password of at least 12 characters and at most 72 UTF-8 bytes are required.");
        // Fail before expensive hashing if the verified single-use transaction does not exist.
        if(!store.setupAvailable())throw new IdentityPersistenceException("ADMIN_MUTATIONS_UNVERIFIED");
        store.completeSetup(hash("koc-admin-setup\0",token),hash("koc-admin-setup-proof\0",token),passwords.encode(password));
    }
    private static String hash(String domain,String token) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((domain+token).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException exception){throw new IllegalStateException("SHA-256 unavailable");}
    }
}
