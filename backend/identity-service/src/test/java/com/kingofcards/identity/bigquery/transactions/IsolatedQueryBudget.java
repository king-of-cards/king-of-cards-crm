package com.kingofcards.identity.bigquery.transactions;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-harness job budget only; never used as an authentication/concurrency lock. */
final class IsolatedQueryBudget {
    static final int DEFAULT_MAX_JOBS=150;
    private final int maximum;
    private final AtomicInteger submitted=new AtomicInteger();
    IsolatedQueryBudget(int maximum) {
        if(maximum<1 || maximum>150)throw new IllegalArgumentException("Auth verification job budget must be 1..150.");
        this.maximum=maximum;
    }
    void reserve() {
        while(true){int value=submitted.get();if(value>=maximum)throw new IllegalStateException("ISOLATED_QUERY_JOB_LIMIT_REACHED");
            if(submitted.compareAndSet(value,value+1))return;}
    }
    int used(){return submitted.get();}
}
