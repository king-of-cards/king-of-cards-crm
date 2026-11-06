package com.kingofcards.identity.bigquery.transactions;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.opentest4j.TestAbortedException;

/** Harness control only: never used for datastore serialization. */
final class IsolatedSuiteGuard implements BeforeEachCallback, TestWatcher {
    private boolean stopped;
    void check() { if (stopped) throw new TestAbortedException("Earlier isolated test failed; no further test operations are permitted."); }
    @Override public void beforeEach(ExtensionContext context) { check(); }
    @Override public void testFailed(ExtensionContext context, Throwable cause) { stopped = true; }
    @Override public void testAborted(ExtensionContext context, Throwable cause) { stopped = true; }
}
