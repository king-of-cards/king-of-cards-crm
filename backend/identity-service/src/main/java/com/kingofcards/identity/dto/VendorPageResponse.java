package com.kingofcards.identity.dto;

import java.util.List;

public record VendorPageResponse(List<VendorResponse> items, int page, int size,
        long totalElements, int totalPages) {
    public VendorPageResponse { items = List.copyOf(items); }
}
