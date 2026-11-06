package com.kingofcards.crm.service;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import com.kingofcards.crm.exception.LeadApiException;

/** Value-only CSV/XLSX parsing. No formula evaluation, external XML, disk extraction or cloud access. */
@Component
public class LeadUploadParser {
    public static final List<String> HEADERS=List.of("name","phone","service","stage","source","vendorSubjectId");
    private static final int MAX_EXCEL_ROW_NUMBER=1_048_576;
    private static final int MAX_CONFIGURABLE_DATA_ROWS=100_000;
    private final int maxRows,maxBytes;private final List<String> headers;private final Map<Integer,String> dateTypes;private final Set<Integer> phoneColumns;private final java.time.ZoneId timeZone;
    private boolean flexibleHeaders;
    private final boolean kocSheet;
    public record ParsedRow(int rowNumber,List<String> cells,List<String> errors,com.kingofcards.crm.directional.SourceDateTime sourceDateTime){
        public ParsedRow(int rowNumber,List<String> cells,List<String> errors){this(rowNumber,cells,errors,null);}
        public ParsedRow(int rowNumber,List<String> cells){this(rowNumber,cells,List.of());}
        public ParsedRow{cells=List.copyOf(cells);errors=List.copyOf(errors);}
    }
    @org.springframework.beans.factory.annotation.Autowired
    public LeadUploadParser(@Value("${crm.lead-import.max-rows:2000}") int maxRows,
            @Value("${crm.lead-import.max-bytes:2097152}") int maxBytes) {
        this(maxRows,maxBytes,HEADERS,Map.of(),java.time.ZoneOffset.UTC);
    }
    private LeadUploadParser(int maxRows,int maxBytes,List<String> headers,Map<Integer,String> dateTypes,java.time.ZoneId timeZone) {
        if(maxRows<1 || maxRows>MAX_CONFIGURABLE_DATA_ROWS || maxBytes<1 || maxBytes>10_485_760)throw new IllegalArgumentException("Invalid bounded upload limits.");
        if(headers==null || headers.isEmpty() || headers.size()>32 || new HashSet<>(headers).size()!=headers.size())throw new IllegalArgumentException("Invalid sheet headers.");
        this.maxRows=maxRows;this.maxBytes=maxBytes;this.headers=List.copyOf(headers);this.dateTypes=Map.copyOf(dateTypes);this.timeZone=timeZone;
        this.kocSheet=headers.contains("Timestamp")&&headers.contains("occasion")&&headers.contains("occasion_date");
        var phoneIndexes=new HashSet<Integer>();for(int i=0;i<headers.size();i++)if(headerKey(headers.get(i)).equals("phone"))phoneIndexes.add(i);this.phoneColumns=Set.copyOf(phoneIndexes);
    }
    public static LeadUploadParser forHeaders(List<String> headers,int maxRows,int maxBytes){return new LeadUploadParser(maxRows,maxBytes,headers,Map.of(),java.time.ZoneOffset.UTC);}
    public static LeadUploadParser forTypedHeaders(List<String> headers,List<String> types,int maxRows,int maxBytes,java.time.ZoneId zone){
        if(headers.size()!=types.size())throw new IllegalArgumentException("Header/type mismatch.");var dates=new HashMap<Integer,String>();
        for(int i=0;i<types.size();i++)if(types.get(i).equals("DATE")||types.get(i).equals("TIMESTAMP"))dates.put(i,types.get(i));
        var parser=new LeadUploadParser(maxRows,maxBytes,headers,dates,zone);parser.flexibleHeaders=true;return parser;
    }
    public List<List<String>> parse(MultipartFile file) {
        return parseRows(file).stream().map(ParsedRow::cells).toList();
    }
    public List<ParsedRow> parseRows(MultipartFile file) {
        if(file==null || file.isEmpty() || file.getSize()>maxBytes)throw invalid("Select a nonempty file within the configured upload limit.");
        try(var input=file.getInputStream()) {
            byte[] bytes=input.readNBytes(maxBytes+1);
            if(bytes.length>maxBytes)throw invalid("Upload exceeds the configured byte limit.");
            String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
            boolean confirmedSource=kocSheet&&name.endsWith(".csv")&&KocConfirmedSource.matches(bytes);
            List<ParsedRow> rows;
            if(name.endsWith(".csv")) rows=csv(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString());
            else if(name.endsWith(".xlsx")) rows=xlsx(bytes);
            else throw invalid("Only UTF-8 CSV and .xlsx files are supported.");
            if(rows.isEmpty())throw invalid("The file has no header row.");
            if(flexibleHeaders){
                var mapping=mapHeaders(rows.getFirst().cells());var normalized=new ArrayList<ParsedRow>();
                for(int row=1;row<rows.size();row++){
                    var source=rows.get(row);var values=new ArrayList<>(Collections.nCopies(headers.size(),""));var errors=new ArrayList<>(source.errors());
                    for(int col=0;col<source.cells().size();col++){
                        if(col<mapping.length && mapping[col]<=-2){
                            if(mapping[col]==-2&&!source.cells().get(col).isBlank())try{String value=source.cells().get(col).strip();if(!UUID.fromString(value).toString().equalsIgnoreCase(value))throw new IllegalArgumentException();}
                            catch(IllegalArgumentException invalidId){errors.add("Row "+source.rowNumber()+": Lead ID must be a canonical UUID reference. IDs are system-managed and never used to overwrite a record during import.");}
                            continue; // Known export-only references; trusted identity supplies names/IDs.
                        }
                        if(col>=mapping.length || mapping[col]<0){if(!source.cells().get(col).isBlank())throw invalid("Row "+source.rowNumber()+", column "+(col+1)+" contains data without a recognized header; nothing was discarded.");continue;}
                        values.set(mapping[col],source.cells().get(col));
                    }
                    com.kingofcards.crm.directional.SourceDateTime sourceDateTime=source.sourceDateTime();
                    if(kocSheet){
                        if(confirmedSource)try{sourceDateTime=KocConfirmedSource.sourceDateTime(values.get(headers.indexOf("Timestamp")),source.rowNumber());}
                        catch(RuntimeException invalid){errors.add("Row "+source.rowNumber()+": Timestamp: "+safeDateError(invalid));}
                        int timestampIndex=headers.indexOf("Timestamp");String original=values.get(timestampIndex);
                        if(!confirmedSource&&sourceDateTime==null&&original.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))try{
                            sourceDateTime=new com.kingofcards.crm.directional.SourceDateTime(java.time.LocalDate.parse(original),false,original);values.set(timestampIndex,"");
                        }catch(java.time.DateTimeException invalid){errors.add("Row "+source.rowNumber()+": Timestamp: Invalid ISO calendar date.");}
                        normalizeKocValues(values,errors,source.rowNumber(),name.endsWith(".xlsx")?"Excel row ":"Row ",confirmedSource);
                        if(sourceDateTime==null&&!values.get(timestampIndex).isBlank())try{
                            var instant=java.time.Instant.parse(values.get(timestampIndex));sourceDateTime=new com.kingofcards.crm.directional.SourceDateTime(instant.atZone(com.kingofcards.crm.directional.SourceDateTime.SOURCE_ZONE).toLocalDate(),true,original);
                        }catch(java.time.DateTimeException invalid){/* Existing row errors remain authoritative. */}
                    }
                    if(kocSheet)for(int phone:phoneColumns){String raw=values.get(phone);
                        if(raw.matches("(?i)[+-]?[0-9]+(?:\\.[0-9]+)?e[+-]?[0-9]+"))try{values.set(phone,exactNumericPhone(raw));}
                        catch(IllegalArgumentException unsafe){errors.add("Row "+source.rowNumber()+": Phone scientific notation has insufficient verified precision. Correct the phone from its original source and store it as text; no missing digits were inferred.");}
                    }
                    normalized.add(new ParsedRow(source.rowNumber(),values,errors,sourceDateTime));
                }
                rows=normalized;
            }else {
                if(!rows.getFirst().cells().equals(headers))throw invalid("Use the exact ordered template header ("+headers.size()+" columns).");
                rows=List.copyOf(rows.subList(1,rows.size()));
            }
            if(rows.isEmpty())throw invalid("Upload must contain at least one populated data row.");
            return List.copyOf(rows);
        } catch(LeadApiException exception){throw exception;}
        catch(Exception exception){throw invalid("File could not be read as a supported, bounded upload.");}
    }
    private static String headerKey(String value){return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private int[] mapHeaders(List<String> source){
        var lookup=new HashMap<String,Integer>();for(int i=0;i<headers.size();i++)lookup.put(headerKey(headers.get(i)),i);
        int comments=headers.indexOf("Additional comments (optional)");if(comments>=0)lookup.put("additionalcomments",comments);
        if(kocSheet){
            lookup.put("leadid",-2);lookup.put("vendorname",-3);
            for(String alias:List.of("datetime","dateandtime"))lookup.put(alias,headers.indexOf("Timestamp"));
            for(String alias:List.of("occasionevent","occasionorevent","event"))lookup.put(alias,headers.indexOf("occasion"));
            for(String alias:List.of("eventdate","occasioneventdate"))lookup.put(alias,headers.indexOf("occasion_date"));
        }
        int lastHeader=source.size()-1;while(lastHeader>=0&&source.get(lastHeader).isBlank())lastHeader--;
        var used=new HashSet<Integer>();var result=new int[source.size()];Arrays.fill(result,-1);
        for(int col=0;col<source.size();col++){
            if(source.get(col).isBlank()){
                if(kocSheet&&col<lastHeader)throw invalid("Unrecognized header in column "+(col+1)+". Blank columns are supported only at the end of the sheet; no data was discarded.");
                continue;
            }
            var index=lookup.get(headerKey(source.get(col)));
            if(index==null)throw invalid("Unrecognized header in column "+(col+1)+". Use a supported sheet field; unknown columns cannot be discarded.");
            if(!used.add(index))throw invalid("Duplicate or ambiguous header for "+(index<0?source.get(col):headers.get(index))+".");result[col]=index;
        }
        for(String required:List.of("Name","Phone")){int index=headers.indexOf(required);if(index>=0&&!used.contains(index))throw invalid("Required column is missing: "+required+".");}
        return result;
    }
    private void normalizeKocValues(List<String> values,List<String> errors,int row,String rowLabel,boolean confirmedSource){
        for(var entry:dateTypes.entrySet()){
            int index=entry.getKey();String raw=values.get(index).strip();if(raw.isEmpty())continue;
            String header=headers.get(index);
            if(errors.stream().anyMatch(message->message.contains(header)))continue;
            try{values.set(index,entry.getValue().equals("TIMESTAMP")
                ?(confirmedSource?Objects.toString(KocConfirmedSource.timestamp(raw,row),""):normalizeKocTimestamp(raw))
                :(confirmedSource?KocConfirmedSource.date(raw):normalizeDateText(raw)));}
            catch(RuntimeException invalidValue){errors.add(rowLabel+row+": "+header+": "+safeDateError(invalidValue));}
        }
        int amount=headers.indexOf("Quoted amount");if(amount>=0){
            String raw=values.get(amount).strip();
            if(raw.contains(",")){
                if(raw.matches("(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,2}(?:,[0-9]{2})*,[0-9]{3})(?:\\.[0-9]+)?"))values.set(amount,raw.replace(",",""));
                else errors.add(rowLabel+row+": Quoted amount has ambiguous comma grouping. Use 4,000 or 4000; decimal fractions use a dot.");
            }
        }
    }
    private static String normalizeKocTimestamp(String raw){
        var match=java.util.regex.Pattern.compile("^(.+?)[T ]([0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?)(Z|[+-][0-9]{2}:[0-9]{2})?$").matcher(raw);
        if(match.matches()){
            String date=normalizeDateText(match.group(1));
            if(match.group(3)==null)throw new IllegalArgumentException("Timestamp requires an explicit timezone offset; date-only and local-time values are not accepted.");
            return java.time.OffsetDateTime.parse(date+"T"+match.group(2)+match.group(3)).toInstant().toString();
        }
        normalizeDateText(raw);
        throw new IllegalArgumentException("Timestamp is date-only; date-only and local-time values are not accepted. Provide an actual time and explicit timezone offset, or leave this optional field blank.");
    }
    private static String safeDateError(RuntimeException error){
        // java.time parse messages include the source cell. Return only known safe guidance.
        if(error instanceof java.time.DateTimeException)return "Invalid calendar date or time; use YYYY-MM-DD, and include a time and explicit offset for Timestamp.";
        return error.getMessage();
    }
    private List<ParsedRow> csv(String text) {
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        var rows=new ArrayList<ParsedRow>();var row=new ArrayList<String>();var cell=new StringBuilder();int recordNumber=1,columnNumber=1;
        boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted){if(c=='"'){if(i+1<text.length() && text.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);continue;}
            if(c=='"'){if(cell.length()!=0 || closed)throw invalid("Malformed CSV quoting.");quoted=true;}
            else if(c==',' || c=='\n' || c=='\r') {
                addCsvCell(row,cell.toString(),recordNumber,columnNumber++);cell.setLength(0);closed=false;
                if(c!=','){addCsvRow(rows,row,recordNumber++);row.clear();columnNumber=1;if(c=='\r' && i+1<text.length() && text.charAt(i+1)=='\n')i++;}
            } else {if(closed)throw invalid("Unexpected text after a quoted CSV cell.");cell.append(c);}
        }
        if(quoted)throw invalid("Unclosed CSV quotation.");
        if(cell.length()>0 || !row.isEmpty() || closed){addCsvCell(row,cell.toString(),recordNumber,columnNumber);addCsvRow(rows,row,recordNumber);}
        return rows;
    }
    private void addCsvCell(List<String> row,String value,int rowNumber,int columnNumber){
        if(kocSheet&&columnNumber>32){
            if(!value.isBlank())throw invalid("Row "+rowNumber+", column "+columnNumber+" contains data outside the supported sheet columns; nothing was discarded.");
            return;
        }
        row.add(value);if(row.size()>32)throw invalid("Too many CSV columns.");
    }
    private void addCsvRow(List<ParsedRow> rows,List<String> values,int rowNumber){
        boolean header=rows.isEmpty();if(header || values.stream().anyMatch(value->!value.isBlank())){
            if(!header && rows.size()>maxRows)throw tooManyRows();rows.add(new ParsedRow(rowNumber,values));
        }
    }
    private List<ParsedRow> xlsx(byte[] bytes) throws Exception {
        var parts=new HashMap<String,byte[]>();int total=0,entries=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(++entries>64 || entry.getName().contains("..") || entry.getName().startsWith("/")
                        || entry.getName().contains("\\") || entry.getName().toLowerCase(Locale.ROOT).contains("vbaproject"))throw invalid("Unsupported workbook package.");
                byte[] part=zip.readNBytes(8_388_609-total);total+=part.length;
                if(total>8_388_608 || parts.putIfAbsent(entry.getName(),part)!=null)throw invalid("Workbook exceeds safe expansion limits or has duplicate parts.");
            }
        }
        var workbook=xml(parts.get("xl/workbook.xml"));var sheets=workbook.getElementsByTagNameNS("*","sheet");
        var bookProperties=workbook.getElementsByTagNameNS("*","workbookPr");
        boolean date1904=bookProperties.getLength()>0 && Set.of("1","true").contains(((Element)bookProperties.item(0)).getAttribute("date1904"));
        if(sheets.getLength()==0)throw invalid("Workbook has no worksheet.");
        String relation=((Element)sheets.item(0)).getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");
        var relationships=xml(parts.get("xl/_rels/workbook.xml.rels")).getElementsByTagNameNS("*","Relationship");
        String sheetPath=null;
        for(int i=0;i<relationships.getLength();i++){var rel=(Element)relationships.item(i);if(rel.getAttribute("Id").equals(relation)){
            String target=rel.getAttribute("Target");
            if("External".equals(rel.getAttribute("TargetMode")) || target.contains("..") || target.contains(":"))throw invalid("External workbook references are unsupported.");
            sheetPath=target.startsWith("/xl/")?target.substring(1):"xl/"+target;
        }}
        if(sheetPath==null || !sheetPath.startsWith("xl/worksheets/"))throw invalid("Unsupported first worksheet reference.");
        var strings=new ArrayList<String>();
        if(parts.containsKey("xl/sharedStrings.xml")){var nodes=xml(parts.get("xl/sharedStrings.xml")).getElementsByTagNameNS("*","si");
            if(nodes.getLength()>10_000)throw invalid("Too many shared strings.");
            for(int i=0;i<nodes.getLength();i++)strings.add(text((Element)nodes.item(i),"t"));}
        var nodes=xml(parts.get(sheetPath)).getElementsByTagNameNS("*","row");
        var rows=new ArrayList<ParsedRow>();int[] mapping=null;int width=flexibleHeaders?32:headers.size();int previousRow=0;
        for(int i=0;i<nodes.getLength();i++){
            var rowElement=(Element)nodes.item(i);int physicalRow=Integer.parseInt(rowElement.getAttribute("r"));
            if(physicalRow<=previousRow || physicalRow>MAX_EXCEL_ROW_NUMBER)throw invalid("Worksheet row coordinates are invalid or out of order.");
            previousRow=physicalRow;
            var values=new ArrayList<>(Collections.nCopies(width,""));var rowErrors=new ArrayList<String>();com.kingofcards.crm.directional.SourceDateTime rowSource=null;var cells=rowElement.getElementsByTagNameNS("*","c");var seen=new HashSet<Integer>();
            for(int j=0;j<cells.getLength();j++){
                var cell=(Element)cells.item(j);String reference=cell.getAttribute("r");
                if(!reference.matches("[A-Z]{1,3}[1-9][0-9]*"))throw invalid("Invalid worksheet cell reference.");
                String letters=reference.replaceAll("[0-9]","");int column=0;for(char letter:letters.toCharArray())column=column*26+(letter-'A'+1);column--;
                if(!seen.add(column) || Integer.parseInt(reference.substring(letters.length()))!=physicalRow)throw invalid("Unexpected column, duplicate cell or mismatched row coordinate.");
                if(cell.getElementsByTagNameNS("*","f").getLength()>0)throw invalid("Formula cells are not accepted (worksheet row "+physicalRow+"). Paste values only.");
                String type=cell.getAttribute("t"),value=text(cell,"v");
                if(type.equals("s"))value=strings.get(Integer.parseInt(value));
                else if(type.equals("inlineStr"))value=text(cell,"t");
                else if(type.equals("e") || type.equals("b"))throw invalid("Unsupported error/boolean cell at worksheet row "+physicalRow+".");
                if(column>=width){if(!value.isBlank())throw invalid("Row "+physicalRow+", column "+(column+1)+" contains data outside the supported sheet columns.");continue;}
                int canonical=mapping==null?column:mapping[column];
                if(i>0 && phoneColumns.contains(canonical) && !value.isBlank() && (type.isEmpty() || type.equals("n"))){
                    try{value=exactNumericPhone(value,true);}
                    catch(IllegalArgumentException unsafe){rowErrors.add("Excel row "+physicalRow+": Phone numeric value is not an exact whole number of at most 15 digits. Store the phone as text or correct it from its original source.");}
                }
                if(i>0 && !value.isBlank() && dateTypes.containsKey(canonical)){
                    String expected=dateTypes.get(canonical);
                    if((type.isEmpty() || type.equals("n")) && value.matches("[0-9]+(?:\\.[0-9]+)?")){
                        try{
                            if(kocSheet&&expected.equals("TIMESTAMP")){
                                String original=value;boolean hasTime=new java.math.BigDecimal(value).remainder(java.math.BigDecimal.ONE).signum()!=0;
                                value=excelDate(value,hasTime?"TIMESTAMP":"DATE",date1904);
                                var date=hasTime?java.time.Instant.parse(value).atZone(com.kingofcards.crm.directional.SourceDateTime.SOURCE_ZONE).toLocalDate():java.time.LocalDate.parse(value);
                                rowSource=new com.kingofcards.crm.directional.SourceDateTime(date,hasTime,original);if(!hasTime)value="";
                            }
                            else if(expected.equals("TIMESTAMP") && new java.math.BigDecimal(value).remainder(java.math.BigDecimal.ONE).signum()==0)
                                rowErrors.add("Excel row "+physicalRow+": Timestamp is date-only; provide a date-time value with a time or an ISO timestamp with an explicit offset.");
                            else value=excelDate(value,expected,date1904);
                        }catch(LeadApiException invalidDate){rowErrors.add("Excel row "+physicalRow+": "+invalidDate.getMessage());}
                    }
                    else if(!kocSheet && expected.equals("TIMESTAMP") && (type.equals("s") || type.equals("inlineStr"))){
                        try{value=java.time.OffsetDateTime.parse(value).toInstant().toString();}
                        catch(java.time.format.DateTimeParseException ambiguous){rowErrors.add("Excel row "+physicalRow+": Timestamp text must be an ISO timestamp with an explicit timezone offset; date-only and local-time values are not accepted.");}
                    }
                    else if(!kocSheet && expected.equals("DATE") && (type.equals("s") || type.equals("inlineStr"))){
                        try{value=normalizeDateText(value);}
                        catch(RuntimeException ambiguous){rowErrors.add("Excel row "+physicalRow+": "+ambiguous.getMessage());}
                    }
                    else if(type.equals("d")){
                        try{
                            if(expected.equals("DATE")){
                                try{value=java.time.OffsetDateTime.parse(value).toLocalDate().toString();}
                                catch(java.time.format.DateTimeParseException offset){try{value=java.time.LocalDateTime.parse(value).toLocalDate().toString();}catch(java.time.format.DateTimeParseException local){value=java.time.LocalDate.parse(value).toString();}}
                            }else if(kocSheet&&value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")){
                                rowSource=new com.kingofcards.crm.directional.SourceDateTime(java.time.LocalDate.parse(value),false,value);value="";
                            }else value=java.time.OffsetDateTime.parse(value).toInstant().toString();
                        }catch(java.time.format.DateTimeParseException invalidDateTime){
                            rowErrors.add(expected.equals("TIMESTAMP")
                                    ? "Excel row "+physicalRow+": Timestamp cells must include a time and an explicit timezone offset."
                                    : "Excel row "+physicalRow+": Date cells must contain a valid ISO date or date-time value.");
                        }
                    }
                }
                values.set(column,value);
            }
            if(i==0){if(flexibleHeaders)mapping=mapHeaders(values);else if(!values.equals(headers))throw invalid("Use the exact ordered template header ("+headers.size()+" columns).");rows.add(new ParsedRow(physicalRow,values));continue;}
            if(values.stream().anyMatch(value->!value.isBlank())){
                rows.add(new ParsedRow(physicalRow,values,rowErrors,rowSource));if(rows.size()-1>maxRows)throw tooManyRows();
            }
        }
        return rows;
    }
    private String excelDate(String raw,String expected,boolean date1904){
        var serial=new java.math.BigDecimal(raw);if(serial.signum()<0 || serial.compareTo(new java.math.BigDecimal("2958465.999999"))>0)throw invalid("Excel date serial is out of range.");
        long days=serial.setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
        if(!date1904 && days==60)throw invalid("Excel's fictitious 1900-02-29 is not a valid business date.");
        var base=date1904?java.time.LocalDate.of(1904,1,1):java.time.LocalDate.of(1899,12,31);
        var date=base.plusDays(date1904?days:days>60?days-1:days);
        if(expected.equals("DATE"))return date.toString();
        long micros=serial.remainder(java.math.BigDecimal.ONE).multiply(new java.math.BigDecimal("86400000000")).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();
        return date.atStartOfDay().plusNanos(micros*1000).atZone(timeZone).toInstant().toString();
    }
    private static String exactNumericPhone(String raw){
        return exactNumericPhone(raw,false);
    }
    private static String exactNumericPhone(String raw,boolean rawXlsxNumericCell){
        final java.math.BigInteger integer;
        try{
            if(raw.length()>128)throw new ArithmeticException("Oversized numeric phone");
            var decimal=new java.math.BigDecimal(raw);var exact=decimal.stripTrailingZeros();
            // Bound the result before expanding an exponent into a large integer.
            if(exact.signum()<0 || exact.scale()>0 || (long)exact.precision()-exact.scale()>15)throw new ArithmeticException("Unsafe numeric phone");
            integer=exact.toBigIntegerExact();
            // A raw XLSX numeric cell is the stored number, not a formatted display.
            // 1.23456789E9 therefore exactly represents 1234567890. Scientific text
            // in CSV/text cells still needs explicit precision; never infer digits.
            if(!rawXlsxNumericCell&&(raw.contains("e")||raw.contains("E"))&&decimal.precision()<integer.abs().toString().length())throw new ArithmeticException("Insufficient significant digits");
        }
        catch(NumberFormatException | ArithmeticException unsafe){throw new IllegalArgumentException("Numeric phone is not an exact integer.",unsafe);}
        if(integer.signum()<0 || integer.abs().toString().length()>15)throw new IllegalArgumentException("Numeric phone may have lost digits.");
        return integer.toString();
    }
    private static String normalizeDateText(String raw){
        try{return java.time.LocalDate.parse(raw).toString();}catch(java.time.format.DateTimeParseException ignored){}
        var yearFirst=java.util.regex.Pattern.compile("^(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})$").matcher(raw);
        if(yearFirst.matches())return java.time.LocalDate.of(Integer.parseInt(yearFirst.group(1)),Integer.parseInt(yearFirst.group(2)),Integer.parseInt(yearFirst.group(3))).toString();
        var dayMonthYear=java.util.regex.Pattern.compile("^(\\d{1,2})([-/])(\\d{1,2})\\2(\\d{4})$").matcher(raw);
        if(dayMonthYear.matches()){
            int first=Integer.parseInt(dayMonthYear.group(1)),second=Integer.parseInt(dayMonthYear.group(3)),year=Integer.parseInt(dayMonthYear.group(4));
            if(first>12 && second<=12)return java.time.LocalDate.of(year,second,first).toString();
            if(second>12 && first<=12)return java.time.LocalDate.of(year,first,second).toString();
            throw new IllegalArgumentException("Date text has an ambiguous day/month order; use ISO YYYY-MM-DD.");
        }
        throw new IllegalArgumentException("Date text is not a supported date; use ISO YYYY-MM-DD or an unambiguous day/month format.");
    }
    private static Document xml(byte[] bytes) throws Exception {
        if(bytes==null)throw invalid("Required workbook part is missing.");
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private static String text(Element parent,String tag){var result=new StringBuilder();var nodes=parent.getElementsByTagNameNS("*",tag);for(int i=0;i<nodes.getLength();i++)result.append(nodes.item(i).getTextContent());return result.toString();}
    private static LeadApiException invalid(String message){return LeadApiException.badRequest(message);}
    private LeadApiException tooManyRows(){return invalid("Upload exceeds the maximum supported limit of "+maxRows+" populated data rows.");}
}
