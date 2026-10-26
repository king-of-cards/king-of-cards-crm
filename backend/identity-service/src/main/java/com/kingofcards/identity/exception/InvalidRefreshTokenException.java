package com.kingofcards.identity.exception;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() { super("Invalid refresh session"); }
}