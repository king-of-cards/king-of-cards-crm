package com.kingofcards.crm.model;

import java.util.UUID;
import com.kingofcards.crm.exception.LeadApiException;

public record LeadSearch(int page, int size, String q, String service, LeadStage stage, UUID vendorSubjectId, String source) {
    public LeadSearch(int page, int size, String q, String service, LeadStage stage, UUID vendorSubjectId) {
        this(page, size, q, service, stage, vendorSubjectId, null);
    }
    public LeadSearch {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > 100_000) {
            throw LeadApiException.badRequest("Page must be nonnegative, size 1–100, and offset at most 100000.");
        }
        q = clean(q, 100); service = clean(service, 80); source = clean(source, 120);
    }
    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        value = value.strip();
        if (value.length() > max) throw LeadApiException.badRequest("Search or filter exceeds its length limit.");
        return value;
    }
    @Override public String toString() { return "LeadSearch[page=" + page + ", size=" + size + ", filters=REDACTED]"; }
}
