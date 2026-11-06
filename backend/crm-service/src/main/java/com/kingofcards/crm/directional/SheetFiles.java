package com.kingofcards.crm.directional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import com.kingofcards.crm.directional.DirectionalModels.*;

/** Header-preserving exports. XLSX stores strings as strings (including phone zeroes).
 * CSV safety escapes are explicit/reversible via the import form's KOC-export option.
 */
public final class SheetFiles {
    private SheetFiles(){}
    public static String csvSafe(String value){if(value==null)return "";String trimmed=value.stripLeading();return value.startsWith("'") || (!trimmed.isEmpty() && "=+-@\t\r".indexOf(trimmed.charAt(0))>=0)?"'"+value:value;}
    public static String csvRestore(String value){return value!=null && value.startsWith("'")?value.substring(1):value;}
    public static byte[] export(LeadDirection direction,List<Lead> leads,String format){
        var keys=new ArrayList<>(direction.headers());var labels=new LinkedHashMap<String,String>();
        if(direction==LeadDirection.KOC_TO_VENDOR){
            keys=new ArrayList<>(List.of("Timestamp","Branch","Name","Phone","occasion","occasion_date","Updates","Additional comments (optional)","Quoted amount","Date Quoted","__vendor_name","__lead_id"));
            labels.put("Timestamp","Date & Time");labels.put("occasion","Occasion / Event");labels.put("occasion_date","Event Date");labels.put("Additional comments (optional)","Additional Comments (Optional)");labels.put("Quoted amount","Quoted Amount");labels.put("__vendor_name","Vendor Name");labels.put("__lead_id","Lead ID");
            // Never lose historical optional fields when exporting existing records.
            for(String legacy:List.of("Relation","Interested (Spin the wheel)"))if(leads.stream().anyMatch(lead->lead.fields().get(legacy)!=null&&!lead.fields().get(legacy).isBlank()))keys.add(legacy);
        }
        var rows=new ArrayList<List<String>>();rows.add(keys.stream().map(key->labels.getOrDefault(key,key)).toList());
        for(var lead:leads)rows.add(keys.stream().map(key->exportValue(lead,key)).toList());
        if(format.equals("csv")){var csv=new StringBuilder("\uFEFF");for(int i=0;i<rows.size();i++){var values=new ArrayList<String>();for(int j=0;j<rows.get(i).size();j++){String value=rows.get(i).get(j);String safe=i==0?value:keys.get(j).equals("Phone")&&!value.isEmpty()?"'"+value:csvSafe(value);values.add("\""+safe.replace("\"","\"\"")+"\"");}csv.append(String.join(",",values)).append("\r\n");}return csv.toString().getBytes(StandardCharsets.UTF_8);}
        if(!format.equals("xlsx"))throw com.kingofcards.crm.exception.LeadApiException.badRequest("Export format must be csv or xlsx.");
        try{var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
            put(zip,"[Content_Types].xml","<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/><Override PartName='/xl/styles.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml'/><Override PartName='/xl/workbook.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml'/><Override PartName='/xl/worksheets/sheet1.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml'/></Types>");
            put(zip,"_rels/.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='xl/workbook.xml'/></Relationships>");
            put(zip,"xl/workbook.xml","<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main' xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'><sheets><sheet name='Leads' sheetId='1' r:id='rId1'/></sheets></workbook>");
            put(zip,"xl/_rels/workbook.xml.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId2' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles' Target='styles.xml'/><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet' Target='worksheets/sheet1.xml'/></Relationships>");
            // Explicit text formatting protects future phone entry in downloaded templates too.
            put(zip,"xl/styles.xml","<styleSheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><fonts count='1'><font><sz val='11'/><name val='Calibri'/></font></fonts><fills count='2'><fill><patternFill patternType='none'/></fill><fill><patternFill patternType='gray125'/></fill></fills><borders count='1'><border/></borders><cellStyleXfs count='1'><xf/></cellStyleXfs><cellXfs count='2'><xf xfId='0'/><xf numFmtId='49' applyNumberFormat='1' xfId='0'/></cellXfs></styleSheet>");
            var sheet=new StringBuilder("<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><cols><col min='1' max='").append(keys.size()).append("' width='22' customWidth='1' style='1'/></cols><sheetData>");
            for(int i=0;i<rows.size();i++){sheet.append("<row r='").append(i+1).append("'>");for(int j=0;j<rows.get(i).size();j++)sheet.append("<c r='").append(column(j)).append(i+1).append("' t='inlineStr'><is><t xml:space='preserve'>").append(xml(rows.get(i).get(j))).append("</t></is></c>");sheet.append("</row>");}
            put(zip,"xl/worksheets/sheet1.xml",sheet.append("</sheetData></worksheet>").toString());
        }return output.toByteArray();}catch(IOException exception){throw new IllegalStateException("Export could not be generated.");}
    }
    private static String exportValue(Lead lead,String key){
        if(key.equals("__vendor_name"))return Objects.toString(lead.vendorName(),"");
        if(key.equals("__lead_id"))return lead.id().toString();
        if(key.equals("Timestamp")){
            if(lead.sourceDateTime()!=null&&!lead.sourceDateTime().timeSupplied())return lead.sourceDateTime().date().toString();
            String value=lead.fields().get(key);return value==null?"":java.time.Instant.parse(value).atZone(SourceDateTime.SOURCE_ZONE).toOffsetDateTime().toString();
        }
        return Objects.toString(lead.fields().get(key),"");
    }
    private static String column(int index){var out=new StringBuilder();for(int n=index+1;n>0;n=(n-1)/26)out.insert(0,(char)('A'+(n-1)%26));return out.toString();}
    private static String xml(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static void put(ZipOutputStream zip,String name,String value)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}
