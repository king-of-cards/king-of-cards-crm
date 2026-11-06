package com.kingofcards.identity.bigquery.transactions;

import java.util.List;
import java.util.concurrent.*;

/** Both workers must be waiting BEFORE release. No lock protects any database operation. */
final class IsolatedRaceRunner {
    static <T> List<T> run(Callable<T> first, Callable<T> second) throws Exception {
        return run(first,second,java.time.Duration.ofMinutes(6));
    }
    static <T> List<T> run(Callable<T> first, Callable<T> second, java.time.Duration timeout) throws Exception {
        if(timeout.isZero() || timeout.isNegative())throw new IllegalArgumentException("Positive race timeout required.");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2, task -> {
            var thread=new Thread(task,"auth-verification-race");thread.setDaemon(true);return thread;
        });
        try {
            var a = executor.submit(() -> { ready.countDown(); start.await(); return first.call(); });
            var b = executor.submit(() -> { ready.countDown(); start.await(); return second.call(); });
            try {
                if (!ready.await(15, TimeUnit.SECONDS)) throw new TimeoutException("Race workers did not reach the start barrier.");
                start.countDown();
                long deadline=System.nanoTime()+timeout.toNanos();
                var firstResult=a.get(timeout.toNanos(),TimeUnit.NANOSECONDS);
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new TimeoutException("Auth race outcome is unknown; reconcile original jobs.");
                return List.of(firstResult,b.get(remaining,TimeUnit.NANOSECONDS));
            } finally {
                a.cancel(true); b.cancel(true);
                // Never cancels a BigQuery job: interrupted client operations remain UNKNOWN.
            }
        } finally {
            // Executor.close() waits indefinitely for SDK calls that ignore interruption.
            // Interrupt local workers only; original cloud jobs require read-only reconciliation.
            executor.shutdownNow();
        }
    }
}
