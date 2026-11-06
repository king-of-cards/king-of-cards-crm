package com.kingofcards.identity.bigquery.transactions;

import java.time.*;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.entity.IdentityUser;
import com.kingofcards.identity.exception.IdentityPersistenceException;

/** Experimental isolated-table executor. NOT a Spring bean; production store remains hard-gated.
 * No automatic conflict retries, new job IDs, cancellations or rollback assumptions.
 */
public final class BigQueryAuthTransactionAdapter {
    @FunctionalInterface public interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
    private final BigQuery client;
    private final Clock clock;
    private final Sleeper sleeper;
    private final int maxPolls;
    public BigQueryAuthTransactionAdapter(BigQuery client) {
        this(client, Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()), 120);
    }
    public BigQueryAuthTransactionAdapter(BigQuery client, Clock clock, Sleeper sleeper, int maxPolls) {
        if (maxPolls < 1 || maxPolls > 120) throw new IllegalArgumentException("Invalid polling limit.");
        this.client = Objects.requireNonNull(client); this.clock = clock;
        this.sleeper = sleeper; this.maxPolls = maxPolls;
    }
    public AuthTransactionResult submit(AuthTransactionPlan plan) {
        validateWindow(plan);
        Job job;
        try { job = client.create(JobInfo.newBuilder(plan.query()).setJobId(plan.jobId()).build()); }
        catch (RuntimeException uncertainSubmission) {
            // Includes 409, timeouts and connection loss. NEVER submit another job here.
            return recover(plan);
        }
        return await(plan, job);
    }
    /** Read-only recovery: a missing/hidden job is UNKNOWN, not permission to resubmit. */
    public AuthTransactionResult recover(AuthTransactionPlan plan) {
        validateWindow(plan);
        try { return await(plan, client.getJob(plan.jobId())); }
        catch (IdentityPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw unknown(); }
    }
    /** Historical reconciliation only, even after the request deadline. Never resubmits a job,
     * returns account data, or authorizes issuing credentials from an expired request.
     */
    public ReviewReceipt reviewOutcome(AuthTransactionPlan plan) {
        Objects.requireNonNull(plan);
        try {
            var result = await(plan, client.getJob(plan.jobId()), true);
            return new ReviewReceipt(plan.jobId().getJob(), result.status());
        } catch (IdentityPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw unknown(); }
    }
    public record ReviewReceipt(String jobId, AuthTransactionResult.Status committedStatus) {}
    private AuthTransactionResult await(AuthTransactionPlan plan, Job job) {
        return await(plan, job, false);
    }
    private AuthTransactionResult await(AuthTransactionPlan plan, Job job, boolean reviewOnly) {
        try {
            for (int i = 0; i < maxPolls; i++) {
                if (!reviewOnly) validateWindow(plan);
                if (job == null) throw unknown();
                if (!plan.jobId().equals(job.getJobId()))
                    throw new IdentityPersistenceException("IDENTITY_IDEMPOTENCY_KEY_REUSED");
                // SDK 2.51.0's duplicate-create recovery requests STATISTICS only and may
                // omit status/configuration. Reload the SAME job before trusting its result.
                // This is metadata recovery, never permission to submit another operation.
                if (job.getStatus() == null || job.getConfiguration() == null) {
                    job = client.getJob(plan.jobId());
                    if (job == null) throw unknown();
                }
                verifyBinding(plan, job);
                var status = job.getStatus();
                if (status == null) throw unknown();
                if (status.getState() == JobStatus.State.DONE) {
                    if (status.getError() != null) {
                        var error = status.getError();
                        if ("concurrentUpdate".equals(error.getReason()) || (error.getMessage() != null
                                && error.getMessage().contains("Transaction is aborted due to concurrent update")))
                            throw new IdentityPersistenceException("IDENTITY_TRANSACTION_CONFLICT");
                        if ("bytesBilledLimitExceeded".equals(error.getReason()) || (error.getMessage() != null
                                && error.getMessage().contains("Query exceeded limit for bytes billed")))
                            throw new IdentityPersistenceException("IDENTITY_QUERY_BUDGET_EXCEEDED");
                        // Do not assume an arbitrary script error proves no commit occurred.
                        throw new IdentityPersistenceException("IDENTITY_TRANSACTION_FAILED_REVIEW_REQUIRED");
                    }
                    var rows = job.getQueryResults().iterateAll().iterator();
                    if (!rows.hasNext()) throw unknown();
                    var row = rows.next();
                    if (rows.hasNext()) throw unknown();
                    var outcome = AuthTransactionResult.Status.valueOf(row.get("status").getStringValue());
                    validateOutcome(plan, outcome);
                    IdentityUser user = null;
                    if (Set.of(AuthTransactionResult.Status.CREATED, AuthTransactionResult.Status.UPDATED,
                            AuthTransactionResult.Status.SUCCESS).contains(outcome)) user = user(row);
                    boolean requiresEnabledUser = outcome == AuthTransactionResult.Status.SUCCESS
                        || (outcome == AuthTransactionResult.Status.CREATED && parameter(plan,"operation").equals("CREATE_SESSION"));
                    if (requiresEnabledUser && (user == null || !user.isEnabled())) throw unknown();
                    if (user != null) validateUserBinding(plan, user);
                    if (!reviewOnly) validateWindow(plan);
                    return new AuthTransactionResult(outcome, user);
                }
                sleeper.sleep(Duration.ofMillis(250));
                job = client.getJob(plan.jobId());
            }
            throw unknown();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw unknown();
        } catch (IdentityPersistenceException exception) { throw exception; }
        catch (RuntimeException exception) { throw confirmedFailureOrUnknown(plan); }
    }
    /** The SDK can throw while fetching results before its cached Job exposes the
     * terminal error. Reconcile ONLY the original job; exception text alone is
     * never proof of rollback and never permits a fresh mutation submission.
     */
    private IdentityPersistenceException confirmedFailureOrUnknown(AuthTransactionPlan plan) {
        try {
            var original = client.getJob(plan.jobId());
            if (original == null || original.getStatus() == null) return unknown();
            verifyBinding(plan, original);
            var status = original.getStatus();
            var error = status.getError();
            if (status.getState() == JobStatus.State.DONE && error != null
                    && ("concurrentUpdate".equals(error.getReason()) || (error.getMessage() != null
                    && error.getMessage().contains("Transaction is aborted due to concurrent update"))))
                return new IdentityPersistenceException("IDENTITY_TRANSACTION_CONFLICT");
        } catch (IdentityPersistenceException exception) { return exception; }
        catch (RuntimeException ignored) { /* Still uncertain; never replay. */ }
        return unknown();
    }
    private void verifyBinding(AuthTransactionPlan plan, Job job) {
        if (!plan.jobId().equals(job.getJobId()) || !(job.getConfiguration() instanceof QueryJobConfiguration config)
                || !plan.query().getQuery().equals(config.getQuery())
                || !plan.query().getNamedParameters().equals(config.getNamedParameters())
                || !Boolean.FALSE.equals(config.useLegacySql()) || !Boolean.FALSE.equals(config.useQueryCache())
                || !Objects.equals(plan.query().getMaximumBytesBilled(), config.getMaximumBytesBilled())
                || !Objects.equals(plan.query().getJobTimeoutMs(), config.getJobTimeoutMs()))
            throw new IdentityPersistenceException("IDENTITY_IDEMPOTENCY_KEY_REUSED");
    }
    private void validateOutcome(AuthTransactionPlan plan, AuthTransactionResult.Status outcome) {
        Set<String> allowed = switch (parameter(plan,"operation")) {
            case "BOOTSTRAP" -> Set.of("CREATED","BOOTSTRAP_NOT_EMPTY");
            case "CREATE_USER", "VENDOR_INVITE" -> Set.of("CREATED","DUPLICATE_EMAIL","ID_COLLISION");
            case "UPDATE_ACCOUNT" -> Set.of("UPDATED","NOT_FOUND","STALE_ACCOUNT","DUPLICATE_EMAIL");
            case "CREATE_SESSION" -> Set.of("CREATED","INVALID");
            case "ROTATE" -> Set.of("SUCCESS","INVALID","REUSED","CSRF_REJECTED");
            case "REVOKE_TOKEN" -> Set.of("REVOKED","INVALID","CSRF_REJECTED");
            case "REVOKE_FAMILY" -> Set.of("REVOKED","NOT_FOUND");
            case "VENDOR_SETUP" -> Set.of("SUCCESS","INVALID");
            default -> throw unknown();
        };
        if (!allowed.contains(outcome.name())) throw unknown();
    }
    private void validateUserBinding(AuthTransactionPlan plan, IdentityUser user) {
        String operation=parameter(plan,"operation");
        if (Set.of("BOOTSTRAP","CREATE_USER","VENDOR_INVITE","UPDATE_ACCOUNT","CREATE_SESSION").contains(operation)) {
            String expectedName=Boolean.parseBoolean(parameter(plan,"name_present"))?parameter(plan,"name"):null;
            if (!user.getId().toString().equals(parameter(plan,"id")) || !user.getEmail().equals(parameter(plan,"email"))
                    || !Objects.equals(user.getDisplayName(),expectedName)
                    || !user.getPasswordHash().equals(parameter(plan,"password_hash"))
                    || !user.getRoles().iterator().next().getCode().equals(parameter(plan,"role"))
                    || user.isEnabled()!=Boolean.parseBoolean(parameter(plan,"enabled"))) throw unknown();
            if (operation.equals("UPDATE_ACCOUNT") && !user.getUpdatedAt().isAfter(
                    timestamp(plan.query().getNamedParameters().get("expected_updated_at")))) throw unknown();
        }
        if (operation.equals("VENDOR_SETUP") && (!user.getRoles().iterator().next().getCode().equals("CRM_VENDOR")
                || !user.getPasswordHash().equals(parameter(plan,"password_hash")))) throw unknown();
    }
    private String parameter(AuthTransactionPlan plan, String name) {
        return Objects.requireNonNull(plan.query().getNamedParameters().get(name).getValue());
    }
    private Instant timestamp(QueryParameterValue value) {
        // The SDK formats microsecond parameters in UTC, without an offset in this version.
        String text=value.getValue().replace(' ', 'T');
        if (!text.endsWith("Z") && !text.matches(".*[+-][0-9]{2}:[0-9]{2}$")) text+="Z";
        return Instant.parse(text);
    }
    private void validateWindow(AuthTransactionPlan plan) {
        if (plan == null || !clock.instant().isBefore(plan.deadline()))
            throw new IdentityPersistenceException("IDENTITY_OPERATION_EXPIRED_REVIEW_REQUIRED");
    }
    private IdentityUser user(FieldValueList row) {
        String email = row.get("email").getStringValue();
        String role = row.get("role").getStringValue();
        String hash = row.get("password_hash").getStringValue();
        if (email.length()>254 || !email.endsWith("@example.test") || !email.equals(email.trim().toLowerCase(Locale.ROOT))
                || !hash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}")) throw unknown();
        String rawId=row.get("id").getStringValue();
        UUID id=UUID.fromString(rawId);
        String name=row.get("name").isNull()?null:row.get("name").getStringValue();
        Instant created=timestamp(row.get("created_at")), updated=timestamp(row.get("updated_at"));
        if (!id.toString().equals(rawId) || (name!=null && (name.isBlank() || name.length()>120 || !name.equals(name.strip())))
                || updated.isBefore(created)) throw unknown();
        return IdentityUser.restore(id,email,name,hash,role,row.get("enabled").getBooleanValue(),created,updated);
    }
    private Instant timestamp(FieldValue value) {
        long micros = value.getTimestampValue();
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1000);
    }
    private static IdentityPersistenceException unknown() {
        return new IdentityPersistenceException("IDENTITY_COMMIT_OUTCOME_UNKNOWN");
    }
}
