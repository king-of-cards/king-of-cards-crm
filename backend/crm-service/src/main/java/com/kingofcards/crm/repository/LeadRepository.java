package com.kingofcards.crm.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.kingofcards.crm.model.CrmLeadRecord;
import com.kingofcards.crm.model.LeadSearch;

public interface LeadRepository {
    List<CrmLeadRecord> search(LeadSearch search, UUID ownerSubjectId);
    Optional<CrmLeadRecord> find(UUID id, UUID ownerSubjectId);
    void assertWritesEnabled();
    void insert(CrmLeadRecord lead);
}
