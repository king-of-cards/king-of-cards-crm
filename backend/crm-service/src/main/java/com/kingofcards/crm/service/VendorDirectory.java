package com.kingofcards.crm.service;

import java.util.UUID;

public interface VendorDirectory {
    void requireAssignable(UUID subjectId, String verifiedAdminBearerToken);
    void requireSelfAssignable(UUID verifiedSubject, String verifiedVendorBearerToken);
}
