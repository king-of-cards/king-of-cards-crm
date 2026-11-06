import java.nio.file.*;import java.util.*;import com.google.auth.oauth2.*;
/** Exact d2d... investigation only. No historical job listing, retry, mutation or other import lookup. */
class SingleUnresolvedOperationReadOnly {
 static final String OP="d2d869c8-f0f0-4662-8720-126134c52efe";
 static final String CANDIDATE="93ce0bda-9f19-804a-8b9e-47ef1e2b62c6";
 public static void main(String[]args)throws Exception{
  if(args.length!=1)throw new IllegalArgumentException("New local evidence file required");
  Path out=Path.of(args[0]).toAbsolutePath().normalize();if(!out.startsWith(Path.of("C:/kocbackend/king-of-cards-crm/backend/crm-service/target-drain-control-verify"))||Files.exists(out))throw new IllegalArgumentException("Preserved isolated evidence only");
  var status=CommitReceiptInvestigation.load(Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control/status.properties"));
  if(!"3160".equals(status.getProperty("pid"))||!"true".equals(status.getProperty("gateInstalled"))||!"true".equals(status.getProperty("writesBlocked")))throw new IllegalStateException("Expected live CRM write barrier required");
  var credentials=GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/bigquery");if(!(credentials instanceof ServiceAccountCredentials c)||!CommitReceiptInvestigation.sha(c.getClientEmail()).equals(status.getProperty("principalSha256")))throw new IllegalStateException("Existing CRM credential identity mismatch");CommitReceiptInvestigation.credentials=credentials;
  var evidence=new Properties();evidence.setProperty("operationId",OP);evidence.setProperty("outcome","UNRESOLVED");evidence.setProperty("originalActorVerified","false");evidence.setProperty("originalVendorVerified","false");evidence.setProperty("originalRequestTimeVerified","false");
  evidence.setProperty("firstSavedUserReportUtc","2026-11-03T01:32:14.860Z");evidence.setProperty("priorReadOnlyNotFoundUtc","2026-11-03T05:37:45.339Z");
  try{
   String master="`king-of-cards-509610.shared_rds_data.crm_leads`",detail="`king-of-cards-509610.shared_rds_data.crm_koc_to_vendor_details`";
   var candidates=CommitReceiptInvestigation.query("SELECT lead_id,vendor_subject_id,lead_direction,created_by_subject_id,updated_by_subject_id,CAST(created_at AS STRING) AS created_at,CAST(updated_at AS STRING) AS updated_at,import_batch_id,revision FROM "+master+" WHERE lead_id='"+CANDIDATE+"' LIMIT 2");
   evidence.setProperty("candidateRows",Integer.toString(candidates.size()));
   if(candidates.size()==1){var candidate=candidates.getFirst();var actors=new LinkedHashSet<String>();for(String field:List.of("created_by_subject_id","updated_by_subject_id")){String actor=candidate.get(field);if(actor!=null)actors.add(UUID.fromString(actor).toString());}
    for(var entry:candidate.entrySet())if(entry.getValue()!=null)evidence.setProperty("candidate."+entry.getKey(),entry.getValue());evidence.setProperty("candidate.import_batch_id",Objects.toString(candidate.get("import_batch_id"),"NULL"));
    System.out.println("CANDIDATE_RECORD="+CANDIDATE+" vendor="+candidate.get("vendor_subject_id")+" direction="+candidate.get("lead_direction")+" createdUtc="+candidate.get("created_at")+" importBatch="+candidate.get("import_batch_id")+"; original-operation association NOT verified");
    var matches=CommitReceiptInvestigation.query("SELECT COUNT(*) AS matching_master,COUNTIF(d.lead_id IS NOT NULL) AS matching_details,COUNTIF(m.vendor_name IS DISTINCT FROM d.vendor_name) AS name_mismatches FROM "+master+" m LEFT JOIN "+detail+" d USING(lead_id) WHERE m.lead_id='"+CANDIDATE+"'").getFirst();for(var entry:matches.entrySet())evidence.setProperty("candidate."+entry.getKey(),entry.getValue());System.out.println("CANDIDATE_INTEGRITY="+matches);
    for(String actor:actors){String id="koc_direction_v1_"+actor.replace("-","")+"_"+OP.replace("-","");evidence.setProperty("candidateJob."+actor,id);
     try{var job=CommitReceiptInvestigation.request("GET","/jobs/"+id+"?location=asia-south1",null);evidence.setProperty("candidateJob."+actor+".state",job.getAsJsonObject("status").get("state").getAsString());evidence.setProperty("outcome","JOB_LOCATED_REQUIRES_ORIGINAL_BINDING_REVIEW");System.out.println("EXACT_JOB_LOCATED="+id+"; no job execution or resubmission");}
     catch(IllegalStateException missing){if(!missing.getMessage().startsWith("Read-only request HTTP=404 "))throw missing;evidence.setProperty("candidateJob."+actor+".state","NOT_FOUND");System.out.println("EXACT_CANDIDATE_JOB="+id+" result=NOT_FOUND; no conclusion about original save");}
    }
   }
   CommitReceiptInvestigation.integrity(evidence);
   CommitReceiptInvestigation.list(true,evidence,1); // PENDING/RUNNING own jobs only, never history.
   System.out.println("FINAL_OPERATION_OUTCOME="+evidence.getProperty("outcome")+"; no original actor/request binding recovered; no retries or historical scan");
  }finally{evidence.setProperty("verifiedAtUtc",java.time.Instant.now().toString());try(var stream=Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)){evidence.store(stream,"Exact unresolved operation audit; metadata/aggregates only; no credentials/customer fields");}}
 }
}
