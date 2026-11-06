package com.kingofcards.crm.directional;

import java.util.*;
import java.math.BigDecimal;
import java.time.*;
import com.kingofcards.crm.exception.LeadApiException;

/** Exact sheet headers and order; name/phone remain in the existing master, not duplicated. */
public enum LeadDirection {
    KOC_TO_VENDOR("crm_koc_to_vendor_details",List.of(
        f("Relation","relation","STRING",false),f("Timestamp","sheet_timestamp","TIMESTAMP",false),
        f("Name","customer_name","STRING",true),f("Phone","phone","STRING",true),f("Branch","branch","STRING",false),
        f("Interested (Spin the wheel)","interested","STRING",false),f("occasion_date","occasion_date","DATE",false),
        f("occasion","occasion","STRING",false),f("Updates","updates","STRING",false),
        f("Additional comments (optional)","additional_comments","STRING",false),
        f("Quoted amount","quoted_amount","NUMERIC",false),f("Date Quoted","date_quoted","DATE",false))),
    VENDOR_TO_KOC("crm_vendor_to_koc_details",List.of(
        f("Sl. No","sl_no","INT64",false),f("Date","business_date","DATE",false),f("Name","customer_name","STRING",true),
        f("Email","email","STRING",false),f("Contact","contact","STRING",false),f("Phone","phone","STRING",true),
        f("City","city","STRING",false),f("Status","status","STRING",false),f("Comment","comment","STRING",false),
        f("campaign sent","campaign_sent","STRING",false),f("2nd campaign sent","second_campaign_sent","STRING",false),
        f("3rd campaign sent","third_campaign_sent","STRING",false)));
    private final String table;private final List<Column> columns;
    LeadDirection(String table,List<Column> columns){this.table=table;this.columns=List.copyOf(columns);}
    public String table(){return table;}public List<Column> columns(){return columns;}
    public List<String> headers(){return columns.stream().map(Column::header).toList();}
    public List<Column> detailColumns(){return columns.stream().filter(c->!c.master()).toList();}
    public boolean writableBy(boolean admin){return admin?this==KOC_TO_VENDOR:this==VENDOR_TO_KOC;}
    private static Column f(String h,String c,String t,boolean required){return new Column(h,c,t,required,c.equals("customer_name")||c.equals("phone"));}
    public record Column(String header,String column,String type,boolean required,boolean master) {}
    public Map<String,String> normalize(Map<String,String> input) {
        if(input==null || input.keySet().stream().anyMatch(key->!headers().contains(key)))throw invalid("Unknown or missing sheet fields.");
        var result=new LinkedHashMap<String,String>();
        for(var column:columns){
            String value=input.get(column.header());value=value==null || value.isBlank()?null:value.strip();
            if(value==null){if(column.required())throw invalid(column.header()+" is required.");result.put(column.header(),null);continue;}
            if(value.length()>(column.column().equals("customer_name")?120:column.column().equals("phone")?40:4000)
                    || value.codePoints().anyMatch(cp->(cp<32 && cp!=9 && cp!=10 && cp!=13)||(cp>=0xD800&&cp<=0xDFFF)||cp==0xFFFE||cp==0xFFFF))throw invalid(column.header()+" has invalid length or control characters.");
            try {
                value=switch(column.type()) {
                    case "DATE" -> LocalDate.parse(value).toString();
                    case "TIMESTAMP" -> {var instant=OffsetDateTime.parse(value).toInstant();if(instant.getNano()%1000!=0)throw new IllegalArgumentException();yield instant.toString();}
                    case "INT64" -> {long number=Long.parseLong(value);if(number<0)throw new IllegalArgumentException();yield Long.toString(number);}
                    case "NUMERIC" -> {var amount=new BigDecimal(value).stripTrailingZeros();if(amount.signum()<0 || Math.max(amount.scale(),0)>9 || (long)amount.precision()-amount.scale()>29)throw new IllegalArgumentException();yield amount.toPlainString();}
                    default -> value;
                };
                if(column.column().equals("phone") && (!value.matches("[+0-9() .-]+") || value.replaceAll("[^0-9]","").length()<7 || value.replaceAll("[^0-9]","").length()>20))throw new IllegalArgumentException();
                if(column.column().equals("email") && (value.length()>254 || !value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))throw new IllegalArgumentException();
            }catch(RuntimeException exception){throw invalid("Invalid "+column.header()+". Dates use YYYY-MM-DD; timestamps require an ISO offset; amounts must be nonnegative NUMERIC values.");}
            result.put(column.header(),value);
        }
        return Collections.unmodifiableMap(result);
    }
    private static LeadApiException invalid(String message){return LeadApiException.badRequest(message);}
}
