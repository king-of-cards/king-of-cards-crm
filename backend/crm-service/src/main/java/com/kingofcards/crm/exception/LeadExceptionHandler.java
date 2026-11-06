package com.kingofcards.crm.exception;

import com.kingofcards.crm.controller.LeadController;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@RestControllerAdvice(assignableTypes = {LeadController.class,com.kingofcards.crm.directional.DirectionalController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LeadExceptionHandler {
    @ExceptionHandler(LeadApiException.class)
    ResponseEntity<ProblemDetail> lead(LeadApiException exception) {
        var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore()).body(problem);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<ProblemDetail> invalid(Exception exception) {
        return lead(LeadApiException.badRequest("Lead request fields or parameters are invalid."));
    }
}
