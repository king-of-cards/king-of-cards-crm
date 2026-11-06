package com.kingofcards.identity.controller;
import java.util.*;
import com.kingofcards.identity.dto.VendorResponse;
import com.kingofcards.identity.repository.IdentityUserRepository;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/vendor-profile")
@PreAuthorize("hasRole('CRM_VENDOR')")
public class VendorSelfProfileController {
    private final IdentityUserRepository users;
    public VendorSelfProfileController(IdentityUserRepository users){this.users=users;}
    @GetMapping public ResponseEntity<VendorResponse> profile(JwtAuthenticationToken token){
        UUID subject;try{subject=UUID.fromString(token.getToken().getSubject());}catch(RuntimeException e){throw new AccessDeniedException("Valid subject required.");}
        var user=users.findById(subject).filter(u->u.isEnabled() && u.getRoles().size()==1 && u.getRoles().stream().allMatch(r->r.getCode().equals("CRM_VENDOR")))
            .orElseThrow(()->new AccessDeniedException("Vendor account is unavailable."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new VendorResponse(subject,user.getDisplayName(),true,List.of("CRM_VENDOR")));
    }
}
