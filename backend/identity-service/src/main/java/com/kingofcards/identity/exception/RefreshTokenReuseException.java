package com.kingofcards.identity.exception;

public class RefreshTokenReuseException extends RuntimeException {
    public RefreshTokenReuseException() { super("Refresh session is no longer valid"); }
}