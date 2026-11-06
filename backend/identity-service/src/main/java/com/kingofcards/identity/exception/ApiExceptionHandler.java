package com.kingofcards.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IdentityPersistenceException.class)
    ProblemDetail persistenceUnavailable(IdentityPersistenceException exception) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
            "Identity storage is unavailable or the operation is not yet enabled.");
        detail.setTitle("Service Unavailable");
        detail.setProperty("code", exception.code());
        return detail;
    }
    @ExceptionHandler({BadCredentialsException.class, InvalidRefreshTokenException.class,
                       RefreshTokenReuseException.class})
    ProblemDetail unauthenticated(RuntimeException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication failed.");
        detail.setTitle("Unauthorized");
        return detail;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "The request is not allowed.");
        detail.setTitle("Forbidden");
        return detail;
    }
}
