package com.kingofcards.crm.directional;

import java.time.*;
import com.kingofcards.crm.exception.LeadApiException;

/** Source precision/provenance, independent of the lead's audit creation timestamp. */
public record SourceDateTime(LocalDate date, boolean timeSupplied, String originalValue) {
    public static final ZoneId SOURCE_ZONE=ZoneId.of("Asia/Kolkata");
    public SourceDateTime {
        if(date==null || originalValue==null || originalValue.isBlank() || originalValue.length()>4000)
            throw LeadApiException.badRequest("Invalid source date provenance.");
    }
    public SourceDateTime validate(LeadDirection direction,String timestamp){
        try {
            if(direction!=LeadDirection.KOC_TO_VENDOR || timeSupplied!=(timestamp!=null)
                || (timeSupplied&&!date.equals(Instant.parse(timestamp).atZone(SOURCE_ZONE).toLocalDate())))
                throw new IllegalArgumentException();
            return this;
        }catch(RuntimeException invalid){throw LeadApiException.badRequest("Source date and supplied-time precision do not match Timestamp.");}
    }
    public static SourceDateTime fromTimestamp(String timestamp){
        return timestamp==null?null:new SourceDateTime(Instant.parse(timestamp).atZone(SOURCE_ZONE).toLocalDate(),true,timestamp);
    }
    @Override public String toString(){return "SourceDateTime[REDACTED]";}
}
