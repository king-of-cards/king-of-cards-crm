package com.kingofcards.identity.bigquery.transactions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.entity.RefreshTokenSession;

/** Pure local preparation. All parameters are bound; SQL identifiers are immutable test targets. */
public final class AuthTransactionPlanner {
    public static final long DEFAULT_MAX_BYTES_BILLED = 100_000_000L;
    public static final long MAX_ISOLATED_TEST_BYTES_BILLED = 536_870_912L;
    private static final String SQL = load();
    private final Clock clock;
    private final long maxBytesBilled;
    private final String verificationRunId;
    public AuthTransactionPlanner(Clock clock) { this(clock, DEFAULT_MAX_BYTES_BILLED); }
    /** Explicit isolated-test budget only; the default is never raised automatically. */
    public AuthTransactionPlanner(Clock clock, long maxBytesBilled) {
        this(clock, maxBytesBilled, "");
    }
    /** An explicit run is mandatory for executing the verification SQL. Legacy constructors
     * remain usable for offline plan tests but their empty run fails SQL before any mutation.
     */
    public AuthTransactionPlanner(Clock clock, long maxBytesBilled, UUID runId) {
        this(clock, maxBytesBilled, Objects.requireNonNull(runId).toString());
    }
    private AuthTransactionPlanner(Clock clock, long maxBytesBilled, String runId) {
        validateByteBudget(maxBytesBilled);
        this.clock = Objects.requireNonNull(clock);
        this.maxBytesBilled = maxBytesBilled;
        this.verificationRunId = runId;
    }
    public static void validateByteBudget(long value) {
        if (value < DEFAULT_MAX_BYTES_BILLED || value > MAX_ISOLATED_TEST_BYTES_BILLED)
            throw new IllegalArgumentException("Isolated Auth query budget must be between 100000000 and 536870912 bytes.");
    }
    static String trustedSql() { return SQL; }

