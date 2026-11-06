package com.kingofcards.crm.directional;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.service.LeadUploadParser;

@Service
public class DirectionalService {
    private final DirectionalRepository repository;private final DashboardIdentity identity;private final DirectionalProperties properties;private final Clock clock;
    public DirectionalService(DirectionalRepository repository,DashboardIdentity identity,DirectionalProperties properties,Clock clock){this.repository=repository;this.identity=identity;this.properties=properties;this.clock=clock;}
    private record Actor(UUID id,boolean admin,String token){}
    private Actor actor(Authentication authentication,UUID vendor,LeadDirection direction,boolean write){
        if(!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated())throw new AccessDeniedException("Verified JWT required.");
        var roles=authentication.getAuthorities().stream().map(a->a.getAuthority()).filter(a->a.startsWith("ROLE_")).toList();
        boolean admin=roles.equals(List.of("ROLE_CRM_ADMIN")),supplier=roles.equals(List.of("ROLE_CRM_VENDOR"));
        if(!admin && !supplier)throw new AccessDeniedException("Approved CRM role required.");
        UUID subject;try{subject=UUID.fromString(jwt.getToken().getSubject());if(!subject.toString().equals(jwt.getToken().getSubject()))throw new IllegalArgumentException();}catch(RuntimeException e){throw new AccessDeniedException("Valid subject required.");}
        if(!admin && vendor!=null && !subject.equals(vendor))throw new AccessDeniedException("Another vendor's records cannot be accessed.");
        if(write && !direction.writableBy(admin))throw new AccessDeniedException("This direction is read-only for your role.");
        return new Actor(subject,admin,jwt.getToken().getTokenValue());
    }
    private Vendor scope(UUID vendor,LeadDirection direction,Authentication authentication,boolean write){var actor=actor(authentication,vendor,direction,write);var account=identity.vendor(vendor,actor.admin(),actor.token());
        if((write || !actor.admin()) && (!account.enabled() || account.displayName()==null || account.displayName().isBlank() || account.displayName().strip().length()>120))throw new AccessDeniedException("Vendor account is not active/eligible.");return account;}
    public Vendor vendor(UUID vendor,Authentication authentication){return scope(vendor,LeadDirection.KOC_TO_VENDOR,authentication,false);}
    public DashboardReport report(UUID vendor,Authentication authentication){
        var account=scope(vendor,LeadDirection.KOC_TO_VENDOR,authentication,false);
        var counts=repository.report(vendor);var total=StageCounts.empty();var directions=new ArrayList<DirectionReport>();
        for(var direction:LeadDirection.values()){
            var value=Objects.requireNonNull(counts.get(direction),"Missing report direction");total=total.plus(value);directions.add(new DirectionReport(direction,value,value.conversionRatePercent()));
        }
        return new DashboardReport(vendor,account.displayName(),clock.instant(),total,total.conversionRatePercent(),directions);
    }
    public Map<String,Object> schema(Authentication authentication){
        actor(authentication,null,LeadDirection.KOC_TO_VENDOR,false);
        return Map.of("version",1,"importSelectionVersion",1,"directions",Arrays.stream(LeadDirection.values()).map(d->Map.<String,Object>of("direction",d.name(),"fields",d.columns())).toList(),
            "storageMode",repository.storageMode(),"writesEnabled",repository.writesAvailable(),"exportLimit",properties.exportLimit(),"importLimit",properties.importLimit(),"xlsxTimeZone",properties.xlsxTimeZone(),"serviceOptions",properties.serviceOptions());
    }
    public Page list(UUID vendor,LeadDirection direction,Search search,Authentication auth){scope(vendor,direction,auth,false);validateFilter(direction,search);var result=repository.list(vendor,direction,search,search.size()+1,false);return new Page(result.stream().limit(search.size()).toList(),search.page(),search.size(),result.size()>search.size());}
    public Lead get(UUID vendor,LeadDirection direction,UUID id,Authentication auth){scope(vendor,direction,auth,false);return repository.get(vendor,direction,id).orElseThrow(()->new LeadApiException(HttpStatus.NOT_FOUND,"LEAD_NOT_FOUND","Lead was not found."));}
    public Receipt write(UUID vendor,LeadDirection direction,UUID editId,Command command,Authentication auth){
        Vendor verifiedVendor=scope(vendor,direction,auth,true);if(command==null)throw LeadApiException.badRequest("Mutation command required.");validateOperation(command.operationId(),command.submittedAt());
        if(editId!=null && command.expectedRevision()<1)throw LeadApiException.badRequest("Expected revision is required for editing.");
        if(editId!=null && repository.get(vendor,direction,editId).isEmpty())throw new LeadApiException(HttpStatus.NOT_FOUND,"LEAD_NOT_FOUND","Lead was not found.");
        var request=command.draft();
        if(editId!=null && request!=null && request.fields()!=null){var old=repository.get(vendor,direction,editId).orElseThrow();
            if(old.sourceDateTime()!=null && request.sourceDateTime()==null && !request.clearSourceDateTime() && Objects.equals(old.fields().get("Timestamp"),request.fields().get("Timestamp")))
                request=new Draft(request.fields(),request.metadata(),old.sourceDateTime());
        }
        var draft=normalize(direction,request);var result=repository.write(vendor,direction,actor(auth,vendor,direction,true).id(),command.operationId(),command.submittedAt(),editId,command.expectedRevision(),List.of(draft),false,verifiedVendor.displayName());
        if(result.failed()>0)throw new LeadApiException(HttpStatus.CONFLICT,"DIRECTIONAL_CONFLICT","Lead changed or is incomplete. Reload before another edit.");return result;
    }
    private Draft normalize(LeadDirection direction,Draft draft){
        if(draft==null || draft.metadata()==null)throw LeadApiException.badRequest("Select a service before saving or importing.");
        if(draft.clearSourceDateTime() && (direction!=LeadDirection.KOC_TO_VENDOR || draft.sourceDateTime()!=null || draft.fields()==null || (draft.fields().get("Timestamp")!=null&&!draft.fields().get("Timestamp").isBlank())))throw LeadApiException.badRequest("Clearing source precision requires an empty Date & Time value.");
        var fields=direction.normalize(draft.fields());var stage=com.kingofcards.crm.model.LeadStage.NEW;
        if(direction==LeadDirection.VENDOR_TO_KOC && fields.get("Status")!=null){
            String status=fields.get("Status").strip().toUpperCase(Locale.ROOT);
            try{stage=com.kingofcards.crm.model.LeadStage.valueOf(status);}catch(IllegalArgumentException unrecognized){/* Preserve original Status, default only master stage to NEW. */}
        }
        return new Draft(fields,new Metadata(draft.metadata().service(),stage,direction.name()),draft.sourceDateTime()==null?null:draft.sourceDateTime().validate(direction,fields.get("Timestamp")));
    }
    public Preview preview(UUID vendor,LeadDirection direction,MultipartFile file,Metadata metadata,boolean restoreCsv,Authentication auth){
        scope(vendor,direction,auth,true);return parse(vendor,direction,file,metadata,restoreCsv,true);
    }
    public Preview preview(UUID vendor,LeadDirection direction,MultipartFile file,Metadata metadata,boolean restoreCsv,ImportSelection selection,Authentication auth){
        scope(vendor,direction,auth,true);return parse(vendor,direction,file,metadata,restoreCsv,true,selection);
    }
    private Preview parse(UUID vendor,LeadDirection direction,MultipartFile file,Metadata metadata,boolean restoreCsv,boolean checkExisting){
        return parse(vendor,direction,file,metadata,restoreCsv,checkExisting,null);
    }
    private Preview parse(UUID vendor,LeadDirection direction,MultipartFile file,Metadata metadata,boolean restoreCsv,boolean checkExisting,ImportSelection selection){
        if(metadata==null)throw LeadApiException.badRequest("Select a service before validating the file.");
        var data=LeadUploadParser.forTypedHeaders(direction.headers(),direction.columns().stream().map(LeadDirection.Column::type).toList(),properties.importLimit(),2_097_152,ZoneId.of(properties.xlsxTimeZone())).parseRows(file);var result=new ArrayList<PreviewRow>();var drafts=new ArrayList<Draft>();var firstRowByKey=new HashMap<String,Integer>();var validResultIndexes=new ArrayList<Integer>();
        boolean decode=restoreCsv && Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT).endsWith(".csv");
        for(int row=0;row<data.size();row++){
            var input=new LinkedHashMap<String,String>();var parsedRow=data.get(row);var cells=parsedRow.cells();var errors=new ArrayList<>(parsedRow.errors());boolean duplicate=false;String classification="INVALID";Integer duplicateOfRow=null;
            String rowLabel=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT).endsWith(".xlsx")?"Excel row "+parsedRow.rowNumber()+": ":"Row "+parsedRow.rowNumber()+": ";
            if(cells.size()!=direction.columns().size())errors.add(rowLabel+"column count does not match this direction.");
            for(int c=0;c<direction.headers().size();c++){String value=c<cells.size()?cells.get(c):"";input.put(direction.headers().get(c),decode?SheetFiles.csvRestore(value):value);}
            if(errors.isEmpty())try{var draft=normalize(direction,new Draft(input,metadata,parsedRow.sourceDateTime()));input=new LinkedHashMap<>(draft.fields());String key=DirectionalKeys.canonical(draft);duplicateOfRow=firstRowByKey.putIfAbsent(key,parsedRow.rowNumber());duplicate=duplicateOfRow!=null;classification=duplicate?"DUPLICATE_IN_FILE":"NEW";drafts.add(draft);validResultIndexes.add(result.size());}
            catch(LeadApiException exception){errors.add(rowLabel+exception.getMessage());}
            result.add(new PreviewRow(parsedRow.rowNumber(),input,errors,duplicate,errors.isEmpty()?classification:"INVALID",duplicateOfRow,parsedRow.sourceDateTime()));
        }
        if(checkExisting&&!drafts.isEmpty())for(int matchedIndex:repository.exactExistingRows(vendor,direction,drafts)){
            if(matchedIndex<0||matchedIndex>=validResultIndexes.size())throw new IllegalStateException("Repository returned an invalid preview row index.");
            int resultIndex=validResultIndexes.get(matchedIndex);var row=result.get(resultIndex);
            if("NEW".equals(row.classification()))result.set(resultIndex,new PreviewRow(row.row(),row.fields(),row.errors(),true,"EXISTING_IN_BIGQUERY",null,row.sourceDateTime()));
        }
        String fingerprint=DirectionalKeys.fingerprint(drafts);ImportSelection resolved=null;
        if(selection!=null){
            var duplicates=result.stream().filter(row->row.errors().isEmpty()&&row.duplicate()).map(PreviewRow::row).sorted().toList();
            var excluded=selection.policy().equals("AUTO")?duplicates:selection.excludedRows();
            var validRows=result.stream().filter(row->row.errors().isEmpty()).map(PreviewRow::row).collect(java.util.stream.Collectors.toSet());
            if(!validRows.containsAll(excluded) || (checkExisting&&!duplicates.containsAll(excluded)))throw LeadApiException.badRequest("Only verified duplicate rows can be excluded. The source or database changed; validate the selection again.");
            resolved=new ImportSelection("EXPLICIT",excluded);
            try{fingerprint=DirectionalKeys.hash("selection-v1:"+fingerprint+":"+DirectionalKeys.hashBytes(file.getBytes())+":"+Objects.toString(file.getOriginalFilename(),"")+":"+restoreCsv+":"+excluded);}
            catch(java.io.IOException unavailable){throw LeadApiException.badRequest("The original upload is unavailable. Validate it again before confirmation.");}
        }
        return new Preview(UUID.randomUUID(),clock.instant(),fingerprint,result,resolved);
    }
    public Receipt importFile(UUID vendor,LeadDirection direction,MultipartFile file,Metadata metadata,boolean restoreCsv,UUID op,Instant submittedAt,String fingerprint,Authentication auth){
        return importFile(vendor,direction,file,metadata,restoreCsv,op,submittedAt,fingerprint,null,auth);
    }
    public Receipt importFile(UUID vendor,LeadDirection direction,MultipartFile file,Metadata metadata,boolean restoreCsv,UUID op,Instant submittedAt,String fingerprint,ImportSelection selection,Authentication auth){
        Vendor verifiedVendor=scope(vendor,direction,auth,true);validateOperation(op,submittedAt);
        if(selection!=null&&!selection.policy().equals("EXPLICIT"))throw LeadApiException.badRequest("Confirm the explicit, unchanged preview selection.");
        UUID actor=actor(auth,vendor,direction,true).id();boolean known=false;
        if(selection!=null){var original=repository.operation(vendor,direction,actor,op);known=!"NOT_FOUND".equals(original.state());}
        var preview=parse(vendor,direction,file,metadata,restoreCsv,selection!=null&&!known,selection);
        if(!Objects.equals(fingerprint,preview.fingerprint()) || preview.rows().stream().anyMatch(r->!r.errors().isEmpty()))throw LeadApiException.badRequest("Fix all validation errors and confirm the unchanged preview before import.");
        var excluded=selection==null?Set.<Integer>of():new HashSet<>(selection.excludedRows());
        var drafts=preview.rows().stream().filter(row->!excluded.contains(row.row())).map(row->normalize(direction,new Draft(row.fields(),metadata,row.sourceDateTime()))).toList();
        if(selection==null)return repository.write(vendor,direction,actor,op,submittedAt,null,0,drafts,true,verifiedVendor.displayName());
        if(drafts.isEmpty() || (!known&&preview.rows().stream().noneMatch(row->"NEW".equals(row.classification()))))throw LeadApiException.badRequest("All records already imported or excluded. No import job was submitted.");
        return repository.writeScopedImport(vendor,direction,actor,op,submittedAt,drafts,verifiedVendor.displayName(),DirectionalKeys.hash(vendor+":"+direction+":"+fingerprint));
    }
    public Receipt operation(UUID vendor,LeadDirection direction,UUID op,Authentication auth){scope(vendor,direction,auth,true);return repository.operation(vendor,direction,actor(auth,vendor,direction,true).id(),op);}
    public byte[] export(UUID vendor,LeadDirection direction,Search search,String format,Authentication auth){scope(vendor,direction,auth,false);validateFilter(direction,search);
        var leads=repository.list(vendor,direction,search,properties.exportLimit()+1,true);
        long bytes=leads.stream().flatMap(lead->lead.fields().values().stream()).filter(Objects::nonNull).mapToLong(value->value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        if(leads.size()>properties.exportLimit() || bytes>20_971_520)throw new LeadApiException(HttpStatus.PAYLOAD_TOO_LARGE,"DIRECTIONAL_EXPORT_TOO_LARGE","Export exceeds the safe row/byte limit; no truncated file was returned.");return SheetFiles.export(direction,leads,format);}
    public byte[] template(UUID vendor,LeadDirection direction,String format,Authentication auth){scope(vendor,direction,auth,true);return SheetFiles.export(direction,List.of(),format);}
    private void validateOperation(UUID op,Instant submittedAt){if(op==null || submittedAt==null || submittedAt.isAfter(clock.instant().plusSeconds(30)))throw LeadApiException.badRequest("Valid operation ID and submission time required.");}
    private void validateFilter(LeadDirection direction,Search search){if(search.filterField()!=null && !Set.of("__source","__service","__stage").contains(search.filterField()) && direction.columns().stream().noneMatch(c->c.header().equals(search.filterField()) && c.type().equals("STRING")))throw LeadApiException.badRequest("Unknown text filter for this direction.");}
}
