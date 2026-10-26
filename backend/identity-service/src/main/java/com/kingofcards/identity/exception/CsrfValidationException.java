package com.kingofcards.identity.exception;

import org.springframework.security.access.AccessDeniedException;

public class CsrfValidationException extends AccessDeniedException {
    public CsrfValidationException() { super("CSRF validation failed."); }
}