    public AuthTransactionPlan createUser(UUID operation, IdentityUser user, boolean firstAdministrator) {
        var p = user(user);
        if (firstAdministrator && (!user.isEnabled() || !p.get("role").getValue().equals("CRM_ADMIN")))
            throw new IllegalArgumentException("Bootstrap requires CRM_ADMIN.");
        return plan(operation, firstAdministrator ? "BOOTSTRAP" : "CREATE_USER", p);
    }
    public AuthTransactionPlan updateAccount(UUID operation, IdentityUser replacement) {
        var p = user(replacement);
        p.put("expected_updated_at", timestamp(replacement.getUpdatedAt()));
        return plan(operation, "UPDATE_ACCOUNT", p);
    }
    public AuthTransactionPlan createSession(UUID operation, IdentityUser expectedUser, RefreshTokenSession session) {
        var p = user(expectedUser);
        if (!expectedUser.isEnabled() || !session.getUserId().equals(expectedUser.getId()) || session.isConsumedOrRevoked())
            throw new IllegalArgumentException("A new session for the expected owner is required.");
        p.put("expected_updated_at", timestamp(expectedUser.getUpdatedAt()));
        p.put("new_id", uuid(session.getId()));
        p.put("family_id", uuid(session.getTokenFamilyId()));
        p.put("new_token_hash", hash(session.getTokenHash()));
        p.put("new_csrf_hash", hash(session.getCsrfTokenHash()));
        p.put("ttl_micros", ttl(session.getCreatedAt(), session.getExpiresAt()));
        return plan(operation, "CREATE_SESSION", p);
    }
    public AuthTransactionPlan rotate(UUID operation, String tokenHash, String csrfHash, UUID nextId,
            String nextTokenHash, String nextCsrfHash, Duration lifetime) {
        var p = new HashMap<String, QueryParameterValue>();
        p.put("token_hash", hash(tokenHash)); p.put("csrf_hash", hash(csrfHash));
        p.put("new_id", uuid(nextId)); p.put("new_token_hash", hash(nextTokenHash));
        p.put("new_csrf_hash", hash(nextCsrfHash));
        p.put("ttl_micros", ttl(Instant.EPOCH, Instant.EPOCH.plus(lifetime)));
        return plan(operation, "ROTATE", p);
    }
    public AuthTransactionPlan createVendorInvitation(UUID operation, IdentityUser vendor, RefreshTokenSession setup) {
        if (!vendor.isEnabled() || vendor.getRoles().size()!=1
                || !vendor.getRoles().iterator().next().getCode().equals("CRM_VENDOR") || vendor.getDisplayName()==null
                || !setup.getUserId().equals(vendor.getId()) || setup.isConsumedOrRevoked())
            throw new IllegalArgumentException("An enabled, named vendor and new setup credential are required.");
        var p=user(vendor);
        p.put("new_id",uuid(setup.getId())); p.put("family_id",uuid(setup.getTokenFamilyId()));
        p.put("new_token_hash",hash(setup.getTokenHash())); p.put("new_csrf_hash",hash(setup.getCsrfTokenHash()));
        var duration=Duration.between(setup.getCreatedAt(),setup.getExpiresAt());
        if (duration.compareTo(Duration.ofMinutes(30))>0) throw new IllegalArgumentException("Setup expires within 30 minutes.");
        p.put("ttl_micros",ttl(setup.getCreatedAt(),setup.getExpiresAt()));
        return plan(operation,"VENDOR_INVITE",p);
    }
    public AuthTransactionPlan completeVendorPasswordSetup(UUID operation, String setupHash, String proofHash, String passwordHash) {
        requireBcrypt(passwordHash);
        return plan(operation,"VENDOR_SETUP",Map.of("token_hash",hash(setupHash),"csrf_hash",hash(proofHash),
            "password_hash",QueryParameterValue.string(passwordHash)));
    }
    public AuthTransactionPlan logout(UUID operation, String tokenHash, String csrfHash) {
        return plan(operation, "REVOKE_TOKEN", Map.of("token_hash", hash(tokenHash), "csrf_hash", hash(csrfHash)));
    }
    /** Internal operation only; owner must be authoritative, never a client-provided display name. */
    public AuthTransactionPlan revokeFamily(UUID operation, UUID owner, UUID family) {
        return plan(operation, "REVOKE_FAMILY", Map.of("id", uuid(owner), "family_id", uuid(family)));
    }
    private AuthTransactionPlan plan(UUID operation, String action, Map<String, QueryParameterValue> provided) {
        Objects.requireNonNull(operation);
        var p = new HashMap<String, QueryParameterValue>();
        for (String key : List.of("id", "name", "email", "password_hash", "role", "new_id", "family_id",
                "token_hash", "csrf_hash", "new_token_hash", "new_csrf_hash")) p.put(key, QueryParameterValue.string(""));
        // BigQuery job metadata round-trips typed NULL values as empty scalars.
        // Use non-null unused placeholders, not a lossy NULL/empty equality rule.
        p.put("enabled", QueryParameterValue.bool(false));
        p.put("name_present", QueryParameterValue.bool(false));
        p.put("expected_updated_at", QueryParameterValue.timestamp(0L));
        p.put("ttl_micros", QueryParameterValue.int64(0));
        p.putAll(provided);
        var deadline = clock.instant().plus(Duration.ofMinutes(5));
        p.put("request_deadline", timestamp(deadline));
        p.put("operation", QueryParameterValue.string(action));
        p.put("verification_run_id", QueryParameterValue.string(verificationRunId));
        var config = QueryJobConfiguration.newBuilder(SQL).setNamedParameters(p).setUseLegacySql(false)
            .setUseQueryCache(false).setMaximumBytesBilled(maxBytesBilled).setJobTimeoutMs(45_000L)
            .setLabels(Map.of("purpose", "koc-auth-verification")).build();
        return new AuthTransactionPlan(operation, deadline, config);
    }
    private Map<String, QueryParameterValue> user(IdentityUser user) {
        Objects.requireNonNull(user);
        if (user.getRoles().size() != 1) throw new IllegalArgumentException("Exactly one role is required.");
        String email = user.getEmail();
        // This adapter is deliberately restricted to synthetic test accounts as well as test tables.
        if (email == null || !email.endsWith("@example.test") || !email.equals(email.trim().toLowerCase(Locale.ROOT))
                || email.length() > 254) throw new IllegalArgumentException("Only normalized synthetic @example.test users are allowed.");
        if (!verificationRunId.isEmpty() && !email.startsWith("it+" + verificationRunId + "-"))
            throw new IllegalArgumentException("Synthetic account does not belong to the verification run.");
        String name = user.getDisplayName();
        if (name != null && (name.isBlank() || name.length() > 120 || !name.equals(name.strip())))
            throw new IllegalArgumentException("Invalid display name.");
        requireBcrypt(user.getPasswordHash());
        var p = new HashMap<String, QueryParameterValue>();
        p.put("id", uuid(user.getId())); p.put("email", QueryParameterValue.string(email));
        p.put("name", QueryParameterValue.string(name == null ? "" : name));
        p.put("name_present", QueryParameterValue.bool(name != null));
        p.put("password_hash", QueryParameterValue.string(user.getPasswordHash()));
        p.put("role", QueryParameterValue.string(user.getRoles().iterator().next().getCode()));
        p.put("enabled", QueryParameterValue.bool(user.isEnabled()));
        return p;
    }
    private QueryParameterValue hash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("A SHA-256 hash is required.");
        return QueryParameterValue.string(value);
    }
    private static void requireBcrypt(String value) {
        if (value==null || !value.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}"))
            throw new IllegalArgumentException("A BCrypt hash is required.");
    }
    private QueryParameterValue uuid(UUID value) { return QueryParameterValue.string(Objects.requireNonNull(value).toString()); }
    private QueryParameterValue ttl(Instant created, Instant expires) {
        var value = Duration.between(created, expires);
        long micros = Math.addExact(Math.multiplyExact(value.getSeconds(), 1_000_000L), value.getNano() / 1000);
        if (micros <= 0) throw new IllegalArgumentException("Session lifetime must be positive.");
        return QueryParameterValue.int64(micros);
    }
    private QueryParameterValue timestamp(Instant value) {
        long micros = Math.addExact(Math.multiplyExact(Objects.requireNonNull(value).getEpochSecond(), 1_000_000L), value.getNano() / 1000);
        return QueryParameterValue.timestamp(micros);
    }
    private static String load() {
        try (var input = AuthTransactionPlanner.class.getResourceAsStream("/bigquery/auth-isolated-transaction.sql")) {
            if (input == null) throw new IllegalStateException("Auth SQL resource missing.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .replace("{{USERS}}", IsolatedAuthTarget.users()).replace("{{SESSIONS}}", IsolatedAuthTarget.sessions());
        } catch (IOException exception) { throw new IllegalStateException("Auth SQL resource unavailable."); }
    }
}
