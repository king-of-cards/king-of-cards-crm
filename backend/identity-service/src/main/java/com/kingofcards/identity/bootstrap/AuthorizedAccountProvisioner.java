package com.kingofcards.identity.bootstrap;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import com.kingofcards.identity.entity.IdentityRole;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.service.AuthenticationService;

/** Operator-only building block, deliberately NOT a Spring bean, controller or startup runner.
 * Input must come from an authorized offline provisioning process. Production writes remain gated.
 * Never performs a read-then-insert, creates credentials, retries, or reports unconfirmed success.
 */
public final class AuthorizedAccountProvisioner {
    private final IdentityUserRepository users;
    private final Clock clock;
    public AuthorizedAccountProvisioner(IdentityUserRepository users, Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.clock = Objects.requireNonNull(clock);
    }
    public UUID provision(UUID subjectId, String name, String email, String bcryptHash, String role, boolean enabled) {
        if (subjectId == null || name == null || name.isBlank() || name.strip().length() > 120
                || email == null || email.length() > 254 || !email.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                || role == null || !IdentityRole.SUPPORTED_CODES.contains(role)
                || bcryptHash == null || !bcryptHash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}"))
            throw new IllegalArgumentException("Invalid authorized provisioning input.");
        var now = clock.instant();
        var user = IdentityUser.restore(subjectId, AuthenticationService.normalizeEmail(email), name.strip(),
            bcryptHash, role, enabled, now, now);
        // createUnique owns distributed uniqueness/confirmed commit. An exception is NOT success.
        users.createUnique(user);
        return subjectId;
    }
}
