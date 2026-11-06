package com.kingofcards.identity.bigquery.production;

import com.kingofcards.identity.entity.IdentityUser;

/** Committed business result, including denials whose revocation MUST already have committed. */
public record ProductionAuthResult(Status status, IdentityUser user) {
    public enum Status { CREATED, UPDATED, SUCCESS, REVOKED, DUPLICATE_EMAIL, ID_COLLISION,
        BOOTSTRAP_NOT_EMPTY, STALE_ACCOUNT, NOT_FOUND, INVALID, REUSED, CSRF_REJECTED }
    @Override public String toString() { return "ProductionAuthResult[status=" + status + ", data=REDACTED]"; }
}
