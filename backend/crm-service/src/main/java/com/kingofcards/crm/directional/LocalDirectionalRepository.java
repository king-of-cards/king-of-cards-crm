package com.kingofcards.crm.directional;

import java.time.*;
import java.util.*;
import com.kingofcards.crm.directional.DirectionalModels.*;
import com.kingofcards.crm.exception.LeadApiException;
import org.springframework.http.HttpStatus;

/** Explicit local profile only. Transient single-process state, not a production database
 * or evidence of BigQuery locking. No automatic fallback can select this repository. */
public final class LocalDirectionalRepository implements DirectionalRepository {
    private Map<UUID,Lead> leads=new LinkedHashMap<>();
    private final Map<String,Entry> operations=new HashMap<>();private final Clock clock;
    private record Entry(String fingerprint,String vendorName,Receipt receipt){}
    public LocalDirectionalRepository(Clock clock){this.clock=clock;}
    @Override public String storageMode(){return "LOCAL_EPHEMERAL";}
    @Override public boolean writesAvailable(){return true;}
    public synchronized Map<LeadDirection,StageCounts> report(UUID vendor){
        var result=new EnumMap<LeadDirection,StageCounts>(LeadDirection.class);
        for(var direction:LeadDirection.values()){
            var scoped=leads.values().stream().filter(l->l.vendorSubjectId().equals(vendor)&&l.direction()==direction).toList();
            long fresh=scoped.stream().filter(l->l.metadata().stage()==com.kingofcards.crm.model.LeadStage.NEW).count();
            long contacted=scoped.stream().filter(l->l.metadata().stage()==com.kingofcards.crm.model.LeadStage.CONTACTED).count();
            long interested=scoped.stream().filter(l->l.metadata().stage()==com.kingofcards.crm.model.LeadStage.INTERESTED).count();
            long converted=scoped.stream().filter(l->l.metadata().stage()==com.kingofcards.crm.model.LeadStage.CONVERTED).count();
            result.put(direction,new StageCounts(scoped.size(),fresh,contacted,interested,converted,scoped.size()-fresh-contacted-interested-converted));
        }
        return Map.copyOf(result);
    }
    private String key(UUID vendor,LeadDirection direction,UUID actor,UUID op){return vendor+":"+direction+":"+actor+":"+op;}
    public synchronized List<Lead> list(UUID vendor,LeadDirection direction,Search search,int limit,boolean export){
        return leads.values().stream().filter(l->l.vendorSubjectId().equals(vendor)&&l.direction()==direction&&DirectionalKeys.matches(l,search))
            .sorted(Comparator.comparing(Lead::createdAt).reversed().thenComparing(l->l.id().toString()))
            .skip(export?0:(long)search.page()*search.size()).limit(limit).toList();
    }
    public synchronized Optional<Lead> get(UUID vendor,LeadDirection direction,UUID id){return Optional.ofNullable(leads.get(id)).filter(l->l.vendorSubjectId().equals(vendor)&&l.direction()==direction);}
    public synchronized Set<Integer> exactExistingRows(UUID vendor,LeadDirection direction,List<Draft> drafts){
        var existing=leads.values().stream().filter(l->l.vendorSubjectId().equals(vendor)&&l.direction()==direction)
            .map(l->DirectionalKeys.canonical(new Draft(l.fields(),l.metadata(),l.sourceDateTime()))).collect(java.util.stream.Collectors.toSet());
        var matches=new LinkedHashSet<Integer>();for(int i=0;i<drafts.size();i++)if(existing.contains(DirectionalKeys.canonical(drafts.get(i))))matches.add(i);return Set.copyOf(matches);
    }
    public synchronized Receipt operation(UUID vendor,LeadDirection direction,UUID actor,UUID op){var entry=operations.get(key(vendor,direction,actor,op));return entry==null?new Receipt(op,"NOT_FOUND",0,0,0,List.of()):entry.receipt();}
    public synchronized Receipt write(UUID vendor,LeadDirection direction,UUID actor,UUID op,Instant submittedAt,UUID editId,long expected,List<Draft> drafts,boolean importing,String trustedVendorName){
        return writeBound(vendor,direction,actor,op,submittedAt,editId,expected,drafts,importing,trustedVendorName,null);
    }
    @Override public synchronized Receipt writeScopedImport(UUID vendor,LeadDirection direction,UUID actor,UUID op,Instant submittedAt,List<Draft> drafts,String trustedVendorName,String requestScope){
        if(requestScope==null||!requestScope.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid import request binding");
        return writeBound(vendor,direction,actor,op,submittedAt,null,0,drafts,true,trustedVendorName,requestScope);
    }
    private Receipt writeBound(UUID vendor,LeadDirection direction,UUID actor,UUID op,Instant submittedAt,UUID editId,long expected,List<Draft> drafts,boolean importing,String trustedVendorName,String requestScope){
        String vendorName=trustedName(trustedVendorName);
        String fingerprint=DirectionalKeys.fingerprint(drafts)+":"+editId+":"+expected+":"+importing+":"+submittedAt+(requestScope==null?"":":"+requestScope);
        String key=key(vendor,direction,actor,op);var previous=operations.get(key);
        if(previous!=null){if(!previous.fingerprint().equals(fingerprint))throw conflict("Operation ID was reused with different content.");return previous.receipt();}
        if(submittedAt.isBefore(clock.instant().minusSeconds(300)))throw conflict("Operation expired; reconcile it before starting another.");
        var updated=new LinkedHashMap<>(leads);var results=new ArrayList<RowResult>();int inserted=0,skipped=0,failed=0;
        for(int i=0;i<drafts.size();i++){
            var draft=drafts.get(i);UUID id=editId==null?DirectionalKeys.leadId(vendor,direction,draft):editId;var old=updated.get(id);
            var identical=updated.values().stream().filter(l->l.vendorSubjectId().equals(vendor)&&l.direction()==direction
                &&DirectionalKeys.canonical(new Draft(l.fields(),l.metadata(),l.sourceDateTime())).equals(DirectionalKeys.canonical(draft))).findFirst();
            if(editId!=null){
                if(old==null || !old.vendorSubjectId().equals(vendor)||old.direction()!=direction)throw new LeadApiException(HttpStatus.NOT_FOUND,"LEAD_NOT_FOUND","Lead was not found.");
                if(old.revision()!=expected)throw conflict("Lead changed. Reload before editing.");
                if(identical.isPresent()&&!identical.get().id().equals(editId))throw conflict("Another identical lead already exists.");
            }else if(identical.isPresent()){
                results.add(new RowResult(i+1,identical.get().id(),"SKIPPED",null));skipped++;continue;
            }else if(old!=null){
                boolean same=old.vendorSubjectId().equals(vendor)&&old.direction()==direction&&DirectionalKeys.canonical(new Draft(old.fields(),old.metadata(),old.sourceDateTime())).equals(DirectionalKeys.canonical(draft));
                results.add(new RowResult(i+1,id,same?"SKIPPED":"FAILED",same?null:"Existing lead changed; it was not overwritten."));
                if(same)skipped++;else failed++;continue;
            }
            Instant now=clock.instant();updated.put(id,new Lead(id,vendor,direction,vendorName,draft.fields(),draft.metadata(),old==null?1:old.revision()+1,
                old==null?actor:old.createdBySubjectId(),actor,old==null?now:old.createdAt(),now,old==null?(importing?op:null):old.importBatchId(),draft.sourceDateTime()));
            inserted++;results.add(new RowResult(i+1,id,editId==null?"IMPORTED":"UPDATED",null));
        }
        var receipt=new Receipt(op,"COMMITTED",inserted,skipped,failed,results);leads=updated;operations.put(key,new Entry(fingerprint,vendorName,receipt));return receipt;
    }
    private static String trustedName(String value){if(value==null||value.isBlank()||value.strip().length()>120)throw new LeadApiException(HttpStatus.FORBIDDEN,"VENDOR_DISPLAY_NAME_REQUIRED","A verified vendor display name is required.");return value.strip();}
    private static LeadApiException conflict(String message){return new LeadApiException(HttpStatus.CONFLICT,"DIRECTIONAL_CONFLICT",message);}
}
