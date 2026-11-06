import java.nio.file.*;import java.time.*;import java.util.*;import com.google.auth.oauth2.*;
/** Aggregate-only integrity and existing Admin subject lookup; no customer/auth secrets selected. */
class CrmReadOnlyIntegrity {
 public static void main(String[]args)throws Exception{
  var status=CommitReceiptInvestigation.load(Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control/status.properties"));
  var credentials=GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/bigquery");
  if(!(credentials instanceof ServiceAccountCredentials c)||!CommitReceiptInvestigation.sha(c.getClientEmail()).equals(status.getProperty("principalSha256"))||!"true".equals(status.getProperty("writesBlocked")))throw new IllegalStateException("Actual credential identity/write barrier mismatch");
  CommitReceiptInvestigation.credentials=credentials;
  String p="`king-of-cards-509610.shared_rds_data.";
  String sql="SELECT (SELECT COUNT(*) FROM "+p+"crm_koc_to_vendor_details` WHERE sheet_date IS NOT NULL OR sheet_time_supplied IS NOT NULL OR sheet_source_value IS NOT NULL) AS populated_precision_fields,"
   +"(SELECT COUNT(*) FROM "+p+"crm_leads` m LEFT JOIN "+p+"crm_koc_to_vendor_details` d USING(lead_id) WHERE m.lead_direction='KOC_TO_VENDOR' AND d.lead_id IS NULL) AS koc_missing_details,"
   +"(SELECT COUNT(*) FROM "+p+"crm_koc_to_vendor_details` d LEFT JOIN "+p+"crm_leads` m USING(lead_id) WHERE m.lead_id IS NULL OR m.lead_direction IS DISTINCT FROM 'KOC_TO_VENDOR') AS koc_orphan_details,"
   +"(SELECT COUNT(*) FROM "+p+"crm_leads` m JOIN "+p+"crm_koc_to_vendor_details` d USING(lead_id) WHERE m.vendor_name IS DISTINCT FROM d.vendor_name) AS koc_name_mismatches,"
   +"(SELECT COUNT(*) FROM "+p+"crm_leads` m LEFT JOIN "+p+"crm_vendor_to_koc_details` d USING(lead_id) WHERE m.lead_direction='VENDOR_TO_KOC' AND d.lead_id IS NULL) AS vendor_missing_details,"
   +"(SELECT COUNT(*) FROM "+p+"crm_vendor_to_koc_details` d LEFT JOIN "+p+"crm_leads` m USING(lead_id) WHERE m.lead_id IS NULL OR m.lead_direction IS DISTINCT FROM 'VENDOR_TO_KOC') AS vendor_orphan_details,"
   +"(SELECT COUNT(*) FROM "+p+"crm_leads` m JOIN "+p+"crm_vendor_to_koc_details` d USING(lead_id) WHERE m.vendor_name IS DISTINCT FROM d.vendor_name) AS vendor_name_mismatches";
  for(var row:CommitReceiptInvestigation.query(sql))for(var entry:row.entrySet()){System.out.println(entry.getKey()+"="+entry.getValue());if(!"0".equals(entry.getValue()))throw new IllegalStateException("Integrity differs; stop without modifying records");}
  for(var row:CommitReceiptInvestigation.query("SELECT import_batch_id,COUNT(*) AS row_count FROM "+p+"crm_leads` WHERE import_batch_id IS NOT NULL GROUP BY import_batch_id"))System.out.println("EXISTING_BATCH="+row.get("import_batch_id")+" rows="+row.get("row_count"));
  // IDs only: never select names/emails/password hashes or session/token data.
  var admins=CommitReceiptInvestigation.query("SELECT id FROM "+p+"identity_user` WHERE role='CRM_ADMIN'");
  if(admins.size()>30)throw new IllegalStateException("Admin candidate count requires narrower review");int matched=0;
  String op="d2d869c8-f0f0-4662-8720-126134c52efe";
  for(var row:admins){String actor=UUID.fromString(row.get("id")).toString();String id="koc_direction_v1_"+actor.replace("-","")+"_"+op.replace("-","");try{var job=CommitReceiptInvestigation.request("GET","/jobs/"+CommitReceiptInvestigation.enc(id)+"?location=asia-south1",null);matched++;System.out.println("LEGACY_JOB_FOUND="+id+" state="+job.getAsJsonObject("status").get("state").getAsString());}catch(IllegalStateException missing){if(!missing.getMessage().startsWith("Read-only request HTTP=404 "))throw missing;}}
  System.out.println("EXISTING_ADMIN_SUBJECT_CHECK candidates="+admins.size()+" legacyJobsLocated="+matched+"; absence does not prove failed/no write");
 }
}
