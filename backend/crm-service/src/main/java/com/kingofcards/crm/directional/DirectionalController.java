package com.kingofcards.crm.directional;
import java.time.Instant;
import java.util.*;
import com.kingofcards.crm.directional.DirectionalModels.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/lead-dashboard")
@PreAuthorize("hasAnyRole('CRM_ADMIN','CRM_VENDOR')")
public class DirectionalController {
    private final DirectionalService service;
    public DirectionalController(DirectionalService service){this.service=service;}
    @GetMapping("/schema") public ResponseEntity<?> schema(Authentication auth){return ok(service.schema(auth));}
    @GetMapping("/vendors/{vendor}/summary") public ResponseEntity<?> vendor(@PathVariable("vendor") UUID vendor,Authentication auth){return ok(service.vendor(vendor,auth));}
    @GetMapping("/vendors/{vendor}/report") public ResponseEntity<DashboardReport> report(@PathVariable("vendor") UUID vendor,Authentication auth){return ok(service.report(vendor,auth));}
    @GetMapping("/vendors/{vendor}/{direction}/leads") public ResponseEntity<Page> list(@PathVariable("vendor") UUID vendor,@PathVariable("direction") LeadDirection direction,
            @RequestParam(name="page",defaultValue="0")int page,@RequestParam(name="size",defaultValue="20")int size,
            @RequestParam(name="q",required=false)String q,@RequestParam(name="filterField",required=false)String field,@RequestParam(name="filterValue",required=false)String value,Authentication auth){return ok(service.list(vendor,direction,new Search(page,size,q,field,value),auth));}
    @GetMapping("/vendors/{vendor}/{direction}/leads/{id}") public ResponseEntity<Lead> get(@PathVariable("vendor")UUID vendor,@PathVariable("direction")LeadDirection direction,@PathVariable("id")UUID id,Authentication auth){return ok(service.get(vendor,direction,id,auth));}
    @PostMapping("/vendors/{vendor}/{direction}/leads") public ResponseEntity<Receipt> create(@PathVariable("vendor")UUID vendor,@PathVariable("direction")LeadDirection direction,@RequestBody Command command,Authentication auth){return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.write(vendor,direction,null,command,auth));}
    @PatchMapping("/vendors/{vendor}/{direction}/leads/{id}") public ResponseEntity<Receipt> edit(@PathVariable("vendor")UUID vendor,@PathVariable("direction")LeadDirection direction,@PathVariable("id")UUID id,@RequestBody Command command,Authentication auth){return ok(service.write(vendor,direction,id,command,auth));}
    @PostMapping(value="/vendors/{vendor}/{direction}/imports/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Preview> preview(@PathVariable("vendor")UUID vendor,@PathVariable("direction")LeadDirection direction,@RequestPart("file")MultipartFile file,@RequestPart("metadata")Metadata metadata,
            @RequestParam(name="restoreCsv",defaultValue="false")boolean restore,@RequestPart(name="selection",required=false)ImportSelection selection,Authentication auth){return ok(service.preview(vendor,direction,file,metadata,restore,selection,auth));}
    @PostMapping(value="/vendors/{vendor}/{direction}/imports",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Receipt> upload(@PathVariable("vendor")UUID vendor,@PathVariable("direction")LeadDirection direction,@RequestPart("file")MultipartFile file,@RequestPart("metadata")Metadata metadata,
            @RequestParam(name="restoreCsv",defaultValue="false")boolean restore,@RequestParam("operationId")UUID operation,@RequestParam("submittedAt")Instant time,@RequestParam("fingerprint")String fingerprint,@RequestPart(name="selection",required=false)ImportSelection selection,Authentication auth){return ok(service.importFile(vendor,direction,file,metadata,restore,operation,time,fingerprint,selection,auth));}
    @GetMapping("/vendors/{vendor}/{direction}/operations/{operation}") public ResponseEntity<Receipt> operation(@PathVariable("vendor")UUID vendor,@PathVariable("direction")LeadDirection direction,@PathVariable("operation")UUID operation,Authentication auth){return ok(service.operation(vendor,direction,operation,auth));}
    @GetMapping("/vendors/{vendor}/{direction}/export") public ResponseEntity<byte[]> export(@PathVariable("vendor")UUID vendor,@PathVariable("direction")LeadDirection direction,@RequestParam(name="format",defaultValue="csv")String format,
            @RequestParam(name="q",required=false)String q,@RequestParam(name="filterField",required=false)String field,@RequestParam(name="filterValue",required=false)String value,Authentication auth){return file(direction,format,service.export(vendor,direction,new Search(0,100,q,field,value),format,auth),false);}
    @GetMapping("/vendors/{vendor}/{direction}/template") public ResponseEntity<byte[]> template(@PathVariable("vendor")UUID vendor,@PathVariable("direction")LeadDirection direction,@RequestParam(name="format",defaultValue="csv")String format,Authentication auth){return file(direction,format,service.template(vendor,direction,format,auth),true);}
    private static ResponseEntity<byte[]> file(LeadDirection direction,String format,byte[] content,boolean template){return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename="+direction.name().toLowerCase(Locale.ROOT)+(template?"-template":"")+"."+format)
        .contentType(MediaType.parseMediaType(format.equals("csv")?"text/csv;charset=UTF-8":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(content);}
    private static <T>ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
