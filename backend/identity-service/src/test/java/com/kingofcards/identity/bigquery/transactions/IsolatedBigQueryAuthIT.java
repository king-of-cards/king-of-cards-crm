package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.google.cloud.http.HttpTransportOptions;
import com.kingofcards.identity.bigquery.IdentityBigQuerySchema;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static com.kingofcards.identity.bigquery.transactions.AuthTransactionResult.Status.*;

/** LIVE DML. Never selected by default Surefire. Explicit profile AND approval required.
 * Production-named Auth tables only, approved synthetic run, initially EMPTY tables required.
 * No DDL, no row deletion, no JWTs, no real accounts or automatic cleanup.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IsolatedBigQueryAuthIT {
    @RegisterExtension final IsolatedSuiteGuard suiteGuard = new IsolatedSuiteGuard();
    static final String APPROVAL = "ALLOW_PRODUCTION_NAMED_AUTH_12_TESTS_V1";
    BigQuery first, second;
    BigQueryAuthTransactionAdapter adapter;
    AuthTransactionPlanner planner;
    String run, bcrypt;
    String continuationTag = "";
    IsolatedQueryBudget jobBudget;

    static void requireApproval(Map<String, String> environment) {
        if (!APPROVAL.equals(environment.get("KOC_AUTH_IT_APPROVAL")))
            throw new IllegalStateException("Fresh approval for production-named Auth verification is required before ADC or network access.");
        String run = Objects.requireNonNull(environment.get("KOC_AUTH_IT_RUN_ID"), "A UUID test run ID is required.");
        if (!UUID.fromString(run).toString().equals(run)) throw new IllegalArgumentException("A canonical lowercase UUID is required.");
    }
    static long approvedByteBudget(Map<String, String> environment) {
        String setting = environment.get("KOC_AUTH_IT_MAX_BYTES_BILLED");
        long value = setting == null ? AuthTransactionPlanner.DEFAULT_MAX_BYTES_BILLED : Long.parseLong(setting);
        AuthTransactionPlanner.validateByteBudget(value);
        return value;
    }
    static int approvedJobBudget(Map<String,String> environment) {
        String setting=environment.get("KOC_AUTH_IT_MAX_JOBS");
        int value=setting==null?IsolatedQueryBudget.DEFAULT_MAX_JOBS:Integer.parseInt(setting);
        new IsolatedQueryBudget(value);return value;
    }
    @BeforeAll void preflight() throws Exception {
        requireApproval(System.getenv()); // MUST precede credentials, clients and every cloud request.
        long byteBudget = approvedByteBudget(System.getenv()); // Invalid/unbounded budgets fail before ADC/network.
        jobBudget=new IsolatedQueryBudget(approvedJobBudget(System.getenv()));
        run = UUID.fromString(System.getenv("KOC_AUTH_IT_RUN_ID")).toString();
        String continuationManifest = System.getenv("KOC_AUTH_IT_CONTINUATION_MANIFEST");
        com.google.gson.JsonObject manifest = null;
        if (continuationManifest != null) {
            manifest = AuthVerificationContinuation.manifest(Path.of(continuationManifest), run);
            continuationTag = "c" + UUID.randomUUID().toString().replace("-", "") + "-";
            for (int i=0;i<AuthVerificationContinuation.PREVIOUS_SUBMISSIONS;i++) jobBudget.reserve();
        }
        var credentials = GoogleCredentials.getApplicationDefault();
        first = client(credentials); second = client(credentials);
        adapter = new BigQueryAuthTransactionAdapter(first);
        planner = new AuthTransactionPlanner(Clock.systemUTC(), byteBudget, UUID.fromString(run));
        var dataset = first.getDataset(DatasetId.of(IsolatedAuthTarget.PROJECT, IsolatedAuthTarget.DATASET));
        assertNotNull(dataset, "Existing approved dataset must be provisioned externally.");
        assertEquals(IsolatedAuthTarget.LOCATION, dataset.getLocation().toLowerCase(Locale.ROOT));
        for (var pair : Map.of(IsolatedAuthTarget.USERS, "identity_user", IsolatedAuthTarget.SESSIONS, "identity_refresh_session").entrySet()) {
            var table = first.getTable(TableId.of(IsolatedAuthTarget.PROJECT, IsolatedAuthTarget.DATASET, pair.getKey()));
            assertNotNull(table, "Isolated tables must be provisioned externally; no automatic DDL.");
            assertInstanceOf(StandardTableDefinition.class, table.getDefinition());
            assertEquals(TableDefinition.Type.TABLE, table.getDefinition().getType());
            var actual = ((StandardTableDefinition) table.getDefinition()).getSchema();
            var expected = IdentityBigQuerySchema.tables().get(pair.getValue());
            assertNotNull(actual);
            assertEquals(expected.getFields().size(), actual.getFields().size());
            for (var field : expected.getFields()) {
                var found = actual.getFields().get(field.getName());
                assertEquals(field.getType(), found.getType());
                assertEquals(field.getMode(), found.getMode() == null ? Field.Mode.NULLABLE : found.getMode());
            }
        }
        if (manifest == null) {
            assertEquals(0, count(IsolatedAuthTarget.users(), "TRUE", Map.of()), "Full bootstrap verification requires empty Auth tables; real accounts must never be removed for this test.");
            assertEquals(0, count(IsolatedAuthTarget.sessions(), "TRUE", Map.of()));
        } else {
            AuthVerificationContinuation.verify(first, manifest);
        }
        // Synthetic test-only password. No real login account, Google credential or signing key.
        bcrypt = new BCryptPasswordEncoder(12).encode("synthetic-not-a-login-" + run);
    }
    @BeforeEach void restrictContinuation(TestInfo info) {
        if (!continuationTag.isEmpty()) AuthVerificationContinuation.requireScenario(
            info.getTestMethod().orElseThrow().getAnnotation(Order.class).value());
    }
    private BigQuery client(GoogleCredentials credentials) {
        var delegate=BigQueryOptions.newBuilder().setProjectId(IsolatedAuthTarget.PROJECT).setLocation(IsolatedAuthTarget.LOCATION)
            .setCredentials(credentials).setTransportOptions(HttpTransportOptions.newBuilder()
                .setConnectTimeout(10_000).setReadTimeout(20_000).build())
            .setRetrySettings(BigQueryOptions.getDefaultRetrySettings().toBuilder().setMaxAttempts(1).build())
            .build().getService();
        return (BigQuery)Proxy.newProxyInstance(BigQuery.class.getClassLoader(),new Class<?>[]{BigQuery.class},(proxy,method,args)->{
            if(method.getName().equals("create") || method.getName().equals("query"))jobBudget.reserve();
            try{return method.invoke(delegate,args);}catch(InvocationTargetException exception){throw exception.getCause();}
        });
    }
    @AfterAll void reportBudgetOnly() throws Exception {
        if(jobBudget!=null) {
            var directory=Path.of("target","auth-verification",run);Files.createDirectories(directory);
            Files.writeString(directory.resolve("budget.txt"),"run="+run+"\nsubmissions="+jobBudget.used()+"\n");
            System.out.println("Auth verification top-level job reservations: "+jobBudget.used());
        }
    }
    @Test @Order(1) void concurrentBootstrapCreatesExactlyOneAdministrator() throws Exception {
        var a = user("admin-a", "CRM_ADMIN");
        var b = user("admin-b", "CRM_ADMIN");
        var attempts = race(() -> planner.createUser(op(), a, true), () -> planner.createUser(op(), b, true));
        for (var attempt : attempts) assertAllowed(attempt, Set.of(CREATED, BOOTSTRAP_NOT_EMPTY));
        assertEquals(1, attempts.stream().filter(x -> x.status == CREATED).count());
        assertEquals(1, count(IsolatedAuthTarget.users(), "TRUE", Map.of()));
        assertEquals(BOOTSTRAP_NOT_EMPTY, run(() -> planner.createUser(op(), a, true)).status());
    }
    @Test @Order(2) void concurrentEmailCreationCannotCommitDuplicates() throws Exception {
        var a = user("duplicate", "CRM_VENDOR");
        var b = user("duplicate", "CRM_VENDOR");
        var attempts = race(() -> planner.createUser(op(), a, false), () -> planner.createUser(op(), b, false));
        for (var attempt : attempts) assertAllowed(attempt, Set.of(CREATED, DUPLICATE_EMAIL));
        assertEquals(1, attempts.stream().filter(x -> x.status == CREATED).count());
        assertEquals(DUPLICATE_EMAIL, run(() -> planner.createUser(op(), b, false)).status());
        assertEquals(1, count(IsolatedAuthTarget.users(), "email = @email", Map.of("email", QueryParameterValue.string(a.getEmail()))));
    }
    @Test @Order(3) void rotatesOnceThenCommitsReuseRevocation() throws Exception {
        var f = fixture("reuse", Duration.ofDays(7));
        var committed = committed(() -> rotation(f, "child"));
        assertEquals(SUCCESS, committed.result().status());
        assertEquals(SUCCESS, adapter.recover(committed.plan()).status()); // historical job, not another rotation
        assertEquals(2, familyCount(f, false));
        assertEquals(1, familyCount(f, true));
        assertEquals(REUSED, run(() -> rotation(f, "replay")).status());
        assertEquals(0, familyCount(f, true));
        assertEquals(2, familyCount(f, false));
    }
    @Test @Order(4) void logoutRevokesTheWholeRotatedFamilyAndIsIdempotent() throws Exception {
        var f = fixture("logout", Duration.ofDays(7));
        assertEquals(SUCCESS, run(() -> rotation(f, "child")).status());
        var committed = committed(() -> planner.logout(op(), f.tokenHash(), f.csrfHash()));
        assertEquals(REVOKED, committed.result().status());
        assertEquals(REVOKED, adapter.submit(committed.plan()).status()); // duplicate job ID recovery
        assertEquals(REVOKED, run(() -> planner.logout(op(), f.tokenHash(), f.csrfHash())).status());
        assertEquals(0, familyCount(f, true));
    }
    @Test @Order(5) void csrfExpirationAndOwnerChecksFailClosed() throws Exception {
        var f = fixture("csrf", Duration.ofDays(7));
        assertEquals(CSRF_REJECTED, run(() -> planner.logout(op(), f.tokenHash(), hash("wrong-csrf"))).status());
        assertEquals(1, familyCount(f, true));
        assertEquals(NOT_FOUND, run(() -> planner.revokeFamily(op(), UUID.randomUUID(), f.family())).status());
        assertEquals(1, familyCount(f, true));
        assertEquals(REVOKED, run(() -> planner.revokeFamily(op(), f.user().getId(), f.family())).status());
        assertEquals(0, familyCount(f, true));
        var expired = fixture("expired", Duration.ofNanos(1000));
        assertEquals(INVALID, run(() -> rotation(expired, "expired-next")).status());
        assertEquals(0, familyCount(expired, true));
    }
    @Test @Order(6) void accountBlockingRevokesSessionsAndRejectsStaleUpdates() throws Exception {
        var f = fixture("blocked", Duration.ofDays(7));
        var blocked = IdentityUser.restore(f.user().getId(), f.user().getEmail(), "Blocked synthetic user", bcrypt,
            "CRM_VENDOR", false, f.user().getCreatedAt(), f.user().getUpdatedAt());
        var result = run(() -> planner.updateAccount(op(), blocked));
        assertEquals(UPDATED, result.status());
        assertFalse(result.user().isEnabled());
        assertEquals(f.user().getCreatedAt(), result.user().getCreatedAt());
        assertTrue(result.user().getUpdatedAt().isAfter(blocked.getUpdatedAt()));
        assertEquals(0, familyCount(f, true));
        assertEquals(STALE_ACCOUNT, run(() -> planner.updateAccount(op(), blocked)).status());
        var session = session(result.user(), "disabled-session", Duration.ofDays(7));
        // A known-disabled snapshot is rejected before RPC, not a committed SQL denial.
        assertThrows(IllegalArgumentException.class, () -> planner.createSession(op(), result.user(), session));
        // Also verify a caller that read ENABLED before blocking cannot persist a session.
        assertEquals(INVALID, run(() -> planner.createSession(op(), f.user(), session)).status());
        assertEquals(1, familyCount(f, false));
    }
    @Test @Order(7) void simultaneousRefreshNeverCreatesTwoSuccessors() throws Exception {
        var f = fixture("refresh-race", Duration.ofDays(7));
        var attempts = race(() -> rotation(f, "a"), () -> rotation(f, "b"));
        for (var attempt : attempts) assertAllowed(attempt, Set.of(SUCCESS, REUSED));
        assertEquals(1, attempts.stream().filter(x -> x.status == SUCCESS).count());
        assertEquals(2, familyCount(f, false));
        // An aborted loser can make a NEW, explicit attempt after the documented backoff.
        assertEquals(REUSED, run(() -> rotation(f, "explicit-retry")).status());
        assertEquals(0, familyCount(f, true));
    }
    @Test @Order(8) void refreshVersusLogoutCannotLeaveAnActiveSuccessor() throws Exception {
        var f = fixture("logout-race", Duration.ofDays(7));
        var attempts = race(() -> rotation(f, "child"), () -> planner.logout(op(), f.tokenHash(), f.csrfHash()));
        assertAllowed(attempts.get(0), Set.of(SUCCESS, REUSED));
        assertAllowed(attempts.get(1), Set.of(REVOKED));
        // If logout lost to a confirmed conflict, retry only that known-aborted intent.
        if (attempts.get(1).conflict) {
            Thread.sleep(60_000);
            assertEquals(REVOKED, run(() -> planner.logout(op(), f.tokenHash(), f.csrfHash())).status());
        }
        assertEquals(0, familyCount(f, true));
    }
    @Test @Order(9) void acknowledgementLossRecoversTheRealSameJobWithoutDuplicateRows() throws Exception {
        var user = user("ack-loss", "CRM_VENDOR");
        var plan = planner.createUser(op(), user, false);
        var armed = new AtomicBoolean(true); // fault injection only, NOT a security/serialization lock
        BigQuery fault = (BigQuery) Proxy.newProxyInstance(BigQuery.class.getClassLoader(), new Class<?>[] {BigQuery.class},
            (proxy, method, args) -> {
                try {
                    Object result = method.invoke(first, args);
                    if (method.getName().equals("create") && armed.compareAndSet(true, false))
                        throw new BigQueryException(504, "Injected acknowledgement loss after submission");
                    return result;
                } catch (InvocationTargetException exception) { throw exception.getCause(); }
            });
        var recovering = new BigQueryAuthTransactionAdapter(fault);
        receipt(plan);
        AuthTransactionResult result;
        try { result = recovering.submit(plan); }
        catch (IdentityPersistenceException exception) {
            if (!exception.code().equals("IDENTITY_COMMIT_OUTCOME_UNKNOWN")) throw exception;
            result = recovering.recover(plan); // READ ONLY; no new mutation/job ID
        }
        assertEquals(CREATED, result.status());
        outcome(plan,result);
        assertFalse(armed.get(), "Acknowledgement-loss fault must actually have been injected.");
        assertEquals(CREATED, adapter.recover(plan).status());
        assertEquals(1, count(IsolatedAuthTarget.users(), "email = @email", Map.of("email", QueryParameterValue.string(user.getEmail()))));
    }
    @Test @Order(10) void invitationSetupChangesPasswordOnceAndRevokesAllSessions() throws Exception {
        var vendor=user("invite","CRM_VENDOR");var setup=session(vendor,"invite-setup",Duration.ofMinutes(30));
        var invited=run(()->planner.createVendorInvitation(op(),vendor,setup));assertEquals(CREATED,invited.status());
        assertEquals(CREATED,run(()->planner.createSession(op(),invited.user(),session(invited.user(),"invite-refresh",Duration.ofDays(7)))).status());
        String password=new BCryptPasswordEncoder(12).encode("synthetic-setup-"+run);
        var changed=run(()->planner.completeVendorPasswordSetup(op(),setup.getTokenHash(),setup.getCsrfTokenHash(),password));
        assertEquals(SUCCESS,changed.status());assertEquals(vendor.getId(),changed.user().getId());
        assertTrue(password.equals(changed.user().getPasswordHash()));
        assertTrue(changed.user().getUpdatedAt().isAfter(invited.user().getUpdatedAt()));
        assertEquals(INVALID,run(()->planner.completeVendorPasswordSetup(op(),setup.getTokenHash(),setup.getCsrfTokenHash(),bcrypt)).status());
        assertEquals(0,count(IsolatedAuthTarget.sessions(),"user_id = @id AND revoked_at IS NULL",Map.of("id",QueryParameterValue.string(vendor.getId().toString()))));
    }
    @Test @Order(11) void expiredAndDisabledSetupCannotChangePassword() throws Exception {
        for(boolean disabled:List.of(false,true)) {
            String label=disabled?"invite-blocked":"invite-expired";var vendor=user(label,"CRM_VENDOR");
            var setup=session(vendor,label,disabled?Duration.ofMinutes(30):Duration.ofNanos(1000));
            var invited=run(()->planner.createVendorInvitation(op(),vendor,setup));assertEquals(CREATED,invited.status());
            if(disabled){var current=invited.user();var blocked=IdentityUser.restore(current.getId(),current.getEmail(),current.getDisplayName(),bcrypt,
                "CRM_VENDOR",false,current.getCreatedAt(),current.getUpdatedAt());assertEquals(UPDATED,run(()->planner.updateAccount(op(),blocked)).status());}
            assertEquals(INVALID,run(()->planner.completeVendorPasswordSetup(op(),setup.getTokenHash(),setup.getCsrfTokenHash(),bcrypt)).status());
            assertEquals(1,count(IsolatedAuthTarget.users(),"id = @id AND password_hash = @hash",Map.of(
                "id",QueryParameterValue.string(vendor.getId().toString()),"hash",QueryParameterValue.string(bcrypt))));
        }
    }
    @Test @Order(12) void simultaneousSetupCanCommitOnlyOnePassword() throws Exception {
        var vendor=user("invite-race","CRM_VENDOR");var setup=session(vendor,"invite-race",Duration.ofMinutes(30));
        assertEquals(CREATED,run(()->planner.createVendorInvitation(op(),vendor,setup)).status());
        String firstHash=new BCryptPasswordEncoder(12).encode("synthetic-first-"+run);
        String secondHash=new BCryptPasswordEncoder(12).encode("synthetic-second-"+run);
        var attempts=race(()->planner.completeVendorPasswordSetup(op(),setup.getTokenHash(),setup.getCsrfTokenHash(),firstHash),
            ()->planner.completeVendorPasswordSetup(op(),setup.getTokenHash(),setup.getCsrfTokenHash(),secondHash));
        for(var attempt:attempts)assertAllowed(attempt,Set.of(SUCCESS,INVALID));
        assertEquals(1,attempts.stream().filter(value->value.status()==SUCCESS).count());
        assertEquals(INVALID,run(()->planner.completeVendorPasswordSetup(op(),setup.getTokenHash(),setup.getCsrfTokenHash(),bcrypt)).status());
        assertEquals(1,count(IsolatedAuthTarget.sessions(),"token_hash = @token AND used_at IS NOT NULL AND revoked_at IS NOT NULL",
            Map.of("token",QueryParameterValue.string(setup.getTokenHash()))));
    }
    private Fixture fixture(String label, Duration ttl) throws Exception {
        var created = run(() -> planner.createUser(op(), user(label, "CRM_VENDOR"), false));
        assertEquals(CREATED, created.status());
        var session = session(created.user(), label, ttl);
        assertEquals(CREATED, run(() -> planner.createSession(op(), created.user(), session)).status());
        return new Fixture(created.user(), session.getTokenFamilyId(), session.getTokenHash(), session.getCsrfTokenHash(), label);
    }
    private RefreshTokenSession session(IdentityUser owner, String label, Duration ttl) {
        var now = Instant.now();
        return new RefreshTokenSession(owner.getId(), UUID.randomUUID(), hash(label + "-old"), hash(label + "-csrf"), now, now.plus(ttl));
    }
    private AuthTransactionPlan rotation(Fixture f, String suffix) {
        return planner.rotate(op(), f.tokenHash(), f.csrfHash(), UUID.randomUUID(),
            hash(f.label() + "-" + suffix), hash(f.label() + "-csrf-" + suffix), Duration.ofDays(7));
    }
    private IdentityUser user(String label, String role) {
        var user = new IdentityUser("it+" + run + "-" + continuationTag + label + "@example.test", "it+" + run + "-" + continuationTag + label + "@example.test", bcrypt, true);
        user.addRole(new IdentityRole(role, role)); user.setDisplayName("Synthetic " + label); return user;
    }
    private UUID op() { return UUID.randomUUID(); }
    private String hash(String label) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((run + ":" + continuationTag + label).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Synthetic hashing unavailable."); }
    }
    private long familyCount(Fixture f, boolean active) throws Exception {
        return count(IsolatedAuthTarget.sessions(), "token_family_id = @family" + (active ? " AND revoked_at IS NULL" : ""),
            Map.of("family", QueryParameterValue.string(f.family().toString())));
    }
    private long count(String table, String predicate, Map<String, QueryParameterValue> parameters) throws Exception {
        if (!Set.of(IsolatedAuthTarget.users(), IsolatedAuthTarget.sessions()).contains(table)) throw new IllegalArgumentException();
        var query = QueryJobConfiguration.newBuilder("SELECT COUNT(*) AS total FROM " + table + " WHERE " + predicate)
            .setNamedParameters(parameters).setUseLegacySql(false).setUseQueryCache(false).setMaximumBytesBilled(100_000_000L).build();
        return first.query(query, JobId.newBuilder().setProject(IsolatedAuthTarget.PROJECT)
            .setLocation(IsolatedAuthTarget.LOCATION).setJob(UUID.randomUUID().toString()).build())
            .iterateAll().iterator().next().get("total").getLongValue();
    }
    private AuthTransactionResult run(Supplier<AuthTransactionPlan> factory) throws Exception {
        return committed(factory).result();
    }
    private Committed committed(Supplier<AuthTransactionPlan> factory) throws Exception {
        var plan = factory.get();
        receipt(plan);
        try { var result=adapter.submit(plan);outcome(plan,result);return new Committed(plan,result); }
        catch (IdentityPersistenceException exception) {
            if (!exception.code().equals("IDENTITY_TRANSACTION_CONFLICT")) throw exception;
            Thread.sleep(60_000); // explicit fresh operation only after confirmed aborted transaction
            var retry = factory.get();
            assertNotEquals(plan.jobId(), retry.jobId(), "An aborted job cannot be re-executed with its old ID.");
            receipt(retry);
            var result=adapter.submit(retry);outcome(retry,result);return new Committed(retry,result);
        }
    }
    record Committed(AuthTransactionPlan plan, AuthTransactionResult result) {}
    private List<Attempt> race(Supplier<AuthTransactionPlan> a, Supplier<AuthTransactionPlan> b) throws Exception {
        // Prepare/record BOTH plans before either worker can make a cloud call.
        var firstPlan = a.get(); var secondPlan = b.get();
        receipt(firstPlan); receipt(secondPlan);
        return IsolatedRaceRunner.run(() -> attempt(adapter, firstPlan),
            () -> attempt(new BigQueryAuthTransactionAdapter(second), secondPlan));
    }
    private Attempt attempt(BigQueryAuthTransactionAdapter adapter, AuthTransactionPlan plan) throws Exception {
        try { var result=adapter.submit(plan);outcome(plan,result);return new Attempt(result.status(),false); }
        catch (IdentityPersistenceException exception) {
            if (!exception.code().equals("IDENTITY_TRANSACTION_CONFLICT")) throw exception;
            return new Attempt(null, true);
        }
    }
    private void receipt(AuthTransactionPlan plan) throws Exception {
        var directory = Path.of("target", "auth-verification", run);
        Files.createDirectories(directory);
        // Job identity only. Never write parameter values, hashes, passwords or raw tokens.
        Files.writeString(directory.resolve(plan.jobId().getJob() + ".receipt"),
            "project=" + IsolatedAuthTarget.PROJECT + "\nlocation=" + IsolatedAuthTarget.LOCATION
                + "\njob=" + plan.jobId().getJob() + "\ndeadline=" + plan.deadline() + "\nrun=" + run
                + "\nusers_table=" + IsolatedAuthTarget.USERS + "\nsessions_table=" + IsolatedAuthTarget.SESSIONS
                + "\noperation=" + plan.query().getNamedParameters().get("operation").getValue()
                + "\nsubject_id=" + plan.query().getNamedParameters().get("id").getValue()
                + "\nnew_session_id=" + plan.query().getNamedParameters().get("new_id").getValue()
                + "\nfamily_id=" + plan.query().getNamedParameters().get("family_id").getValue() + "\n",
            StandardOpenOption.CREATE_NEW);
    }
    private void outcome(AuthTransactionPlan plan,AuthTransactionResult result) throws Exception {
        var file=Path.of("target","auth-verification",run,plan.jobId().getJob()+".outcome");
        Files.writeString(file,"job="+plan.jobId().getJob()+"\nstatus="+result.status()
            +"\nsubject_id="+(result.user()==null?"":result.user().getId())+"\n",StandardOpenOption.CREATE_NEW);
    }
    record Fixture(IdentityUser user, UUID family, String tokenHash, String csrfHash, String label) {
        @Override public String toString() { return "Fixture[data=REDACTED]"; }
    }
    record Attempt(AuthTransactionResult.Status status, boolean conflict) {}
    static void assertAllowed(Attempt attempt, Set<AuthTransactionResult.Status> allowed) {
        if (attempt.conflict()) assertNull(attempt.status());
        else assertTrue(allowed.contains(attempt.status()), "Unexpected transaction outcome in race.");
    }
}
