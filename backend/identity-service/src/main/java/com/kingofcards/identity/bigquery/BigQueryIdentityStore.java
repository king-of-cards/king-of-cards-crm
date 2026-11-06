package com.kingofcards.identity.bigquery;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import com.google.cloud.bigquery.*;
import org.springframework.data.domain.*;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.entity.RefreshTokenSession;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import com.kingofcards.identity.repository.IdentityUserRepository;
import com.kingofcards.identity.repository.RefreshTokenSessionRepository;
import com.kingofcards.identity.service.AuthenticationService;

/** Read adapter. All mutations are deliberately hard-gated, with no environment override.
 * A read followed by an INSERT/UPDATE is NOT a substitute for a verified Auth transaction.
 */
public final class BigQueryIdentityStore implements IdentityUserRepository, RefreshTokenSessionRepository {
    private static final String COLUMNS = "id, name, email, password_hash, role, enabled, created_at, updated_at";
    private final Supplier<BigQuery> clients;
    private final IdentityBigQueryProperties properties;
    public BigQueryIdentityStore(Supplier<BigQuery> clients, IdentityBigQueryProperties properties) {
        properties.validate();
        this.clients = clients;
        this.properties = properties;
    }
    @Override public Optional<IdentityUser> findByNormalizedEmail(String email) {
        if (email == null || email.isBlank() || email.trim().length() > 254)
            throw new IllegalArgumentException("Invalid email lookup.");
        String normalized = AuthenticationService.normalizeEmail(email);
        var result = one("email = @email", Map.of("email", QueryParameterValue.string(normalized)));
        if (result.isPresent() && !result.get().getEmail().equals(normalized)) throw integrity();
        return result;
    }
    @Override public Optional<IdentityUser> findById(UUID id) {
        Objects.requireNonNull(id);
        var result = one("id = @id", Map.of("id", QueryParameterValue.string(id.toString())));
        if (result.isPresent() && !result.get().getId().equals(id)) throw integrity();
        return result;
    }
    private Optional<IdentityUser> one(String predicate, Map<String, QueryParameterValue> params) {
        var rows = read("SELECT " + selection() + " FROM " + properties.table("identity_user") + " AS u"
            + " WHERE " + predicate + " LIMIT 2", params).iterator();
        if (!rows.hasNext()) return Optional.empty();
        var user = user(rows.next());
        if (rows.hasNext()) throw integrity();
        return Optional.of(user);
    }
    @Override public long count() { return countWhere("", Map.of()); }
    private long countWhere(String where, Map<String, QueryParameterValue> params) {
        var rows = read("SELECT COUNT(*) AS total FROM " + properties.table("identity_user") + where, params)
            .iterator();
        if (!rows.hasNext()) throw integrity();
        long value = rows.next().get("total").getLongValue();
        if (value < 0 || rows.hasNext()) throw integrity();
        return value;
    }
    @Override public Page<IdentityUser> findAssignableVendors(Pageable pageable) {
        if (pageable == null || pageable.isUnpaged() || pageable.getPageSize() < 1
                || pageable.getPageSize() > 100 || pageable.getOffset() > 100_000)
            throw new IllegalArgumentException("Invalid bounded vendor pagination.");
        String where = " WHERE role = @role AND enabled = TRUE AND name IS NOT NULL AND TRIM(name) <> ''";
        var params = Map.of("role", QueryParameterValue.string("CRM_VENDOR"));
        long count = countWhere(where, params);
        var pageParams = new HashMap<>(params);
        pageParams.put("limit", QueryParameterValue.int64(pageable.getPageSize()));
        pageParams.put("offset", QueryParameterValue.int64(pageable.getOffset()));
        var rows = read("SELECT " + selection() + " FROM " + properties.table("identity_user") + " AS u" + where
            + " ORDER BY id ASC LIMIT @limit OFFSET @offset", pageParams);
        var items = new ArrayList<IdentityUser>();
        var ids = new HashSet<UUID>();
        for (var row : rows) {
            var user = user(row);
            if (!user.isEnabled() || user.getDisplayName() == null || user.getDisplayName().isBlank()
                    || !user.getRoles().iterator().next().getCode().equals("CRM_VENDOR") || !ids.add(user.getId()))
                throw integrity();
            items.add(user);
            if (items.size() > pageable.getPageSize()) throw integrity();
        }
        // Count and page are read separately; totals may change with concurrent approved writes.
        return new PageImpl<>(items, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
            Sort.by("id").ascending()), count);
    }
    private List<FieldValueList> read(String sql, Map<String, QueryParameterValue> params) {
        if (!properties.enabled()) throw new IdentityPersistenceException("IDENTITY_BIGQUERY_DISABLED");
        try {
            BigQuery client = clients.get();
            if (client == null) throw new IdentityPersistenceException("IDENTITY_BIGQUERY_UNAVAILABLE");
            var config = QueryJobConfiguration.newBuilder(sql).setNamedParameters(params)
                .setUseLegacySql(false).setUseQueryCache(false).setMaximumBytesBilled(100_000_000L)
                .setJobTimeoutMs(20_000L).build();
            var jobId = JobId.newBuilder().setProject(properties.projectId()).setLocation(properties.location())
                .setJob(UUID.randomUUID().toString()).build();
            var result = client.query(config, jobId);
            var rows = new ArrayList<FieldValueList>();
            for (var row : result.iterateAll()) rows.add(row);
            return rows;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IdentityPersistenceException("IDENTITY_BIGQUERY_INTERRUPTED");
        } catch (IdentityPersistenceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IdentityPersistenceException("IDENTITY_BIGQUERY_UNAVAILABLE");
        }
    }
    private String selection() {
        String table = properties.table("identity_user");
        // Detect corrupt IDs/emails even when the other lookup predicate returns one row.
        return COLUMNS + ", (SELECT COUNT(*) FROM " + table + " AS checks WHERE checks.id = u.id) AS subject_count"
            + ", (SELECT COUNT(*) FROM " + table + " AS checks WHERE checks.email = u.email) AS email_count";
    }
    private IdentityUser user(FieldValueList row) {
        try {
            if (row.get("subject_count").getLongValue() != 1 || row.get("email_count").getLongValue() != 1)
                throw integrity();
            String id = row.get("id").getStringValue();
            UUID uuid = UUID.fromString(id);
            if (!uuid.toString().equals(id)) throw integrity();
            String email = row.get("email").getStringValue();
            if (email.isBlank() || email.length() > 254 || !email.equals(AuthenticationService.normalizeEmail(email)))
                throw integrity();
            String name = row.get("name").isNull() ? null : row.get("name").getStringValue();
            if (name != null && (name.isBlank() || !name.equals(name.strip()) || name.length() > 120)) throw integrity();
            String hash = row.get("password_hash").getStringValue();
            if (!hash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}")) throw integrity();
            Instant created=timestamp(row.get("created_at")), updated=timestamp(row.get("updated_at"));
            if (updated.isBefore(created)) throw integrity();
            return IdentityUser.restore(uuid, email, name, hash,
                row.get("role").getStringValue(), row.get("enabled").getBooleanValue(),
                created, updated);
        } catch (RuntimeException exception) { throw integrity(); }
    }
    private Instant timestamp(FieldValue value) {
        long micros = value.getTimestampValue();
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000);
    }
    private static IdentityPersistenceException integrity() {
        return new IdentityPersistenceException("IDENTITY_DATA_INTEGRITY_FAILURE");
    }
    @Override public IdentityUser save(IdentityUser user) { throw IdentityPersistenceException.gated(); }
    @Override public void createUnique(IdentityUser user) { throw IdentityPersistenceException.gated(); }
    @Override public void createFirstAdministrator(IdentityUser user) { throw IdentityPersistenceException.gated(); }
    @Override public void createVendorInvitation(IdentityUser user, RefreshTokenSession setup) {
        throw IdentityPersistenceException.gated();
    }
    @Override public boolean completeVendorPasswordSetup(String tokenHash, String proofHash, String passwordHash) {
        throw IdentityPersistenceException.gated();
    }
    @Override public void createForAuthenticatedUser(IdentityUser user, RefreshTokenSession session) {
        throw IdentityPersistenceException.gated();
    }
    @Override public Rotation rotate(String tokenHash, String csrfHash, UUID nextId, String nextTokenHash,
            String nextCsrfHash, Instant now, Instant expiresAt) { throw IdentityPersistenceException.gated(); }
    @Override public void revoke(String tokenHash, String csrfHash, Instant now) {
        throw IdentityPersistenceException.gated();
    }
}
