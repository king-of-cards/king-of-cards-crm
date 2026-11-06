package com.kingofcards.crm.service;

import java.security.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** User-confirmed interpretation of one immutable CSV. No filename/content heuristics. */
final class KocConfirmedSource {
    static final String SHA256="3f9558917cca0550678a93deb2eb2dc5306c7bbe6fbafe804c39667972ddf0e3";
    private static final ZoneId ZONE=ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE=formatter("d-M-uuuu");
    private static DateTimeFormatter formatter(String pattern){return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);}
    static boolean matches(byte[] bytes){
        try{return SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static String date(String raw){
        try{return LocalDate.parse(raw,DATE).toString();}
        catch(DateTimeException invalid){throw new IllegalArgumentException("This source requires DD-MM-YYYY dates with a four-digit year; verify the original value.");}
    }
    static com.kingofcards.crm.directional.SourceDateTime sourceDateTime(String raw,int row){
        if(row>=145&&row<=609){
            return new com.kingofcards.crm.directional.SourceDateTime(LocalDate.parse(date(raw)),false,raw);
        }
        String timestamp=timestamp(raw,row);
        return new com.kingofcards.crm.directional.SourceDateTime(Instant.parse(timestamp).atZone(ZONE).toLocalDate(),true,raw);
    }
    static String timestamp(String raw,int row){
        if(row>=145&&row<=609){date(raw);return null;}
        String pattern;
        if(row>=2&&row<=144)pattern="M-d-uuuu";
        else if(row>=610&&row<=616)pattern="d-M-uuuu";
        else throw new IllegalArgumentException("Row is outside the verified source-format boundaries.");
        for(String time:List.of(" H:mm[:ss]"," h:mm[:ss] a")){
            try{return LocalDateTime.parse(raw,formatter(pattern+time)).atZone(ZONE).toInstant().toString();}
            catch(DateTimeException invalid){/* Try the other explicit time representation. */}
        }
        throw new IllegalArgumentException("Timestamp does not follow the confirmed date order and local-time format for this source row. No value was inferred.");
    }
}
