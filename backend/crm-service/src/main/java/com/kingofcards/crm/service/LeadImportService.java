package com.kingofcards.crm.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.validation.Validator;
import com.kingofcards.crm.dto.CreateLeadRequest;
import com.kingofcards.crm.exception.LeadApiException;
import com.kingofcards.crm.model.LeadStage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@PreAuthorize("hasRole('CRM_ADMIN')")
public class LeadImportService {
    private final LeadUploadParser parser;private final Validator validator;private final AtomicLeadImportStore store;
    public LeadImportService(LeadUploadParser parser,Validator validator,AtomicLeadImportStore store){this.parser=parser;this.validator=validator;this.store=store;}
    public Preview validate(MultipartFile file) {
        var parsed=parser.parseRows(file);var rows=new ArrayList<Row>();var seen=new HashSet<String>();
        for(int i=0;i<parsed.size();i++){
            var parsedRow=parsed.get(i);var cells=parsedRow.cells();var errors=new ArrayList<>(parsedRow.errors());CreateLeadRequest lead=null;
            if(cells.size()!=6)errors.add("Exactly six columns are required.");
            else {
                LeadStage stage=null;UUID vendor=null;
                try{stage=LeadStage.valueOf(cells.get(3).strip());}catch(IllegalArgumentException exception){errors.add("Unsupported stage.");}
                try{vendor=UUID.fromString(cells.get(5).strip());if(!vendor.toString().equals(cells.get(5).strip()))throw new IllegalArgumentException();}catch(IllegalArgumentException exception){errors.add("A valid assigned vendor identifier is required.");}
                String phone=cells.get(1).replaceAll("[\\s().-]","");
                lead=new CreateLeadRequest(cells.get(0),phone,cells.get(2),stage,cells.get(4),vendor);
                for(var violation:validator.validate(lead))errors.add(violation.getPropertyPath()+": "+violation.getMessage());
                if(!phone.matches("\\+[1-9][0-9]{7,14}"))errors.add("Phone must include +, country code and 8 to 15 digits.");
                String key=canonical(lead);
                if(!seen.add(key))errors.add("Duplicate row in this upload.");
            }
            rows.add(new Row(parsedRow.rowNumber(),lead,List.copyOf(errors)));
        }
        String fingerprint=digest(rows.stream().map(row->row.lead()==null?"INVALID":canonical(row.lead())).toList().toString());
        return new Preview(UUID.randomUUID(),fingerprint,List.copyOf(rows),"FILE_ONLY",false);
    }
    public AtomicLeadImportStore.Result importFile(MultipartFile file,UUID operation,String expectedFingerprint,Authentication authentication) {
        if(!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_CRM_ADMIN")))throw new AccessDeniedException("Verified administrator required.");
        UUID actor;try{actor=UUID.fromString(jwt.getToken().getSubject());}catch(RuntimeException exception){throw new AccessDeniedException("Valid subject required.");}
        var preview=validate(file);
        if(operation==null || !preview.fingerprint().equals(expectedFingerprint) || preview.rows().stream().anyMatch(row->!row.errors().isEmpty()))
            throw LeadApiException.badRequest("Revalidate the unchanged file and resolve every row error before import.");
        var result=store.importBatch(operation,actor,preview.fingerprint(),preview.rows().stream().map(Row::lead).toList());
        if(result==null || result.total()!=preview.rows().size())throw LeadApiException.unavailable();
        return result;
    }
    private static String canonical(CreateLeadRequest lead){return List.of(lead.name(),lead.phone(),lead.service(),String.valueOf(lead.stage()),lead.source(),String.valueOf(lead.vendorSubjectId())).stream().map(value->value.length()+":"+value).collect(java.util.stream.Collectors.joining());}
    private static String digest(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception exception){throw new IllegalStateException("SHA-256 unavailable");}}
    public record Row(int row,CreateLeadRequest lead,List<String> errors){}
    public record Preview(UUID operationId,String fingerprint,List<Row> rows,String duplicateScope,boolean vendorAssignmentsVerified){
        @Override public String toString(){return "LeadPreview[customer data=REDACTED]";}
    }
}
