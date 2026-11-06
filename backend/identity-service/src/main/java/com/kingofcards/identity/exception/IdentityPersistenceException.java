package com.kingofcards.identity.exception;

/** Intentionally excludes SDK causes/messages that may contain sensitive values. */
public final class IdentityPersistenceException extends RuntimeException {
    private final String code;
    public IdentityPersistenceException(String code) {
        super("Identity persistence operation could not be completed safely.");
        this.code = code;
    }
    public String code() { return code; }
    public static IdentityPersistenceException gated() {
        return new IdentityPersistenceException("IDENTITY_MUTATIONS_UNVERIFIED");
    }
}
