package com.kingofcards.crm.exception;

import org.springframework.http.HttpStatus;

public final class LeadApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public LeadApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static LeadApiException unavailable() {
        return new LeadApiException(HttpStatus.SERVICE_UNAVAILABLE, "CRM_DATA_UNAVAILABLE", "CRM data is unavailable.");
    }
    public static LeadApiException badRequest(String message) {
        return new LeadApiException(HttpStatus.BAD_REQUEST, "INVALID_LEAD_REQUEST", message);
    }
}
