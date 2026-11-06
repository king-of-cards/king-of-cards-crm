import java.nio.file.*;import java.util.*;import java.security.*;import java.time.*;
import com.google.cloud.bigquery.*;import com.google.auth.oauth2.ServiceAccountCredentials;
/** Own-principal metadata + receipt/readback checks only. Never DDL/DML or allUsers(). */
class ReadOnlyCommitDrain {
 static final String PROJECT="king-of-cards-509610",LOCATION="asia-south1";
 static final List<String> KNOWN=List.of("d2d869c8-f0f0-4662-8720-126134c52efe","65f6e25d-e1f4-43e2-a57c-228cef83393b","25f7049d-4ed5-4252-bae4-b0308af8d7d7");
 static Properties load(Path p)throws Exception{var data=new Properties();try(var in=Files.newInputStream(p)){data.load(in);}return data;}
 static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);}
 static boolean crm(Job job){if(job.getJobId().getJob().startsWith("koc_direction_v1_")||job.getJobId().getJob().startsWith("koc_lead_import_v1_"))return true;
  if(job.getConfiguration() instanceof QueryJobConfiguration q){String sql=q.getQuery().toLowerCase(Locale.ROOT);return sql.contains("crm_leads")||sql.contains("crm_koc_to_vendor_details")||sql.contains("crm_vendor_to_koc_details");}return true;}
 static boolean readOnly(Job job){return job.getStatistics() instanceof JobStatistics.QueryStatistics s&&s.getStatementType()==JobStatistics.QueryStatistics.StatementType.SELECT;}
 static Map<String,Job> scan(BigQuery client,Properties proof,int round)throws Exception{
  var found=new LinkedHashMap<String,Job>();int total=0,pending=0;long started=System.currentTimeMillis();
  // The default jobs.list scope is this authenticated principal only. Never request allUsers.
  for(Job summary:client.listJobs(BigQuery.JobListOption.pageSize(50),BigQuery.JobListOption.stateFilter(JobStatus.State.PENDING,JobStatus.State.RUNNING),BigQuery.JobListOption.fields(BigQuery.JobField.JOB_REFERENCE,BigQuery.JobField.STATUS)).iterateAll()){
   if(++total>100000)throw new IllegalStateException("Own-job visibility scan incomplete; do not deploy");String id=summary.getJobId().getJob();
   String known=KNOWN.stream().filter(op->id.endsWith("_"+op.replace("-",""))).findFirst().orElse(null);
   boolean active=summary.getStatus()==null||summary.getStatus().getState()!=JobStatus.State.DONE;
   if(known!=null||active){var ref=summary.getJobId();if(ref.getLocation()==null)ref=JobId.newBuilder().setProject(PROJECT).setLocation(LOCATION).setJob(id).build();Job job=client.getJob(ref);if(job==null)throw new IllegalStateException("Job metadata unavailable; do not deploy");
    if(known!=null){if(found.putIfAbsent(known,job)!=null)throw new IllegalStateException("Multiple original actors found; reconcile exact actor before deployment");}
    if((job.getStatus()==null||job.getStatus().getState()!=JobStatus.State.DONE)&&crm(job)&&!readOnly(job)){pending++;System.out.println("NONTERMINAL_CRM_WRITE_JOB="+id+" state="+job.getStatus().getState());}
   }
  }
  // Default REST jobs.list scope is this credential only; no IAM expansion or allUsers.
  int historical=0;
  for(Job summary:client.listJobs(BigQuery.JobListOption.pageSize(50),BigQuery.JobListOption.minCreationTime(Instant.now().minus(Duration.ofDays(90)).toEpochMilli()),BigQuery.JobListOption.stateFilter(JobStatus.State.DONE),BigQuery.JobListOption.fields(BigQuery.JobField.JOB_REFERENCE,BigQuery.JobField.STATUS)).iterateAll()){
   if(++historical>100000||System.currentTimeMillis()-started>90000)throw new IllegalStateException("Original receipt discovery incomplete; do not deploy");
   if(historical%250==0)System.out.println("OWN_RECEIPT_DISCOVERY round="+round+" inspected="+historical);
   String id=summary.getJobId().getJob();String op=KNOWN.stream().filter(value->id.endsWith("_"+value.replace("-",""))).findFirst().orElse(null);if(op==null)continue;
   Job job=client.getJob(JobId.newBuilder().setProject(PROJECT).setLocation(LOCATION).setJob(id).build());
   if(job==null||found.putIfAbsent(op,job)!=null)throw new IllegalStateException("Original actor/job metadata is missing or ambiguous; do not deploy");
  }
  proof.setProperty("scan"+round+"OwnHistoricalJobs",Integer.toString(historical));
  proof.setProperty("scan"+round+"Jobs",Integer.toString(total));proof.setProperty("scan"+round+"ActiveWrites",Integer.toString(pending));proof.setProperty("scan"+round+"Complete","true");
  System.out.println("OWN_PRINCIPAL_SCAN="+round+" listed="+total+" complete=true activeCrmWriteJobs="+pending+" durationMs="+(System.currentTimeMillis()-started));return found;
 }
 static void readback(BigQuery client,Job job,List<String> ids,String op,Properties proof)throws Exception{
  if(ids.isEmpty())return;var q=(QueryJobConfiguration)job.getConfiguration();var p=q.getNamedParameters();String vendor=p.get("vendor").getValue(),direction=p.get("direction").getValue();
  String detail=switch(direction){case "KOC_TO_VENDOR"->"crm_koc_to_vendor_details";case "VENDOR_TO_KOC"->"crm_vendor_to_koc_details";default->throw new IllegalStateException("Invalid stored operation direction");};
  String sql="SELECT COUNT(*) AS master_rows,COUNT(DISTINCT m.lead_id) AS distinct_ids,COUNTIF(d.lead_id IS NULL) AS missing_details,COUNTIF(m.vendor_subject_id!=@vendor OR m.lead_direction!=@direction) AS scope_mismatches,COUNTIF(m.vendor_name IS DISTINCT FROM d.vendor_name) AS name_mismatches FROM `king-of-cards-509610.shared_rds_data.crm_leads` m LEFT JOIN `king-of-cards-509610.shared_rds_data."+detail+"` d ON d.lead_id=m.lead_id WHERE m.lead_id IN UNNEST(@ids)";
  var config=QueryJobConfiguration.newBuilder(sql).addNamedParameter("ids",QueryParameterValue.array(ids.stream().distinct().toArray(String[]::new),String.class)).addNamedParameter("vendor",QueryParameterValue.string(vendor)).addNamedParameter("direction",QueryParameterValue.string(direction)).setUseLegacySql(false).setUseQueryCache(false).setMaximumBytesBilled(100000000L).setJobTimeoutMs(20000L).build();
  for(var row:client.query(config,JobId.newBuilder().setProject(PROJECT).setLocation(LOCATION).setJob("koc_direction_read_drain_"+UUID.randomUUID().toString().replace("-","")).build()).iterateAll()){
   for(String key:List.of("master_rows","distinct_ids","missing_details","scope_mismatches","name_mismatches"))proof.setProperty(op+"."+key,Long.toString(row.get(key).getLongValue()));
   long count=ids.stream().distinct().count();if(row.get("master_rows").getLongValue()!=count||row.get("distinct_ids").getLongValue()!=count||row.get("missing_details").getLongValue()!=0||row.get("scope_mismatches").getLongValue()!=0||row.get("name_mismatches").getLongValue()!=0)throw new IllegalStateException("Stored operation readback integrity mismatch");
   System.out.println("RECEIPT_READBACK="+op+" matchingMasterAndDetail="+count+" scopeAndNamesMatch=true");
  }
 }
 public static void main(String[]args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("Private drain status directory required");Path dir=Path.of(args[0]).toAbsolutePath().normalize();if(!dir.equals(Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control").toAbsolutePath().normalize()))throw new IllegalArgumentException("Unapproved control directory");
  var status=load(dir.resolve("status.properties"));if(!"true".equals(status.getProperty("gateInstalled"))||!"true".equals(status.getProperty("writesBlocked")))throw new IllegalStateException("Actual closed write admission gate required; job listings alone never prove drain");
  for(String key:List.of("httpActive","queued","processing","gateActive"))if(!"0".equals(status.getProperty(key)))throw new IllegalStateException("HTTP handlers not drained");
  var client=BigQueryOptions.newBuilder().setProjectId(PROJECT).setLocation(LOCATION)
   .setTransportOptions(com.google.cloud.http.HttpTransportOptions.newBuilder().setConnectTimeout(10000).setReadTimeout(10000).build())
   .setRetrySettings(BigQueryOptions.getDefaultRetrySettings().toBuilder().setTotalTimeoutDuration(Duration.ofSeconds(30)).setMaxAttempts(2).build())
   .build().getService();if(!(client.getOptions().getCredentials() instanceof ServiceAccountCredentials credentials)||!hash(credentials.getClientEmail()).equals(status.getProperty("principalSha256")))throw new IllegalStateException("Audit identity differs from actual running CRM identity");
  Properties proof=new Properties();for(String key:List.of("pid","agentSha256","applicationSha256","principalSha256"))proof.setProperty(key,status.getProperty(key));proof.setProperty("ready","false");
  // Invalidate any older proof before a remote check can fail. A failed check
  // must never leave a previous successful proof usable for shutdown.
  proof.setProperty("timestamp",Instant.now().toString());
  Path initial=dir.resolve("drain-evidence-next.properties");try(var out=Files.newOutputStream(initial)){proof.store(out,"Verification in progress; NOT ready for shutdown");}
  Files.move(initial,dir.resolve("drain-evidence.properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  var first=scan(client,proof,1);Thread.sleep(5000);var last=scan(client,proof,2);int unknown=0;
  for(String op:KNOWN){Job job=last.get(op);if(job==null){unknown++;System.out.println("ORIGINAL_OPERATION="+op+" state=NOT_FOUND_UNRESOLVED");continue;}
   String state=job.getStatus()==null?"UNKNOWN":job.getStatus().getState().name();proof.setProperty(op+".jobId",job.getJobId().getJob());proof.setProperty(op+".state",state);
   if(job.getStatus()==null||job.getStatus().getState()!=JobStatus.State.DONE){unknown++;System.out.println("ORIGINAL_OPERATION="+op+" state="+state);continue;}
   if(job.getStatus().getError()!=null){proof.setProperty(op+".outcome","FAILED_TERMINAL");System.out.println("ORIGINAL_OPERATION="+op+" state=FAILED_TERMINAL reason="+job.getStatus().getError().getReason()+"; no retry performed");continue;}
   int imported=0,skipped=0,failed=0;var ids=new ArrayList<String>();var result=job.getQueryResults();boolean rowStatus=result.getSchema().getFields().stream().anyMatch(f->f.getName().equals("status"));
   if(!rowStatus){unknown++;System.out.println("ORIGINAL_OPERATION="+op+" result=UNSUPPORTED_RECEIPT_UNRESOLVED");continue;}
   for(var row:result.iterateAll()){switch(row.get("status").getStringValue()){case "IMPORTED","UPDATED"->imported++;case "SKIPPED"->skipped++;case "FAILED"->failed++;default->throw new IllegalStateException("Unknown original receipt status");}ids.add(row.get("lead_id").getStringValue());}
   proof.setProperty(op+".outcome","COMMITTED");proof.setProperty(op+".imported",Integer.toString(imported));proof.setProperty(op+".skipped",Integer.toString(skipped));proof.setProperty(op+".failed",Integer.toString(failed));readback(client,job,ids,op,proof);System.out.println("ORIGINAL_OPERATION="+op+" state=COMMITTED imported="+imported+" skipped="+skipped+" failed="+failed);
  }
  int active=Math.max(Integer.parseInt(proof.getProperty("scan1ActiveWrites")),Integer.parseInt(proof.getProperty("scan2ActiveWrites")));proof.setProperty("activeCrmWriteJobs",Integer.toString(active));proof.setProperty("unresolvedKnownOperations",Integer.toString(unknown));proof.setProperty("timestamp",Instant.now().toString());proof.setProperty("ready",Boolean.toString(active==0&&unknown==0));
  Path next=dir.resolve("drain-evidence-next.properties");try(var out=Files.newOutputStream(next)){proof.store(out,"Own-identity metadata/receipt evidence; no credentials or customer fields");}Files.move(next,dir.resolve("drain-evidence.properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  System.out.println("ADMISSION_HTTP_AND_OWN_COMMIT_DRAIN_READY="+proof.getProperty("ready")+"; no allUsers/project-wide visibility assumed; no DML/DDL submitted");
 }
}
