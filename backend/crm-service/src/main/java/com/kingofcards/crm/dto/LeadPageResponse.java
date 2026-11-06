package com.kingofcards.crm.dto;

import java.util.List;

public record LeadPageResponse(List<LeadResponse> items, int page, int size, boolean hasNext) {
    public LeadPageResponse { items = List.copyOf(items); }
    @Override public String toString() { return "LeadPageResponse[page=" + page + ", size=" + size + ", data=REDACTED]"; }
}
