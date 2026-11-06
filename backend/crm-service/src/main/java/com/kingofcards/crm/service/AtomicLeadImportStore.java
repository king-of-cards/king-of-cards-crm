package com.kingofcards.crm.service;

import java.util.List;
import java.util.UUID;
import com.kingofcards.crm.dto.CreateLeadRequest;

/** Must bind operation+actor+payload to a durable job receipt, validate vendor assignments,
 * reconcile unknown commits and commit all rows atomically. Never call create() in a retry loop.
 */
public interface AtomicLeadImportStore {
    Result importBatch(UUID operation,UUID actor,String fingerprint,List<CreateLeadRequest> rows);
    record Result(int total,int imported,int duplicates) {
        public Result { if(total<1 || imported<0 || duplicates<0 || imported+duplicates!=total)throw new IllegalArgumentException("Invalid confirmed import counts."); }
    }
}
