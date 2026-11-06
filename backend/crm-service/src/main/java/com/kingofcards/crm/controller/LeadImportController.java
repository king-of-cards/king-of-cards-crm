package com.kingofcards.crm.controller;
import java.util.*;
import com.kingofcards.crm.service.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/leads/import")
@PreAuthorize("hasRole('CRM_ADMIN')")
public class LeadImportController {
    private final LeadImportService service;
    public LeadImportController(LeadImportService service){this.service=service;}
    @GetMapping("/template") public ResponseEntity<String> template(){return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=lead-template.csv").contentType(MediaType.parseMediaType("text/csv"))
        .body(String.join(",",LeadUploadParser.HEADERS)+"\r\n");}
    @PostMapping(value="/validate",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LeadImportService.Preview> validate(@RequestParam("file") MultipartFile file){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.validate(file));}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AtomicLeadImportStore.Result> upload(@RequestParam("file") MultipartFile file,@RequestParam("operationId") UUID operation,
            @RequestParam("fingerprint") String fingerprint,Authentication authentication){return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(service.importFile(file,operation,fingerprint,authentication));}
}
