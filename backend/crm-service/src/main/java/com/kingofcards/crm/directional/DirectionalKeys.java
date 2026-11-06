package com.kingofcards.crm.directional;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import com.kingofcards.crm.directional.DirectionalModels.*;

public final class DirectionalKeys {
    private DirectionalKeys(){}
    public static String canonical(Draft draft){
        var value=new StringBuilder();draft.fields().forEach((key,field)->{part(value,key);part(value,field);});
        if(draft.sourceDateTime()!=null&&!draft.sourceDateTime().timeSupplied()){part(value,"source_date");part(value,draft.sourceDateTime().date().toString());}
        part(value,draft.metadata().service());part(value,draft.metadata().stage().name());part(value,draft.metadata().source());return value.toString();
    }
    private static void part(StringBuilder out,String value){out.append(value==null?"-1:":value.length()+":"+value);}
    public static String hash(String value){return hashBytes(value.getBytes(StandardCharsets.UTF_8));}
    public static String hashBytes(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    public static UUID leadId(UUID vendor,LeadDirection direction,Draft draft){
        var bytes=HexFormat.of().parseHex(hash(vendor+":"+direction+":"+canonical(draft)));var b=ByteBuffer.wrap(bytes);
        return new UUID((b.getLong()&0xffffffffffff0fffL)|0x8000L,(b.getLong()&0x3fffffffffffffffL)|0x8000000000000000L);
    }
    public static String fingerprint(List<Draft> drafts){var out=new StringBuilder();for(var draft:drafts){part(out,canonical(draft));if(draft.sourceDateTime()!=null){part(out,draft.sourceDateTime().date().toString());part(out,Boolean.toString(draft.sourceDateTime().timeSupplied()));part(out,draft.sourceDateTime().originalValue());}}return hash(out.toString());}
    public static boolean matches(Lead lead,Search search){
        if(search.q()!=null && lead.fields().values().stream().filter(Objects::nonNull).noneMatch(value->value.toLowerCase(Locale.ROOT).contains(search.q().toLowerCase(Locale.ROOT))))return false;
        String value=search.filterField()==null?null:switch(search.filterField()){case "__source"->lead.metadata().source();case "__service"->lead.metadata().service();case "__stage"->lead.metadata().stage().name();default->lead.fields().get(search.filterField());};
        return search.filterField()==null || Objects.equals(value,search.filterValue());
    }
}
