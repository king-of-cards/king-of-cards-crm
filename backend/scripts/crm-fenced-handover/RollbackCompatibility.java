import java.nio.file.*;import java.util.*;import com.google.auth.oauth2.*;import com.google.gson.*;import com.kingofcards.crm.ops.fenced.*;
/** Fresh read-only rollback schema/data check. No CRM starts and no SQL other than SELECT. */
class RollbackCompatibility {
 static String type(String s){return switch(s){case "INTEGER"->"INT64";case "BOOLEAN"->"BOOL";case "FLOAT"->"FLOAT64";default->s;};}
 static void need(boolean b,String s){if(!b)throw new IllegalStateException(s);}
 public static void main(String[]a)throws Exception{
  if(a.length!=2)throw new IllegalArgumentException("archive manifestSHA");Path root=Path.of(a[0]).toRealPath();FencedPolicy.archive(root,a[1]);var baseline=FencedPolicy.read(root.resolve("baseline.properties"));var runtime=FencedPolicy.read(root.resolve("runtime-inspection.properties"));
  var credentials=GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/bigquery");need(credentials instanceof ServiceAccountCredentials,"Expected service credential");need(CommitReceiptInvestigation.sha(((ServiceAccountCredentials)credentials).getClientEmail()).equals(runtime.getProperty("principalSha256")),"Credential differs");CommitReceiptInvestigation.credentials=credentials;
  need("asia-south1".equalsIgnoreCase(CommitReceiptInvestigation.request("GET","/datasets/shared_rds_data",null).get("location").getAsString()),"Dataset location differs");
  for(String table:List.of("crm_leads","crm_koc_to_vendor_details","crm_vendor_to_koc_details")){
   var actual=new HashMap<String,String>();for(var field:CommitReceiptInvestigation.request("GET","/datasets/shared_rds_data/tables/"+table,null).getAsJsonObject("schema").getAsJsonArray("fields")){var f=field.getAsJsonObject();actual.put(f.get("name").getAsString(),type(f.get("type").getAsString())+":"+(f.has("mode")?f.get("mode").getAsString():"NULLABLE"));}
   var old=new HashSet<String>();for(String definition:baseline.getProperty("schema."+table).split(",")){String[] parts=definition.split(":");old.add(parts[0]);need((parts[1]+":"+parts[2]).equals(actual.get(parts[0])),"Old required column changed: "+table+"."+parts[0]);}
   if(table.equals("crm_koc_to_vendor_details")){for(var field:Map.of("sheet_date","DATE:NULLABLE","sheet_time_supplied","BOOL:NULLABLE","sheet_source_value","STRING:NULLABLE").entrySet()){need(field.getValue().equals(actual.get(field.getKey())),"Nullable source column differs");old.add(field.getKey());}}
   need(actual.keySet().equals(old),"Unreviewed schema difference");
  }
  need(FencedPolicy.hash(root.resolve("baseline.properties")).equals(FencedPolicy.hash(Path.of("C:/kocbackend/king-of-cards-crm/backend/crm-service/target-source-precision-verify/source-date-migration-20261105/before.properties"))),"Retained baseline differs");CommitReceiptInvestigation.integrity(new Properties());need("0".equals(CommitReceiptInvestigation.query("SELECT COUNTIF(sheet_date IS NOT NULL OR sheet_time_supplied IS NOT NULL OR sheet_source_value IS NOT NULL) AS populated FROM `king-of-cards-509610.shared_rds_data.crm_koc_to_vendor_details`").getFirst().get("populated")),"New precision data requires separate rollback review");
  System.out.println("ROLLBACK_COMPATIBILITY_PASS: old fields/types/modes unchanged, only reviewed nullable additions, full existing data unchanged, no precision data written; no startup or DML/DDL");
 }
}
