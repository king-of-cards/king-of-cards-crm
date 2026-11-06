import java.net.*;import java.net.http.*;import java.nio.file.*;import java.time.*;import java.util.*;import java.security.*;
import com.google.auth.oauth2.*;import com.google.gson.*;
/** Operator investigation only: jobs/table GETs and fixed SELECT queries, never replay/DML/DDL. */
class CommitReceiptInvestigation {
 static final String PROJECT="king-of-cards-509610",REGION="asia-south1",DATASET="shared_rds_data";
 static final List<String> OPS=List.of("d2d869c8-f0f0-4662-8720-126134c52efe","65f6e25d-e1f4-43e2-a57c-228cef83393b","25f7049d-4ed5-4252-bae4-b0308af8d7d7");
 static final List<String> TABLES=List.of("crm_leads","crm_koc_to_vendor_details","crm_vendor_to_koc_details");
 static final Gson JSON=new Gson();static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
 static GoogleCredentials credentials;static Path output;static int calls;static Long historyUpperBound;static final long DEADLINE=System.nanoTime()+240_000_000_000L;
 static String enc(String s){return URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8);}
 static String sha(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);}
 static Properties load(Path p)throws Exception{var x=new Properties();try(var in=Files.newInputStream(p)){x.load(in);}return x;}
 static void store(Properties p,String name)throws Exception{try(var out=Files.newOutputStream(output.resolve(name),StandardOpenOption.CREATE_NEW)){p.store(out,"Read-only aggregate/job evidence; no customer fields or credentials");}}
 static JsonObject request(String method,String path,JsonObject body)throws Exception{
  if(System.nanoTime()>DEADLINE)throw new IllegalStateException("Investigation time limit reached; incomplete evidence");
  if(!(method.equals("GET")||(method.equals("POST")&&path.equals("/queries")&&body!=null&&body.get("query").getAsString().startsWith("SELECT ")&&!body.get("query").getAsString().contains(";"))))throw new IllegalArgumentException("Only metadata GET or a single fixed SELECT request allowed");
  credentials.refreshIfExpired();var builder=HttpRequest.newBuilder(URI.create("https://bigquery.googleapis.com/bigquery/v2/projects/"+PROJECT+path)).timeout(Duration.ofSeconds(18)).header("Authorization","Bearer "+credentials.getAccessToken().getTokenValue());
  if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
  var response=HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());calls++;
  if(response.statusCode()!=200){String reason="unavailable";try{var error=JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");reason=error.has("status")?error.get("status").getAsString():"HTTP_ERROR";if(response.statusCode()==404&&error.has("message")){String message=error.get("message").getAsString();if(message.contains("Not found: Table"))reason+="_RESULT_TABLE_NOT_FOUND";else if(message.contains("Not found: Job"))reason+="_QUERY_JOB_NOT_FOUND";}}catch(Exception ignored){}throw new IllegalStateException("Read-only request HTTP="+response.statusCode()+" reason="+reason+" endpoint="+path.split("\\?")[0]);}
  return JsonParser.parseString(response.body()).getAsJsonObject();
 }
 static String val(JsonObject row,String name){if(row==null)return null;var v=row.get(name);return v==null||v.isJsonNull()?null:v.getAsString();}
 static List<Map<String,String>> query(String sql)throws Exception{return query(sql,null);}
 static List<Map<String,String>> query(String sql,JsonArray parameters)throws Exception{
  var body=new JsonObject();body.addProperty("query",sql);body.addProperty("useLegacySql",false);body.addProperty("useQueryCache",false);body.addProperty("location",REGION);body.addProperty("timeoutMs",10000);body.addProperty("maximumBytesBilled","100000000");body.addProperty("maxResults",1000);
  if(parameters!=null){body.addProperty("parameterMode","NAMED");body.add("queryParameters",parameters);}
  var result=request("POST","/queries",body);String id=result.getAsJsonObject("jobReference").get("jobId").getAsString();
  for(int i=0;!result.get("jobComplete").getAsBoolean()&&i<6;i++)result=request("GET","/queries/"+enc(id)+"?location="+REGION+"&timeoutMs=10000&maxResults=1000",null);
  if(!result.get("jobComplete").getAsBoolean()||result.has("pageToken")||result.has("errors"))throw new IllegalStateException("SELECT result incomplete; no claim of intact data");
  var fields=result.getAsJsonObject("schema").getAsJsonArray("fields");var rows=new ArrayList<Map<String,String>>();
  if(result.has("rows"))for(var element:result.getAsJsonArray("rows")){var cells=element.getAsJsonObject().getAsJsonArray("f");var row=new LinkedHashMap<String,String>();for(int i=0;i<fields.size();i++){var v=cells.get(i).getAsJsonObject().get("v");row.put(fields.get(i).getAsJsonObject().get("name").getAsString(),v==null||v.isJsonNull()?null:v.getAsString());}rows.add(row);}return rows;
 }
 static Map<String,JsonObject> list(boolean active,Properties evidence,int round)throws Exception{
  String token=null;var tokens=new HashSet<String>();var seenJobs=new HashSet<String>();var found=new LinkedHashMap<String,JsonObject>();int pages=0,listed=0;long minCreated=Instant.now().minus(Duration.ofDays(90)).toEpochMilli();
  do{
   String path="/jobs?allUsers=false&projection=minimal&maxResults=1000&fields="+enc("nextPageToken,jobs(jobReference,status)")+(active?"&stateFilter=pending&stateFilter=running":"&stateFilter=done&minCreationTime="+minCreated+(historyUpperBound==null?"":"&maxCreationTime="+historyUpperBound));if(token!=null)path+="&pageToken="+enc(token);
   var page=request("GET",path,null);pages++;
   if(page.has("jobs"))for(var e:page.getAsJsonArray("jobs")){listed++;var job=e.getAsJsonObject();String id=job.getAsJsonObject("jobReference").get("jobId").getAsString();if(!seenJobs.add(id))throw new IllegalStateException("Repeated job across pages; discovery not complete");String op=OPS.stream().filter(s->id.endsWith("_"+s.replace("-",""))).findFirst().orElse(null);
    if(active||op!=null){var full=request("GET","/jobs/"+enc(id)+"?location="+REGION,null);if(active){String state=full.getAsJsonObject("status").get("state").getAsString();if(!state.equals("DONE")){evidence.setProperty("nonterminal."+id,state);System.out.println("NONTERMINAL_OWN_JOB="+id+" state="+state);}}if(op!=null&&found.putIfAbsent(op,full)!=null)throw new IllegalStateException("Multiple actor jobs for original operation; review required");}
   }
   token=val(page,"nextPageToken");if(token!=null&&!tokens.add(token))throw new IllegalStateException("Repeated page token; pagination incomplete");
   System.out.println("OWN_JOB_PAGE round="+round+" active="+active+" page="+pages+" listed="+listed+" more="+(token!=null));
   if(pages>500)throw new IllegalStateException("Own-job pagination safety limit; incomplete evidence");
  }while(token!=null);
  evidence.setProperty("scan"+round+"."+(active?"active":"historical")+".pages",Integer.toString(pages));evidence.setProperty("scan"+round+"."+(active?"active":"historical")+".listed",Integer.toString(listed));evidence.setProperty("scan"+round+"."+(active?"active":"historical")+".complete","true");return found;
 }
 static Map<String,String> params(JsonObject job){var p=new LinkedHashMap<String,String>();var q=job.getAsJsonObject("configuration").getAsJsonObject("query");if(q.has("queryParameters"))for(var e:q.getAsJsonArray("queryParameters")){var x=e.getAsJsonObject();p.put(x.get("name").getAsString(),val(x.getAsJsonObject("parameterValue"),"value"));}return p;}
 static Map<String,JsonObject> exactOriginals(Properties evidence)throws Exception{
  var actors=query("SELECT DISTINCT actor FROM (SELECT created_by_subject_id AS actor FROM `"+PROJECT+"."+DATASET+".crm_leads` UNION DISTINCT SELECT updated_by_subject_id AS actor FROM `"+PROJECT+"."+DATASET+".crm_leads`) WHERE actor IS NOT NULL");
  if(actors.size()>30)throw new IllegalStateException("Too many original actor candidates; narrow scope before more checks");
  var found=new LinkedHashMap<String,JsonObject>();
  for(var row:actors){String actor=UUID.fromString(row.get("actor")).toString();for(String op:OPS){String id="koc_direction_v1_"+actor.replace("-","")+"_"+op.replace("-","");JsonObject job;
   try{job=request("GET","/jobs/"+enc(id)+"?location="+REGION,null);}catch(IllegalStateException missing){if(missing.getMessage().startsWith("Read-only request HTTP=404 "))continue;throw missing;}
   if(found.putIfAbsent(op,job)!=null)throw new IllegalStateException("Multiple actor jobs for original operation; review required");
  }}
  evidence.setProperty("originalActorCandidates",Integer.toString(actors.size()));evidence.setProperty("exactOriginalJobs",Integer.toString(found.size()));System.out.println("EXACT_ORIGINAL_JOB_LOOKUP actorCandidates="+actors.size()+" found="+found.size()+" no new operation IDs");return found;
 }
 static void committedBatchReadback(String op,JsonObject job,Properties evidence)throws Exception{
  var p=params(job);if(!"KOC_TO_VENDOR".equals(p.get("direction"))||!op.equals(p.get("batch")))throw new IllegalStateException("Fallback only supports original KOC import batch");
  String sql=job.getAsJsonObject("configuration").getAsJsonObject("query").get("query").getAsString();if(!sql.contains("BEGIN TRANSACTION;")||!sql.contains("COMMIT TRANSACTION;\nSELECT row_index,lead_id,status,"))throw new IllegalStateException("Unrecognized original transactional job");
  var raw=new HashMap<String,JsonObject>();for(var element:job.getAsJsonObject("configuration").getAsJsonObject("query").getAsJsonArray("queryParameters")){var value=element.getAsJsonObject();raw.put(value.get("name").getAsString(),value);}
  var master=List.of("customer_name","phone","service","stage","source","vendor_name");var detail=List.of("relation","sheet_timestamp","branch","interested","occasion_date","occasion","updates","additional_comments","quoted_amount","date_quoted");var fields=new ArrayList<String>();fields.add("id");fields.addAll(master);fields.addAll(detail);
  var incoming=new ArrayList<String>();var parameters=new JsonArray();int expected=0;
  while(p.containsKey("id_"+expected)){var columns=new ArrayList<String>();for(String field:fields){String name=field+"_"+expected;var value=raw.get(name);if(value==null)throw new IllegalStateException("Original persisted-field parameter missing");value=value.deepCopy();if(!value.has("parameterValue")){var nullValue=new JsonObject();nullValue.add("value",JsonNull.INSTANCE);value.add("parameterValue",nullValue);}parameters.add(value);columns.add("@"+name+" AS "+(field.equals("id")?"lead_id":field));}incoming.add("SELECT "+String.join(",",columns));expected++;}
  for(String field:List.of("actor","vendor","batch"))parameters.add(raw.get(field).deepCopy());
  var same=new ArrayList<String>();for(String field:master)same.add("m."+field+" IS NOT DISTINCT FROM i."+field);for(String field:detail)same.add("d."+field+" IS NOT DISTINCT FROM i."+field);same.add("d.vendor_name IS NOT DISTINCT FROM i.vendor_name");
  String check="SELECT COUNT(*) AS expected_rows,COUNT(DISTINCT i.lead_id) AS distinct_ids,COUNTIF(m.lead_id IS NULL) AS missing_master,COUNTIF(d.lead_id IS NULL) AS missing_detail,COUNTIF(m.vendor_subject_id IS DISTINCT FROM @vendor OR m.lead_direction IS DISTINCT FROM 'KOC_TO_VENDOR' OR m.created_by_subject_id IS DISTINCT FROM @actor) AS scope_mismatches,COUNTIF(m.import_batch_id IS DISTINCT FROM @batch) AS batch_mismatches,COUNTIF(NOT("+String.join(" AND ",same)+")) AS persisted_field_mismatches FROM ("+String.join(" UNION ALL ",incoming)+") i LEFT JOIN `"+PROJECT+"."+DATASET+".crm_leads` m ON m.lead_id=i.lead_id LEFT JOIN `"+PROJECT+"."+DATASET+".crm_koc_to_vendor_details` d ON d.lead_id=i.lead_id";
  var counts=query(check,parameters).getFirst();for(var e:counts.entrySet())evidence.setProperty(op+".recovered."+e.getKey(),e.getValue());
  if(expected==0||!counts.get("expected_rows").equals(Integer.toString(expected))||!counts.get("distinct_ids").equals(Integer.toString(expected))||counts.entrySet().stream().filter(e->!List.of("expected_rows","distinct_ids","batch_mismatches").contains(e.getKey())).anyMatch(e->!"0".equals(e.getValue())))throw new IllegalStateException("Terminal job all-field readback differs; unresolved");
  if(!"0".equals(counts.get("batch_mismatches"))){
   String id=job.getAsJsonObject("jobReference").get("jobId").getAsString();String token=null;int children=0;long inserted=0,detailInserted=0;boolean masterInsertSeen=false,detailInsertSeen=false;
   do{String path="/jobs?allUsers=false&projection=minimal&maxResults=1000&parentJobId="+enc(id)+"&fields="+enc("nextPageToken,jobs(jobReference,status)");if(token!=null)path+="&pageToken="+enc(token);var page=request("GET",path,null);
    if(page.has("jobs"))for(var element:page.getAsJsonArray("jobs")){children++;String childId=element.getAsJsonObject().getAsJsonObject("jobReference").get("jobId").getAsString();var child=request("GET","/jobs/"+enc(childId)+"?location="+REGION,null);var childStatus=child.getAsJsonObject("status");if(!"DONE".equals(val(childStatus,"state"))||childStatus.has("errorResult"))throw new IllegalStateException("Child transaction job not successfully terminal");
     if(!id.equals(val(child.getAsJsonObject("statistics"),"parentJobId")))throw new IllegalStateException("Child metadata belongs to a different original job");
     var statistics=child.getAsJsonObject("statistics").getAsJsonObject("query");String statement=val(statistics,"statementType");var config=child.getAsJsonObject("configuration").getAsJsonObject("query");String childSql=val(config,"query");
     if("INSERT".equals(statement)&&childSql!=null&&childSql.stripLeading().startsWith("INSERT INTO `"+PROJECT+"."+DATASET+".crm_leads`")){masterInsertSeen=true;String affected=val(statistics,"numDmlAffectedRows");if(affected==null)throw new IllegalStateException("Original insert count unavailable");inserted+=Long.parseLong(affected);}
     if("INSERT".equals(statement)&&childSql!=null&&childSql.stripLeading().startsWith("INSERT INTO `"+PROJECT+"."+DATASET+".crm_koc_to_vendor_details`")){detailInsertSeen=true;String affected=val(statistics,"numDmlAffectedRows");if(affected==null)throw new IllegalStateException("Original detail insert count unavailable");detailInserted+=Long.parseLong(affected);}
    }token=val(page,"nextPageToken");if(children>100)throw new IllegalStateException("Unexpected child job count");
   }while(token!=null);
   evidence.setProperty(op+".childJobs",Integer.toString(children));evidence.setProperty(op+".masterInsertSeen",Boolean.toString(masterInsertSeen));if(masterInsertSeen)evidence.setProperty(op+".confirmedInsertedByDml",Long.toString(inserted));if(detailInsertSeen)evidence.setProperty(op+".confirmedDetailInsertedByDml",Long.toString(detailInserted));
   if(masterInsertSeen&&detailInsertSeen&&inserted==expected&&detailInserted==expected){evidence.setProperty(op+".outcome","COMMITTED_DML_CONFIRMED");evidence.setProperty(op+".imported",Integer.toString(expected));evidence.setProperty(op+".skipped","0");evidence.setProperty(op+".failed","0");evidence.setProperty(op+".receiptCache","NOT_FOUND");System.out.println("OPERATION="+op+" outcome=COMMITTED_DML_CONFIRMED imported="+expected+" skipped=0 failed=0 parentAndChildrenSuccessfullyTerminal=true originalReceiptCache=NOT_FOUND originalBatchIdsNowDiffer="+counts.get("batch_mismatches"));return;}
   evidence.setProperty(op+".outcome","COMMITTED_TERMINAL_RECEIPT_UNAVAILABLE");evidence.setProperty(op+".receiptCache","NOT_FOUND");
   System.out.println("OPERATION="+op+" outcome=COMMITTED_TERMINAL_RECEIPT_UNAVAILABLE childJobs="+children+" confirmedInsertedByDml="+(masterInsertSeen?inserted:"UNKNOWN")+" skippedAndFailedCounts=UNKNOWN allPersistedBusinessFieldsMatch=true originalBatchIdsDiffer="+counts.get("batch_mismatches"));return;
  }
  evidence.setProperty(op+".outcome","COMMITTED_READBACK_CONFIRMED");evidence.setProperty(op+".imported",Integer.toString(expected));evidence.setProperty(op+".skipped","0");evidence.setProperty(op+".failed","0");evidence.setProperty(op+".receiptCache","NOT_FOUND");System.out.println("OPERATION="+op+" outcome=COMMITTED_READBACK_CONFIRMED imported="+expected+" skipped=0 failed=0 originalReceiptCache=NOT_FOUND allPersistedBusinessFieldsMatch=true");
 }
 static void inspect(String op,JsonObject job,Properties evidence)throws Exception{
  if(job==null){System.out.println("OPERATION="+op+" outcome=UNRESOLVED_JOB_NOT_VISIBLE");evidence.setProperty(op+".outcome","UNRESOLVED_JOB_NOT_VISIBLE");return;}
  String id=job.getAsJsonObject("jobReference").get("jobId").getAsString();var status=job.getAsJsonObject("status");String state=val(status,"state");var p=params(job);String actor=p.get("actor"),vendor=p.get("vendor"),direction=p.get("direction");
  if(actor==null||!id.equals("koc_direction_v1_"+UUID.fromString(actor).toString().replace("-","")+"_"+op.replace("-","")))throw new IllegalStateException("Original actor/job-ID binding failed");
  if(!List.of("KOC_TO_VENDOR","VENDOR_TO_KOC").contains(direction)||vendor==null)throw new IllegalStateException("Original scope unavailable");UUID.fromString(vendor);
  evidence.setProperty(op+".jobId",id);evidence.setProperty(op+".vendorId",vendor);evidence.setProperty(op+".direction",direction);evidence.setProperty(op+".actorId",actor);evidence.setProperty(op+".state",state);
  if(!"DONE".equals(state)){evidence.setProperty(op+".outcome","UNRESOLVED_NONTERMINAL");System.out.println("OPERATION="+op+" state="+state);return;}
  if(status.has("errorResult")){evidence.setProperty(op+".outcome","FAILED_TERMINAL");evidence.setProperty(op+".errorReason",val(status.getAsJsonObject("errorResult"),"reason"));System.out.println("OPERATION="+op+" state=DONE outcome=FAILED_TERMINAL");return;}
  int expected=0;while(p.containsKey("id_"+expected))expected++;evidence.setProperty(op+".expectedRows",Integer.toString(expected));evidence.setProperty(op+".nameSnapshotPresent",Boolean.toString(p.get("vendor_name_0")!=null));
  // Only the original job result is read. Do not execute its stored SQL.
  JsonObject receipt;try{receipt=request("GET","/queries/"+enc(id)+"?location="+REGION+"&maxResults=1000",null);}catch(IllegalStateException unavailable){if(unavailable.getMessage().startsWith("Read-only request HTTP=404 ")){committedBatchReadback(op,job,evidence);return;}throw unavailable;}
  if(!receipt.has("jobComplete")||!receipt.get("jobComplete").getAsBoolean()||receipt.has("pageToken")||!receipt.has("rows"))throw new IllegalStateException("Original receipt absent or incomplete: "+op);
  var schema=receipt.getAsJsonObject("schema").getAsJsonArray("fields");var names=new ArrayList<String>();for(var f:schema)names.add(f.getAsJsonObject().get("name").getAsString());int statusIndex=names.indexOf("status"),idIndex=names.indexOf("lead_id");if(statusIndex<0||idIndex<0)throw new IllegalStateException("Unsupported original receipt");
  int imported=0,skipped=0,failed=0;var ids=new LinkedHashSet<String>();
  for(var row:receipt.getAsJsonArray("rows")){var f=row.getAsJsonObject().getAsJsonArray("f");String rowState=f.get(statusIndex).getAsJsonObject().get("v").getAsString();String lead=f.get(idIndex).getAsJsonObject().get("v").getAsString();UUID.fromString(lead);ids.add(lead);switch(rowState){case "IMPORTED","UPDATED"->imported++;case "SKIPPED"->skipped++;case "FAILED"->failed++;default->throw new IllegalStateException("Unknown row outcome");}}
  if(imported+skipped+failed!=expected||failed!=0)throw new IllegalStateException("Receipt needs review; failed/count mismatch: "+op);
  String detail=direction.equals("KOC_TO_VENDOR")?"crm_koc_to_vendor_details":"crm_vendor_to_koc_details";String literalIds=String.join(",",ids.stream().map(s->"'"+s+"'").toList());
  var counts=query("SELECT COUNT(*) AS master_rows,COUNT(DISTINCT m.lead_id) AS distinct_ids,COUNTIF(d.lead_id IS NULL) AS missing_details,COUNTIF(m.vendor_subject_id IS DISTINCT FROM '"+vendor+"' OR m.lead_direction IS DISTINCT FROM '"+direction+"') AS scope_mismatches,COUNTIF(m.vendor_name IS DISTINCT FROM d.vendor_name) AS name_mismatches FROM `"+PROJECT+"."+DATASET+".crm_leads` m LEFT JOIN `"+PROJECT+"."+DATASET+"."+detail+"` d ON d.lead_id=m.lead_id WHERE m.lead_id IN ("+literalIds+")").getFirst();
  for(var e:counts.entrySet())evidence.setProperty(op+"."+e.getKey(),e.getValue());
  if(!counts.get("master_rows").equals(Integer.toString(ids.size()))||!counts.get("distinct_ids").equals(Integer.toString(ids.size()))||!counts.get("missing_details").equals("0")||!counts.get("scope_mismatches").equals("0")||!counts.get("name_mismatches").equals("0"))throw new IllegalStateException("Original receipt readback mismatch: "+op);
  evidence.setProperty(op+".outcome","COMMITTED");evidence.setProperty(op+".imported",Integer.toString(imported));evidence.setProperty(op+".skipped",Integer.toString(skipped));evidence.setProperty(op+".failed",Integer.toString(failed));System.out.println("OPERATION="+op+" outcome=COMMITTED imported="+imported+" skipped="+skipped+" failed="+failed+" matchingMasterAndDetail="+ids.size()+" trustedNameSnapshot="+p.containsKey("vendor_name_0"));
 }
 static void integrity(Properties evidence)throws Exception{
  var baseline=load(Path.of("C:/kocbackend/king-of-cards-crm/backend/crm-service/target-source-precision-verify/source-date-migration-20261105/before.properties"));var sql=new ArrayList<String>();
  for(String table:TABLES){String columns=baseline.getProperty("columns."+table);if(columns==null||!columns.matches("[a-z_]+(?:,[a-z_]+)*"))throw new IllegalStateException("Unsafe baseline column list");sql.add("SELECT '"+table+"' AS table_name,COUNT(*) AS row_count,COUNT(DISTINCT lead_id) AS unique_ids,TO_HEX(SHA256(COALESCE(STRING_AGG(row_hash,'' ORDER BY row_hash),''))) AS content_digest FROM (SELECT lead_id,TO_HEX(SHA256(TO_JSON_STRING(t))) AS row_hash FROM (SELECT "+columns+" FROM `"+PROJECT+"."+DATASET+"."+table+"`) t)");}
  for(var row:query(String.join(" UNION ALL ",sql))){String table=row.get("table_name");boolean same=row.get("row_count").equals(baseline.getProperty("rows."+table))&&row.get("unique_ids").equals(baseline.getProperty("unique."+table))&&row.get("content_digest").equals(baseline.getProperty("digest."+table));evidence.setProperty("rows."+table,row.get("row_count"));evidence.setProperty("unchanged."+table,Boolean.toString(same));System.out.println("DATA_INTEGRITY="+table+" rows="+row.get("row_count")+" unique="+row.get("unique_ids")+" fullExistingColumnDigestUnchanged="+same);if(!same)throw new IllegalStateException("Baseline differs; do not change records or proceed");}
 }
 public static void main(String[]args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("New isolated evidence directory required");output=Path.of(args[0]).toAbsolutePath().normalize();Path allowed=Path.of("C:/kocbackend/king-of-cards-crm/backend/crm-service/target-drain-control-verify");if(!output.startsWith(allowed)||Files.exists(output))throw new IllegalArgumentException("New approved evidence subdirectory only");Files.createDirectories(output);
  var evidence=new Properties();evidence.setProperty("ready","false");evidence.setProperty("timestamp",Instant.now().toString());
  try{
   var status=load(Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control/status.properties"));if(!"true".equals(status.getProperty("writesBlocked"))||!"true".equals(status.getProperty("gateInstalled")))throw new IllegalStateException("Write barrier not confirmed");for(String key:List.of("pid","agentSha256","applicationSha256","principalSha256"))evidence.setProperty(key,status.getProperty(key));
   credentials=GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/bigquery");if(!(credentials instanceof ServiceAccountCredentials c)||!sha(c.getClientEmail()).equals(status.getProperty("principalSha256")))throw new IllegalStateException("Credential identity differs from actual CRM");
   for(String name:List.of("bigquery.googleapis.com","oauth2.googleapis.com"))System.out.println("JAVA_DNS="+name+" addresses="+InetAddress.getAllByName(name).length);
   list(true,evidence,1);integrity(evidence);var originals=exactOriginals(evidence);
   for(String op:OPS)if(originals.containsKey(op)){try{inspect(op,originals.get(op),evidence);}catch(Exception error){evidence.setProperty(op+".outcome","UNRESOLVED");evidence.setProperty(op+".failure",error.getClass().getSimpleName());System.out.println("OPERATION="+op+" outcome=UNRESOLVED cause="+error.getClass().getSimpleName()+" detail="+error.getMessage());}}
   store(evidence,"original-checkpoint.properties");list(true,evidence,2);
   if(originals.size()!=OPS.size()&&!Boolean.getBoolean("koc.receipts.exact-only")){
    // The user identifies d2d... as preceding the first committed 65f... import.
    // Bound that search by its actual stored creation time, not today's read traffic.
    if(originals.size()==2&&!originals.containsKey(OPS.getFirst())&&originals.containsKey(OPS.get(1))){historyUpperBound=originals.get(OPS.get(1)).getAsJsonObject("statistics").get("creationTime").getAsLong();evidence.setProperty("originalHistoryUpperBound",Instant.ofEpochMilli(historyUpperBound).toString());System.out.println("LEGACY_HISTORY_BOUND="+Instant.ofEpochMilli(historyUpperBound)+" basedOnOriginalImportMetadataAndConfirmedOperationOrder");}
    var historic=list(false,evidence,1);for(String op:OPS)if(!originals.containsKey(op))inspect(op,historic.get(op),evidence);
   }
   for(String op:OPS)if(!evidence.containsKey(op+".outcome"))evidence.setProperty(op+".outcome","UNRESOLVED_JOB_NOT_VISIBLE");
   long unknown=OPS.stream().filter(op->!List.of("COMMITTED","COMMITTED_READBACK_CONFIRMED","COMMITTED_DML_CONFIRMED","FAILED_TERMINAL").contains(evidence.getProperty(op+".outcome","UNRESOLVED"))).count();long nonterminal=evidence.stringPropertyNames().stream().filter(k->k.startsWith("nonterminal.")).count();evidence.setProperty("unresolvedKnownOperations",Long.toString(unknown));evidence.setProperty("nonterminalOwnJobs",Long.toString(nonterminal));evidence.setProperty("ready",Boolean.toString(unknown==0&&nonterminal==0));System.out.println("COMMIT_EVIDENCE_READY="+evidence.getProperty("ready")+" requests="+calls+" no DML/DDL or original-operation retry");
  }catch(Exception error){evidence.setProperty("failure",error.getClass().getSimpleName());System.out.println("INVESTIGATION_INCOMPLETE="+error.getClass().getSimpleName()+" detail="+error.getMessage());}
  finally{evidence.setProperty("timestamp",Instant.now().toString());store(evidence,"evidence.properties");}
 }
}
