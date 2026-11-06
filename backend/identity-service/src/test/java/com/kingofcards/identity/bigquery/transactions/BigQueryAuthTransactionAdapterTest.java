package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.*;
import java.util.*;
import com.google.cloud.bigquery.*;
import com.kingofcards.identity.exception.IdentityPersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.kingofcards.identity.entity.IdentityUser;

class BigQueryAuthTransactionAdapterTest {
    @Test void sdkResultFailureIsAConflictOnlyAfterOriginalJobConfirmsAbort() throws Exception {
        var cached = done(plan,"REVOKED");
        when(cached.getQueryResults()).thenThrow(new BigQueryException(400,"Result fetch failed"));
        var terminal = job(plan,status(JobStatus.State.DONE,new BigQueryError("invalidQuery",null,
            "Transaction is aborted due to concurrent update against table")));
        when(client.create(any(JobInfo.class))).thenReturn(cached);
        when(client.getJob(plan.jobId())).thenReturn(terminal);
        assertEquals("IDENTITY_TRANSACTION_CONFLICT",assertThrows(IdentityPersistenceException.class,
            ()->adapter.submit(plan)).code());
        verify(client,times(1)).create(any(JobInfo.class));
        verify(client,times(1)).getJob(plan.jobId());
    }
    @Test void sdkConflictTextWithoutConfirmedOriginalAbortRemainsUnknown() throws Exception {
        var cached=done(plan,"REVOKED");
        when(cached.getQueryResults()).thenThrow(new BigQueryException(400,
            "Transaction is aborted due to concurrent update against table"));
        when(client.create(any(JobInfo.class))).thenReturn(cached);
        when(client.getJob(plan.jobId())).thenReturn(null);
        assertEquals("IDENTITY_COMMIT_OUTCOME_UNKNOWN",assertThrows(IdentityPersistenceException.class,
            ()->adapter.submit(plan)).code());
        verify(client,times(1)).create(any(JobInfo.class));
    }
    @ParameterizedTest @ValueSource(strings={"status","configuration"})
    void sdkStatisticsOnlyDuplicateJobIsReloadedWithoutResubmitting(String missing) throws Exception {
        var partial=job(plan,status(JobStatus.State.DONE,null));
        if(missing.equals("status"))when(partial.getStatus()).thenReturn(null);
        else when(partial.getConfiguration()).thenReturn(null);
        var complete=done(plan,"REVOKED");
        when(client.create(any(JobInfo.class))).thenReturn(partial);
        when(client.getJob(plan.jobId())).thenReturn(complete);
        assertEquals(AuthTransactionResult.Status.REVOKED,adapter.submit(plan).status());
        verify(client,times(1)).create(any(JobInfo.class));verify(client,times(1)).getJob(plan.jobId());
    }
    @Test void partialJobRecoveryDoesNotAcceptDifferentPayloadOrMissingStatus() throws Exception {
        var partial=job(plan,null); when(client.create(any(JobInfo.class))).thenReturn(partial);
        when(client.getJob(plan.jobId())).thenReturn(partial);
        assertEquals("IDENTITY_COMMIT_OUTCOME_UNKNOWN",assertThrows(IdentityPersistenceException.class,
            ()->adapter.submit(plan)).code());
        var full=done(plan,"REVOKED");when(full.getConfiguration()).thenReturn(rotation().query());
        when(client.getJob(plan.jobId())).thenReturn(full);
        assertEquals("IDENTITY_IDEMPOTENCY_KEY_REUSED",assertThrows(IdentityPersistenceException.class,
            ()->adapter.submit(plan)).code());
    }
    @Test void wrongPartialJobIdFailsBeforeAnyRecoveryLookup() {
        var partial=job(plan,null);
        when(partial.getJobId()).thenReturn(JobId.of("wrong-job-id"));
        when(client.create(any(JobInfo.class))).thenReturn(partial);
        assertEquals("IDENTITY_IDEMPOTENCY_KEY_REUSED",assertThrows(IdentityPersistenceException.class,
            ()->adapter.submit(plan)).code());
        verify(client,never()).getJob(any(JobId.class));
    }
    @Test void historicalReviewWorksAfterDeadlineWithoutReturningCredentialsOrResubmitting() throws Exception {
        var completed=done(plan,"REVOKED");
        when(client.getJob(plan.jobId())).thenReturn(completed);
        var expired=new BigQueryAuthTransactionAdapter(client,Clock.fixed(plan.deadline().plusSeconds(60),ZoneOffset.UTC),d->{},1);
        var receipt=expired.reviewOutcome(plan);
        assertEquals(AuthTransactionResult.Status.REVOKED,receipt.committedStatus());
        assertEquals(plan.jobId().getJob(),receipt.jobId());
        assertEquals(2,receipt.getClass().getRecordComponents().length);
        assertFalse(receipt.toString().contains(AuthTransactionPlannerTest.HASH));
        verify(client).getJob(plan.jobId()); verify(client,never()).create(any(JobInfo.class));
        assertThrows(IdentityPersistenceException.class,()->expired.submit(plan));
        assertThrows(IdentityPersistenceException.class,()->expired.recover(plan));
    }
    @Test void historicalReviewStillRejectsMissingOrMismatchedJobs() throws Exception {
        assertThrows(IdentityPersistenceException.class,()->adapter.reviewOutcome(plan));
        var wrong=done(plan,"REVOKED"); when(wrong.getConfiguration()).thenReturn(rotation().query());
        when(client.getJob(plan.jobId())).thenReturn(wrong);
        assertEquals("IDENTITY_IDEMPOTENCY_KEY_REUSED",assertThrows(IdentityPersistenceException.class,
            ()->adapter.reviewOutcome(plan)).code());
        verify(client,never()).create(any(JobInfo.class));
    }
    @ParameterizedTest @ValueSource(strings={"CREATE_USER","CREATE_SESSION","ROTATE","REVOKE_TOKEN","REVOKE_FAMILY"})
    void simultaneousClientOperationsKeepJobBindingsAndFailClosedOnSimulatedConflicts(String operation) throws Exception {
        // Scripted SDK outcomes test adapter behavior, NOT the BigQuery locking engine.
        var first=operationPlan(operation); var second=operationPlan(operation);
        String success=switch(operation){case "CREATE_USER","CREATE_SESSION"->"CREATED";case "ROTATE"->"SUCCESS";default->"REVOKED";};
        var winningJob=Set.of("CREATED","SUCCESS").contains(success)?completedWithUser(first,success):done(first,success);
        var conflictingJob=job(second,status(JobStatus.State.DONE,
            new BigQueryError("invalidQuery",null,"Transaction is aborted due to concurrent update against table")));
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        when(client.create(any(JobInfo.class))).thenAnswer(invocation->{
            barrier.await(3,java.util.concurrent.TimeUnit.SECONDS);
            return invocation.getArgument(0,JobInfo.class).getJobId().equals(first.jobId())?winningJob:conflictingJob;
        });
        var results=IsolatedRaceRunner.run(()->adapter.submit(first).status().name(),()->{
            try { adapter.submit(second); return "UNEXPECTED_SUCCESS"; }
            catch(IdentityPersistenceException failure){return failure.code();}
        });
        assertEquals(List.of(success,"IDENTITY_TRANSACTION_CONFLICT"),results);
        verify(client,times(2)).create(any(JobInfo.class)); verify(client,never()).getJob(any(JobId.class));
    }
    private AuthTransactionPlan operationPlan(String operation) {
        return switch(operation){
            case "CREATE_USER"->planner().createUser(UUID.randomUUID(),fixture(),false);
            case "CREATE_SESSION"->planner().createSession(UUID.randomUUID(),fixture(),new com.kingofcards.identity.entity.RefreshTokenSession(
                userId,UUID.randomUUID(),AuthTransactionPlannerTest.HASH,AuthTransactionPlannerTest.CSRF,
                AuthTransactionPlannerTest.NOW,AuthTransactionPlannerTest.NOW.plusSeconds(60)));
            case "ROTATE"->rotation();
            case "REVOKE_TOKEN"->planner().logout(UUID.randomUUID(),AuthTransactionPlannerTest.HASH,AuthTransactionPlannerTest.CSRF);
            default->planner().revokeFamily(UUID.randomUUID(),userId,UUID.randomUUID());
        };
    }
    @Test void concurrentReplayAndLogoutReturnCommittedRevocationOutcomesWithoutRetry() throws Exception {
        var replay=rotation(); var logout=operationPlan("REVOKE_TOKEN");
        var replayJob=done(replay,"REUSED"); var logoutJob=done(logout,"REVOKED");
        when(client.create(any(JobInfo.class))).thenAnswer(invocation->
            invocation.getArgument(0,JobInfo.class).getJobId().equals(replay.jobId())?replayJob:logoutJob);
        assertEquals(List.of("REUSED","REVOKED"),IsolatedRaceRunner.run(
            ()->adapter.submit(replay).status().name(),()->adapter.submit(logout).status().name()));
        verify(client,times(2)).create(any(JobInfo.class)); verify(client,never()).getJob(any(JobId.class));
    }
    @Test void concurrentProvisioningPreservesDuplicateEmailDenialAsACommittedBusinessResult() throws Exception {
        var first=operationPlan("CREATE_USER"); var second=operationPlan("CREATE_USER");
        var created=completedWithUser(first,"CREATED"); var duplicate=done(second,"DUPLICATE_EMAIL");
        when(client.create(any(JobInfo.class))).thenAnswer(invocation->
            invocation.getArgument(0,JobInfo.class).getJobId().equals(first.jobId())?created:duplicate);
        assertEquals(List.of("CREATED","DUPLICATE_EMAIL"),IsolatedRaceRunner.run(
            ()->adapter.submit(first).status().name(),()->adapter.submit(second).status().name()));
        verify(client,times(2)).create(any(JobInfo.class)); verify(client,never()).getJob(any(JobId.class));
    }
    @Test void newLoginSessionCannotAcceptDisabledSuccessFromSdk() throws Exception {
        var sessionPlan=operationPlan("CREATE_SESSION"); var job=done(sessionPlan,"CREATED");
        var disabled=userRow("CREATED",false);
        var result=mock(TableResult.class); when(result.iterateAll()).thenReturn(List.of(disabled));
        when(job.getQueryResults()).thenReturn(result); when(client.create(any(JobInfo.class))).thenReturn(job);
        assertThrows(IdentityPersistenceException.class,()->adapter.submit(sessionPlan));
    }
    private Job completedWithUser(AuthTransactionPlan plan,String status) throws Exception {
        var job=done(plan,status); var row=userRow(status,true); var results=mock(TableResult.class);
        when(results.iterateAll()).thenReturn(List.of(row)); when(job.getQueryResults()).thenReturn(results);
        return job;
    }
    final UUID userId=UUID.fromString("00000000-0000-4000-8000-000000000001");
    IdentityUser fixture() { return IdentityUser.restore(userId,"it+synthetic@example.test","Synthetic Vendor",
        AuthTransactionPlannerTest.BCRYPT,"CRM_VENDOR",true,AuthTransactionPlannerTest.NOW,AuthTransactionPlannerTest.NOW); }
    AuthTransactionPlanner planner() { return new AuthTransactionPlanner(Clock.fixed(AuthTransactionPlannerTest.NOW,ZoneOffset.UTC)); }
    AuthTransactionPlan rotation() { return planner().rotate(UUID.randomUUID(),AuthTransactionPlannerTest.HASH,
        AuthTransactionPlannerTest.CSRF,UUID.randomUUID(),AuthTransactionPlannerTest.NEXT,AuthTransactionPlannerTest.HASH,Duration.ofDays(7)); }
    BigQuery client;
    BigQueryAuthTransactionAdapter adapter;
    AuthTransactionPlan plan;
    @BeforeEach void setup() {
        client = mock(BigQuery.class);
        adapter = new BigQueryAuthTransactionAdapter(client, Clock.fixed(AuthTransactionPlannerTest.NOW, ZoneOffset.UTC), d -> {}, 2);
        plan = new AuthTransactionPlanner(Clock.fixed(AuthTransactionPlannerTest.NOW, ZoneOffset.UTC))
            .logout(UUID.randomUUID(), AuthTransactionPlannerTest.HASH, AuthTransactionPlannerTest.CSRF);
    }
    @Test void committedDenialsAreReturnedAsBusinessResultsNotThrownBeforeCommit() throws Exception {
        for (String status : List.of("REUSED", "INVALID", "CSRF_REJECTED", "DUPLICATE_EMAIL", "BOOTSTRAP_NOT_EMPTY", "STALE_ACCOUNT", "REVOKED")) {
            plan=switch(status) {
                case "REUSED" -> rotation();
                case "DUPLICATE_EMAIL" -> planner().createUser(UUID.randomUUID(),fixture(),false);
                case "BOOTSTRAP_NOT_EMPTY" -> planner().createUser(UUID.randomUUID(),IdentityUser.restore(userId,
                    "it+synthetic@example.test",null,AuthTransactionPlannerTest.BCRYPT,"CRM_ADMIN",true,
                    AuthTransactionPlannerTest.NOW,AuthTransactionPlannerTest.NOW),true);
                case "STALE_ACCOUNT" -> planner().updateAccount(UUID.randomUUID(),fixture());
                default -> planner().logout(UUID.randomUUID(),AuthTransactionPlannerTest.HASH,AuthTransactionPlannerTest.CSRF);
            };
            var job = done(plan, status);
            when(client.create(any(JobInfo.class))).thenReturn(job);
            assertEquals(status, adapter.submit(plan).status().name());
        }
        verify(client, never()).getJob(any(JobId.class));
    }
    @Test void uncertainSubmissionRecoversSameJobWithoutSecondInsert() throws Exception {
        when(client.create(any(JobInfo.class))).thenThrow(new BigQueryException(504, "synthetic-sensitive-transport-error"));
        var recovered = done(plan, "REVOKED");
        when(client.getJob(plan.jobId())).thenReturn(recovered);
        assertEquals(AuthTransactionResult.Status.REVOKED, adapter.submit(plan).status());
        verify(client, times(1)).create(argThat((JobInfo info) -> info.getJobId().equals(plan.jobId())));
        verify(client).getJob(plan.jobId());
    }
    @Test void explicitSamePlanRetryReconcilesDuplicateJobId() throws Exception {
        var job = done(plan, "REVOKED");
        when(client.create(any(JobInfo.class))).thenReturn(job).thenThrow(new BigQueryException(409, "already exists"));
        when(client.getJob(plan.jobId())).thenReturn(job);
        assertEquals(adapter.submit(plan).status(), adapter.submit(plan).status());
        verify(client, times(2)).create(argThat((JobInfo info) -> info.getJobId().equals(plan.jobId())));
    }
    @Test void missingJobAfterTimeoutIsUnknownNotRollbackOrNewJob() {
        when(client.create(any(JobInfo.class))).thenThrow(new BigQueryException(504, "sensitive"));
        var failure = assertThrows(IdentityPersistenceException.class, () -> adapter.submit(plan));
        assertEquals("IDENTITY_COMMIT_OUTCOME_UNKNOWN", failure.code());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("sensitive"));
        verify(client, times(1)).create(any(JobInfo.class));
        verify(client, times(1)).getJob(plan.jobId());
    }
    @Test void recoveryNeverSubmitsEvenWhenJobIsMissingOrForbidden() {
        assertThrows(IdentityPersistenceException.class, () -> adapter.recover(plan));
        when(client.getJob(plan.jobId())).thenThrow(new BigQueryException(403, "sensitive IAM details"));
        assertThrows(IdentityPersistenceException.class, () -> adapter.recover(plan));
        verify(client, never()).create(any(JobInfo.class));
    }
    @Test void differentPayloadWithSameIdIsRejected() throws Exception {
        var other = new AuthTransactionPlanner(Clock.fixed(AuthTransactionPlannerTest.NOW, ZoneOffset.UTC))
            .logout(UUID.randomUUID(), AuthTransactionPlannerTest.NEXT, AuthTransactionPlannerTest.CSRF);
        var job = done(plan, "REVOKED");
        when(job.getConfiguration()).thenReturn(other.query());
        when(client.getJob(plan.jobId())).thenReturn(job);
        assertEquals("IDENTITY_IDEMPOTENCY_KEY_REUSED", assertThrows(IdentityPersistenceException.class,
            () -> adapter.recover(plan)).code());
        verify(client, never()).create(any(JobInfo.class));
    }
    @Test void explicitNamePresenceCannotBeChangedForAnExistingJob() throws Exception {
        var job = done(plan, "REVOKED");
        var changed = new HashMap<>(plan.query().getNamedParameters());
        changed.put("name_present", QueryParameterValue.bool(true));
        when(job.getConfiguration()).thenReturn(plan.query().toBuilder().setNamedParameters(changed).build());
        when(client.getJob(plan.jobId())).thenReturn(job);
        assertEquals("IDENTITY_IDEMPOTENCY_KEY_REUSED", assertThrows(IdentityPersistenceException.class,
            () -> adapter.recover(plan)).code());
        verify(client, never()).create(any(JobInfo.class));
    }
    @Test void confirmedTransactionConflictDoesNotAutomaticallyRetry() {
        var job = job(plan, status(JobStatus.State.DONE, new BigQueryError("invalidQuery", null, "Transaction is aborted due to concurrent update against table")));
        when(client.create(any(JobInfo.class))).thenReturn(job);
        assertEquals("IDENTITY_TRANSACTION_CONFLICT", assertThrows(IdentityPersistenceException.class,
            () -> adapter.submit(plan)).code());
        verify(client, times(1)).create(any(JobInfo.class));
    }
    @Test void arbitraryScriptFailureDoesNotClaimRollbackOrReplay() {
        var job = job(plan, status(JobStatus.State.DONE, new BigQueryError("accessDenied", null, "sensitive table details")));
        when(client.create(any(JobInfo.class))).thenReturn(job);
        assertEquals("IDENTITY_TRANSACTION_FAILED_REVIEW_REQUIRED", assertThrows(IdentityPersistenceException.class,
            () -> adapter.submit(plan)).code());
        verify(client, times(1)).create(any(JobInfo.class));
    }
    @Test void exhaustedScriptBudgetIsExplicitAndNeverRetriedWithAHigherLimit() {
        var job = job(plan, status(JobStatus.State.DONE, new BigQueryError("invalidQuery", null,
            "Query exceeded limit for bytes billed: 5628160. 10485760 or higher required. at [123:6]")));
        when(client.create(any(JobInfo.class))).thenReturn(job);
        assertEquals("IDENTITY_QUERY_BUDGET_EXCEEDED", assertThrows(IdentityPersistenceException.class,
            () -> adapter.submit(plan)).code());
        verify(client, times(1)).create(any(JobInfo.class));
        verify(client, never()).getJob(any(JobId.class));
    }
    @Test void unknownResultAfterSuccessfulCommitIsNotReplayed() throws Exception {
        var job = done(plan, "REVOKED");
        when(job.getQueryResults()).thenThrow(new BigQueryException(503, "lost committed result"));
        when(client.create(any(JobInfo.class))).thenReturn(job);
        assertEquals("IDENTITY_COMMIT_OUTCOME_UNKNOWN", assertThrows(IdentityPersistenceException.class,
            () -> adapter.submit(plan)).code());
        verify(client, times(1)).create(any(JobInfo.class));
    }
    @Test void pollingUsesSameJobAndTimesOutClosed() {
        var job = job(plan, status(JobStatus.State.RUNNING, null));
        when(client.create(any(JobInfo.class))).thenReturn(job);
        when(client.getJob(plan.jobId())).thenReturn(job);
        assertThrows(IdentityPersistenceException.class, () -> adapter.submit(plan));
        verify(client, times(1)).create(any(JobInfo.class));
        verify(client, times(2)).getJob(plan.jobId());
    }
    @Test void expiredPlanNeverMakesAnRpc() {
        var expired = new BigQueryAuthTransactionAdapter(client,
            Clock.fixed(plan.deadline(), ZoneOffset.UTC), d -> {}, 1);
        assertThrows(IdentityPersistenceException.class, () -> expired.submit(plan));
        verifyNoInteractions(client);
    }
    @Test void committedUserAndSessionResultsPreserveSubjectRoleAndAuditTimes() throws Exception {
        for (String status : List.of("CREATED", "UPDATED", "SUCCESS")) {
            plan=switch(status) {
                case "CREATED" -> planner().createUser(UUID.randomUUID(),fixture(),false);
                case "UPDATED" -> planner().updateAccount(UUID.randomUUID(),fixture());
                default -> rotation();
            };
            var job = done(plan, status);
            var row = userRow(status, true);
            var results = mock(TableResult.class);
            when(results.iterateAll()).thenReturn(List.of(row));
            when(job.getQueryResults()).thenReturn(results);
            when(client.create(any(JobInfo.class))).thenReturn(job);
            var result = adapter.submit(plan);
            assertEquals("CRM_VENDOR", result.user().getRoles().iterator().next().getCode());
            assertEquals("it+synthetic@example.test", result.user().getEmail());
            assertEquals(AuthTransactionPlannerTest.NOW, result.user().getCreatedAt());
            assertFalse(result.toString().contains(AuthTransactionPlannerTest.BCRYPT));
        }
    }
    @Test void disabledSuccessAndMultipleOrMissingResultRowsFailClosed() throws Exception {
        plan=rotation();
        var job = done(plan, "SUCCESS");
        var disabledRow = userRow("SUCCESS", false);
        var results = mock(TableResult.class);
        when(results.iterateAll()).thenReturn(List.of(disabledRow));
        when(job.getQueryResults()).thenReturn(results);
        when(client.create(any(JobInfo.class))).thenReturn(job);
        assertThrows(IdentityPersistenceException.class, () -> adapter.submit(plan));
        when(results.iterateAll()).thenReturn(List.of());
        assertThrows(IdentityPersistenceException.class, () -> adapter.submit(plan));
        when(results.iterateAll()).thenReturn(List.of(disabledRow, disabledRow));
        assertThrows(IdentityPersistenceException.class, () -> adapter.submit(plan));
    }
    @Test void interruptionPreservesFlagAndDoesNotCancelOrResubmit() {
        var job = job(plan, status(JobStatus.State.RUNNING, null));
        when(client.create(any(JobInfo.class))).thenReturn(job);
        var interrupted = new BigQueryAuthTransactionAdapter(client, Clock.fixed(AuthTransactionPlannerTest.NOW, ZoneOffset.UTC),
            d -> { throw new InterruptedException(); }, 1);
        try {
            assertThrows(IdentityPersistenceException.class, () -> interrupted.submit(plan));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        verify(client, times(1)).create(any(JobInfo.class));
        verify(client, never()).cancel(any(JobId.class));
    }
    private Job done(AuthTransactionPlan plan, String status) throws Exception {
        var job = job(plan, status(JobStatus.State.DONE, null));
        var row = mock(FieldValueList.class);
        when(row.get("status")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE, status));
        var results = mock(TableResult.class);
        when(results.iterateAll()).thenReturn(List.of(row));
        when(job.getQueryResults()).thenReturn(results);
        return job;
    }
    private JobStatus status(JobStatus.State state, BigQueryError error) {
        var status = mock(JobStatus.class);
        when(status.getState()).thenReturn(state);
        when(status.getError()).thenReturn(error);
        return status;
    }
    private FieldValueList userRow(String status, boolean enabled) {
        var values = new HashMap<String, String>();
        values.put("status", status); values.put("id", userId.toString()); values.put("name", "Synthetic Vendor");
        values.put("email", "it+synthetic@example.test"); values.put("password_hash", AuthTransactionPlannerTest.BCRYPT);
        values.put("role", "CRM_VENDOR"); values.put("enabled", Boolean.toString(enabled));
        values.put("created_at", AuthTransactionPlannerTest.NOW.getEpochSecond()+".000000");
        values.put("updated_at", AuthTransactionPlannerTest.NOW.getEpochSecond()+(status.equals("UPDATED")?".000001":".000000"));
        var row = mock(FieldValueList.class);
        when(row.get(anyString())).thenAnswer(call -> FieldValue.of(FieldValue.Attribute.PRIMITIVE,
            values.get(call.getArgument(0, String.class))));
        return row;
    }
    @Test void operationIncompatibleSuccessIsUnknownRatherThanAccepted() throws Exception {
        var unexpected=done(plan,"CREATED");when(client.create(any(JobInfo.class))).thenReturn(unexpected);
        assertEquals("IDENTITY_COMMIT_OUTCOME_UNKNOWN",assertThrows(IdentityPersistenceException.class,()->adapter.submit(plan)).code());
        verify(client,times(1)).create(any(JobInfo.class));
    }
    @Test void accountUpdateMustAdvanceVersionAndPreserveAuditOrdering() throws Exception {
        plan=planner().updateAccount(UUID.randomUUID(),fixture());
        var row=userRow("UPDATED",true);var job=done(plan,"UPDATED");var results=mock(TableResult.class);
        when(results.iterateAll()).thenReturn(List.of(row));when(job.getQueryResults()).thenReturn(results);
        when(client.create(any(JobInfo.class))).thenReturn(job);
        when(row.get("updated_at")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,AuthTransactionPlannerTest.NOW.getEpochSecond()+".000000"));
        assertThrows(IdentityPersistenceException.class,()->adapter.submit(plan));
        when(row.get("created_at")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,AuthTransactionPlannerTest.NOW.plusSeconds(10).getEpochSecond()+".000000"));
        assertThrows(IdentityPersistenceException.class,()->adapter.submit(plan));
    }
    @ParameterizedTest @ValueSource(strings={"id","email","password_hash","role","name","enabled"})
    void successfulUserMustMatchTheAtomicRequest(String field) throws Exception {
        plan=planner().createUser(UUID.randomUUID(),fixture(),false);
        var row=userRow("CREATED",true);
        String wrong=switch(field){case "id"->UUID.randomUUID().toString();case "email"->"other@example.test";
            case "password_hash"->"$2a$12$"+"B".repeat(53);case "role"->"CRM_ADMIN";case "enabled"->"false";default->"Other name";};
        when(row.get(field)).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,wrong));
        var job=done(plan,"CREATED");var results=mock(TableResult.class);when(results.iterateAll()).thenReturn(List.of(row));
        when(job.getQueryResults()).thenReturn(results);when(client.create(any(JobInfo.class))).thenReturn(job);
        assertThrows(IdentityPersistenceException.class,()->adapter.submit(plan));verify(client,times(1)).create(any(JobInfo.class));
    }
    @Test void jobRecoveryMustPreserveBillingAndQueryControls() throws Exception {
        var job=done(plan,"REVOKED");when(client.getJob(plan.jobId())).thenReturn(job);
        for(var config:List.of(plan.query().toBuilder().setMaximumBytesBilled(536_870_912L).build(),
                plan.query().toBuilder().setUseLegacySql(true).build(),plan.query().toBuilder().setUseQueryCache(true).build(),
                plan.query().toBuilder().setJobTimeoutMs(90_000L).build())) {
            when(job.getConfiguration()).thenReturn(config);
            assertEquals("IDENTITY_IDEMPOTENCY_KEY_REUSED",assertThrows(IdentityPersistenceException.class,()->adapter.recover(plan)).code());
        }
        verify(client,never()).create(any(JobInfo.class));
    }
    @Test void setupSuccessMustConfirmTheRequestedPasswordHash() throws Exception {
        plan=planner().completeVendorPasswordSetup(UUID.randomUUID(),AuthTransactionPlannerTest.HASH,
            AuthTransactionPlannerTest.CSRF,AuthTransactionPlannerTest.BCRYPT);
        var row=userRow("SUCCESS",true);var job=done(plan,"SUCCESS");var results=mock(TableResult.class);
        when(results.iterateAll()).thenReturn(List.of(row));when(job.getQueryResults()).thenReturn(results);
        when(client.create(any(JobInfo.class))).thenReturn(job);
        assertEquals(AuthTransactionResult.Status.SUCCESS,adapter.submit(plan).status());
        when(row.get("password_hash")).thenReturn(FieldValue.of(FieldValue.Attribute.PRIMITIVE,"$2a$12$"+"B".repeat(53)));
        assertThrows(IdentityPersistenceException.class,()->adapter.submit(plan));
    }
    private Job job(AuthTransactionPlan plan, JobStatus status) {
        var job = mock(Job.class);
        when(job.getJobId()).thenReturn(plan.jobId());
        when(job.getConfiguration()).thenReturn(plan.query());
        when(job.getStatus()).thenReturn(status);
        return job;
    }
}
