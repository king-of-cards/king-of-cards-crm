package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

class IsolatedHarnessSafetyTest {
    @Test void uninterruptibleSdkWorkerCannotMakeRaceShutdownUnbounded() {
        var release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<String> blocked=()->{
            while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
            return "finished";
        };
        long start=System.nanoTime();
        try {
            assertThrows(java.util.concurrent.TimeoutException.class,()->IsolatedRaceRunner.run(
                blocked,blocked,java.time.Duration.ofMillis(30)));
            assertTrue(System.nanoTime()-start<java.time.Duration.ofSeconds(2).toNanos());
        } finally { release.countDown(); }
    }
    @Test void queryJobBudgetIsBoundedBeforeClientsAndCannotBeRaisedAutomatically() {
        assertEquals(150,IsolatedBigQueryAuthIT.approvedJobBudget(java.util.Map.of()));
        for(String bad:java.util.List.of("0","151","201","2147483647","invalid"))
            assertThrows(IllegalArgumentException.class,()->IsolatedBigQueryAuthIT.approvedJobBudget(java.util.Map.of("KOC_AUTH_IT_MAX_JOBS",bad)));
        var budget=new IsolatedQueryBudget(2);budget.reserve();budget.reserve();
        assertThrows(IllegalStateException.class,budget::reserve);assertEquals(2,budget.used());
    }
    @Test void failurePreventsEveryLaterTestFromRunning() {
        var guard = new IsolatedSuiteGuard();
        assertDoesNotThrow(guard::check);
        guard.testFailed(null, new IllegalStateException("unknown commit"));
        assertThrows(TestAbortedException.class, guard::check);
        assertThrows(TestAbortedException.class, guard::check);
    }
    @Test void abortedTestAlsoStopsLaterMutations() {
        var guard = new IsolatedSuiteGuard();
        guard.testAborted(null, new TestAbortedException());
        assertThrows(TestAbortedException.class, guard::check);
    }
    @Test void raceWorkersReallyExecuteConcurrently() throws Exception {
        var bothExecuting = new CyclicBarrier(2);
        var results = IsolatedRaceRunner.run(() -> { bothExecuting.await(5, TimeUnit.SECONDS); return "first"; },
            () -> { bothExecuting.await(5, TimeUnit.SECONDS); return "second"; });
        assertEquals(Set.of("first", "second"), Set.copyOf(results));
    }
    @Test void raceChecksRejectUnexpectedDenialsInsteadOfMaskingThem() {
        var status = AuthTransactionResult.Status.INVALID;
        assertThrows(AssertionError.class, () -> IsolatedBigQueryAuthIT.assertAllowed(
            new IsolatedBigQueryAuthIT.Attempt(status, false), Set.of(AuthTransactionResult.Status.CREATED)));
        assertDoesNotThrow(() -> IsolatedBigQueryAuthIT.assertAllowed(
            new IsolatedBigQueryAuthIT.Attempt(null, true), Set.of(AuthTransactionResult.Status.CREATED)));
    }
}
