package com.kingofcards.identity.exception;

import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.kingofcards.identity.controller.VendorAdminController;
import com.kingofcards.identity.controller.VendorProvisioningController;
import com.kingofcards.identity.controller.VendorPasswordSetupController;

@RestControllerAdvice(assignableTypes = {VendorAdminController.class, VendorProvisioningController.class, VendorPasswordSetupController.class})
public class VendorDirectoryExceptionHandler {
    @ExceptionHandler(VendorDirectoryException.class)
    ResponseEntity<ProblemDetail> vendorError(VendorDirectoryException exception) {
        var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setProperty("code", exception.code());
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore()).body(problem);
    }
}
