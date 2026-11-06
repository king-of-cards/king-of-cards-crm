import java.nio.file.*;import java.util.*;import com.google.auth.oauth2.*;
/** Exact original job reference only; alternate locations can reveal old failed region setup. */
class LegacyJobRegionCheck {
 public static void main(String[]args)throws Exception{
  var status=CommitReceiptInvestigation.load(Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control/status.properties"));
  var credentials=GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/bigquery");
  if(!(credentials instanceof ServiceAccountCredentials c)||!CommitReceiptInvestigation.sha(c.getClientEmail()).equals(status.getProperty("principalSha256")))throw new IllegalStateException("Existing CRM credential identity required");
  CommitReceiptInvestigation.credentials=credentials;
  var admins=CommitReceiptInvestigation.query("SELECT id FROM `king-of-cards-509610.shared_rds_data.identity_user` WHERE role='CRM_ADMIN'");
  for(var admin:admins){String actor=UUID.fromString(admin.get("id")).toString().replace("-","");String job="koc_direction_v1_"+actor+"_d2d869c8f0f046628720126134c52efe";
   for(String region:List.of("US","EU","")){try{var result=CommitReceiptInvestigation.request("GET","/jobs/"+job+(region.isEmpty()?"":"?location="+region),null);System.out.println("LEGACY_LOOKUP region="+(region.isEmpty()?"default":region)+" state="+result.getAsJsonObject("status").get("state").getAsString()+" error="+result.getAsJsonObject("status").has("errorResult"));}catch(IllegalStateException missing){if(!missing.getMessage().startsWith("Read-only request HTTP=404 "))throw missing;System.out.println("LEGACY_LOOKUP region="+(region.isEmpty()?"default":region)+" result=NOT_FOUND; not evidence of failed/no write");}}
  }
 }
}
