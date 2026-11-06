package com.kingofcards.crm.directional;

import java.time.Instant;
import java.util.*;
import com.kingofcards.crm.model.LeadStage;
import com.kingofcards.crm.exception.LeadApiException;

public final class DirectionalModels {
    private DirectionalModels(){}
    public record Metadata(String service,LeadStage stage,String source) {
        public Metadata {
            service=service==null?null:service.strip();source=source==null?null:source.strip();
            if(service==null || service.isBlank() || service.length()>80)
                throw LeadApiException.badRequest("Select a service containing 1 to 80 characters.");
        }
        @Override public String toString(){return "LeadMetadata[REDACTED]";}
    }
    public record Draft(Map<String,String> fields,Metadata metadata,SourceDateTime sourceDateTime,Boolean clearSourceDateTime) {
        // Older clients omit this additive property; Jackson must accept null and
        // preserve the previous edit behavior rather than reject their request.
        public Draft { clearSourceDateTime=Boolean.TRUE.equals(clearSourceDateTime); }
        public Draft(Map<String,String> fields,Metadata metadata){this(fields,metadata,null,false);}
        public Draft(Map<String,String> fields,Metadata metadata,SourceDateTime sourceDateTime){this(fields,metadata,sourceDateTime,false);}
        @Override public String toString(){return "DirectionalDraft[REDACTED]";}
    }
    public record Command(UUID operationId,Instant submittedAt,long expectedRevision,Draft draft) {
        @Override public String toString(){return "DirectionalCommand[REDACTED]";}
    }
    public record Lead(UUID id,UUID vendorSubjectId,LeadDirection direction,String vendorName,Map<String,String> fields,Metadata metadata,
            long revision,UUID createdBySubjectId,UUID updatedBySubjectId,Instant createdAt,Instant updatedAt,UUID importBatchId,SourceDateTime sourceDateTime) {
        public Lead(UUID id,UUID vendorSubjectId,LeadDirection direction,String vendorName,Map<String,String> fields,Metadata metadata,
                long revision,UUID createdBySubjectId,UUID updatedBySubjectId,Instant createdAt,Instant updatedAt,UUID importBatchId){
            this(id,vendorSubjectId,direction,vendorName,fields,metadata,revision,createdBySubjectId,updatedBySubjectId,createdAt,updatedAt,importBatchId,null);
        }
        public Lead {fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));}
        public Lead(UUID id,UUID vendorSubjectId,LeadDirection direction,Map<String,String> fields,Metadata metadata,
                long revision,UUID createdBySubjectId,UUID updatedBySubjectId,Instant createdAt,Instant updatedAt,UUID importBatchId) {
            this(id,vendorSubjectId,direction,null,fields,metadata,revision,createdBySubjectId,updatedBySubjectId,createdAt,updatedAt,importBatchId);
        }
        @Override public String toString(){return "DirectionalLead[id="+id+", data=REDACTED]";}
    }
    public record Search(int page,int size,String q,String filterField,String filterValue) {
        public Search {
            if(page<0 || size<1 || size>100 || (long)page*size>100_000)throw LeadApiException.badRequest("Invalid bounded pagination.");
            q=clean(q,100);filterField=clean(filterField,120);filterValue=clean(filterValue,4000);
            if((filterField==null)!=(filterValue==null))throw LeadApiException.badRequest("Filter field and value must be supplied together.");
        }
        private static String clean(String value,int max){if(value==null || value.isBlank())return null;if(value.length()>max)throw LeadApiException.badRequest("Filter is too long.");return value.strip();}
    }
    public record Page(List<Lead> items,int page,int size,boolean hasNext){public Page{items=List.copyOf(items);}}
    public record RowResult(int row,UUID leadId,String status,String error) {}
    public record Receipt(UUID operationId,String state,int imported,int skipped,int failed,List<RowResult> rows) {
        public Receipt{rows=List.copyOf(rows);}
    }
    public record PreviewRow(int row,Map<String,String> fields,List<String> errors,boolean duplicate,String classification,Integer duplicateOfRow,SourceDateTime sourceDateTime) {
        public PreviewRow(int row,Map<String,String> fields,List<String> errors,boolean duplicate,String classification,Integer duplicateOfRow){this(row,fields,errors,duplicate,classification,duplicateOfRow,null);}
        public PreviewRow(int row,Map<String,String> fields,List<String> errors,boolean duplicate) {
            this(row,fields,errors,duplicate,errors!=null&&!errors.isEmpty()?"INVALID":duplicate?"DUPLICATE_IN_FILE":"NEW",null);
        }
        public PreviewRow{fields=Collections.unmodifiableMap(new LinkedHashMap<>(fields));errors=List.copyOf(errors);}
        @Override public String toString(){return "PreviewRow[data=REDACTED]";}
    }
    public record ImportSelection(String policy,List<Integer> excludedRows) {
        public ImportSelection {
            if(policy==null || !Set.of("AUTO","EXPLICIT").contains(policy) || excludedRows==null || excludedRows.size()>100000 || excludedRows.stream().anyMatch(row->row==null||row<2||row>1048576) || excludedRows.stream().distinct().count()!=excludedRows.size())throw LeadApiException.badRequest("Invalid duplicate exclusion selection.");
            excludedRows=excludedRows.stream().sorted().toList();
            if(policy.equals("AUTO")&&!excludedRows.isEmpty())throw LeadApiException.badRequest("Automatic duplicate selection cannot contain explicit row numbers.");
        }
    }
    public record Preview(UUID operationId,Instant submittedAt,String fingerprint,List<PreviewRow> rows,ImportSelection selection) {
        public Preview(UUID operationId,Instant submittedAt,String fingerprint,List<PreviewRow> rows){this(operationId,submittedAt,fingerprint,rows,null);}
        public Preview{rows=List.copyOf(rows);}
        @Override public String toString(){return "DirectionalPreview[data=REDACTED]";}
    }
    public record Vendor(UUID subjectId,String displayName,boolean enabled,List<String> roles) {}
    public record StageCounts(long total,long newLeads,long contacted,long interested,long converted,long unclassified) {
        public StageCounts {
            if(total<0||newLeads<0||contacted<0||interested<0||converted<0||unclassified<0
                || java.math.BigInteger.valueOf(newLeads).add(java.math.BigInteger.valueOf(contacted)).add(java.math.BigInteger.valueOf(interested)).add(java.math.BigInteger.valueOf(converted)).add(java.math.BigInteger.valueOf(unclassified)).compareTo(java.math.BigInteger.valueOf(total))!=0)
                throw new IllegalArgumentException("Invalid report counts");
        }
        public static StageCounts empty(){return new StageCounts(0,0,0,0,0,0);}
        public StageCounts plus(StageCounts other){return new StageCounts(Math.addExact(total,other.total),Math.addExact(newLeads,other.newLeads),Math.addExact(contacted,other.contacted),Math.addExact(interested,other.interested),Math.addExact(converted,other.converted),Math.addExact(unclassified,other.unclassified));}
        public java.math.BigDecimal conversionRatePercent(){return total==0?java.math.BigDecimal.ZERO.setScale(2):java.math.BigDecimal.valueOf(converted).multiply(java.math.BigDecimal.valueOf(100)).divide(java.math.BigDecimal.valueOf(total),2,java.math.RoundingMode.HALF_UP);}
    }
    public record DirectionReport(LeadDirection direction,StageCounts counts,java.math.BigDecimal conversionRatePercent) {}
    public record DashboardReport(UUID vendorSubjectId,String vendorName,Instant generatedAt,StageCounts totals,java.math.BigDecimal conversionRatePercent,List<DirectionReport> directions) {
        public DashboardReport { directions=List.copyOf(directions); }
    }
}
