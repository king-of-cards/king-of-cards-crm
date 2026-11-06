package com.kingofcards.crm.service;
import java.util.*;
import com.kingofcards.crm.dto.CreateLeadRequest;
import com.kingofcards.crm.exception.LeadApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class UnverifiedLeadImportStore implements AtomicLeadImportStore {
    public Result importBatch(UUID operation,UUID actor,String fingerprint,List<CreateLeadRequest> rows) {
        throw new LeadApiException(HttpStatus.SERVICE_UNAVAILABLE,"BULK_IMPORT_UNVERIFIED",
            "Bulk persistence requires verified atomicity, duplicate rules and durable retry reconciliation. No rows imported.");
    }
}
