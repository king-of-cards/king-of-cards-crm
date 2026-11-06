package com.kingofcards.crm.controller;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.kingofcards.crm.dto.*;
import com.kingofcards.crm.model.*;
import com.kingofcards.crm.service.LeadService;

@RestController
@RequestMapping("/leads")
public class LeadController {
    private final LeadService service;
    public LeadController(LeadService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<LeadPageResponse> list(@RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size, @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "service", required = false) String service,
            @RequestParam(name = "stage", required = false) LeadStage stage,
            @RequestParam(name = "vendorSubjectId", required = false) UUID vendorSubjectId,
            @RequestParam(name = "source", required = false) String source, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            this.service.list(new LeadSearch(page, size, q, service, stage, vendorSubjectId, source), authentication));
    }
    @GetMapping("/{id}")
    public ResponseEntity<LeadResponse> get(@PathVariable("id") UUID id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id, authentication));
    }
    @PostMapping
    public ResponseEntity<LeadResponse> create(@Valid @RequestBody CreateLeadRequest request, Authentication authentication) {
        var response = service.create(request, authentication);
        return ResponseEntity.created(URI.create("leads/" + response.id())).cacheControl(CacheControl.noStore()).body(response);
    }
}
