package com.kingofcards.identity.controller;

import com.kingofcards.identity.service.AdminPasswordSetupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/admin-password-setup")
public class AdminPasswordSetupController {
    private final AdminPasswordSetupService service;
    public AdminPasswordSetupController(AdminPasswordSetupService service){this.service=service;}
    @PostMapping public ResponseEntity<Void> setup(@Valid @RequestBody SetupRequest request) {
        service.complete(request.token(),request.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    public record SetupRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(min=12,max=72) String password) {
        @Override public String toString(){return "AdminSetupRequest[credentials=REDACTED]";}
    }
}
