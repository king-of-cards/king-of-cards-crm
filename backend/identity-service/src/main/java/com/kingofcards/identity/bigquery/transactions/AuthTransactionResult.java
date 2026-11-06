package com.kingofcards.identity.bigquery.transactions;

import com.kingofcards.identity.entity.IdentityUser;

/** Committed business result, including denials whose revocation MUST already have committed. */
public record AuthTransactionResult(Status status, IdentityUser user) {
    public enum Status { CREATED, UPDATED, SUCCESS, REVOKED, DUPLICATE_EMAIL, ID_COLLISION,
        BOOTSTRAP_NOT_EMPTY, STALE_ACCOUNT, NOT_FOUND, INVALID, REUSED, CSRF_REJECTED }
    @Override public String toString() { return "AuthTransactionResult[status=" + status + ", data=REDACTED]"; }
}
