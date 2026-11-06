package com.kingofcards.crm.directional;
import java.time.Instant;
import java.util.*;
import com.kingofcards.crm.directional.DirectionalModels.*;

public interface DirectionalRepository {
    default String storageMode(){return "BIGQUERY";}
    default boolean writesAvailable(){return false;}
    List<Lead> list(UUID vendor,LeadDirection direction,Search search,int limit,boolean export);
    Optional<Lead> get(UUID vendor,LeadDirection direction,UUID id);
    Map<LeadDirection,StageCounts> report(UUID vendor);
    /** Returns zero-based input indexes that exactly match persisted leads; must not write. */
    default Set<Integer> exactExistingRows(UUID vendor,LeadDirection direction,List<Draft> drafts){return Set.of();}
    Receipt write(UUID vendor,LeadDirection direction,UUID actor,UUID operation,Instant submittedAt,
        UUID editId,long expectedRevision,List<Draft> drafts,boolean importing,String trustedVendorName);
    default Receipt writeScopedImport(UUID vendor,LeadDirection direction,UUID actor,UUID operation,Instant submittedAt,List<Draft> drafts,String trustedVendorName,String requestScope){throw new UnsupportedOperationException("Explicit import selection is not supported by this repository.");}
    Receipt operation(UUID vendor,LeadDirection direction,UUID actor,UUID operation);
}
