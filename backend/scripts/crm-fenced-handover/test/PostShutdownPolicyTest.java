import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.kingofcards.crm.ops.fenced.FencedPolicy;

/** Pure policy/file fixtures only. Never starts a service or calls Google/CRM. */
class PostShutdownPolicyTest {
 static int count;
 static final Instant NOW=Instant.parse("2026-11-05T12:00:00Z");
 static Properties evidence(){
  var p=new Properties();
  String[][] entries={{"purpose","POST_SHUTDOWN_RECOVERY_PREPARATION"},{"historicalOperationId",FencedPolicy.OP},{"historicalState","UNKNOWN"},{"originalCertificateSha256",PostShutdownPolicy.CERT},{"originalStopSha256",PostShutdownPolicy.STOP},{"archiveManifestSha256",PostShutdownPolicy.ARCHIVE},{"applicationSha256",FencedPolicy.NEW},{"bootstrapSha256",FencedPolicy.BARRIER},{"afterStartAdmission","READS_ONLY"},{"releaseWrites","false"},{"jobScope","OWN_PRINCIPAL"},{"scan1.active.complete","true"},{"scan2.active.complete","true"},{"nonterminalOwnJobs","0"},{"schemaCompatible","true"},{"existingDataUnchanged","true"},{"newPrecisionFieldsStillNull","true"},{"timestamp",NOW.toString()},{"jobEvidenceUtc",NOW.minusSeconds(1).toString()}};
  for(var e:entries)p.setProperty(e[0],e[1]);return p;
 }
 static Properties local(){var p=new Properties();for(String k:List.of("oldPidAbsent","noCrmJavaProcess","port8082Free","noPreviousStartupAttempt","gatewayCrmUnavailable","eurekaCrmAbsent","directIpv4Closed","directIpv6Closed","gatewayHealthy","identityHealthy","eurekaHealthy","bootstrapArgumentsVerified"))p.setProperty(k,"true");p.setProperty("afterStartAdmission","READS_ONLY");p.setProperty("releaseWrites","false");return p;}
 interface Action{void run()throws Exception;}
 static void check(String label,boolean accepted,Action action)throws Exception{
  boolean passed=true;try{action.run();}catch(IllegalStateException expected){passed=false;}
  if(passed!=accepted)throw new AssertionError(label);count++;System.out.println("PASS "+label);
 }
 public static void main(String[]args)throws Exception{
  check("fresh preparation accepted, not an execution approval",true,()->PostShutdownPolicy.fresh(evidence(),NOW));
  for(var e:List.of(Map.entry("purpose","READ_ONLY_FENCED_HANDOVER"),Map.entry("historicalState","COMMITTED"),Map.entry("originalCertificateSha256","changed"),Map.entry("originalStopSha256","changed"),Map.entry("archiveManifestSha256","changed"),Map.entry("applicationSha256","changed"),Map.entry("bootstrapSha256","changed"),Map.entry("afterStartAdmission","OPEN"),Map.entry("releaseWrites","true"),Map.entry("jobScope","PROJECT_WIDE_ASSUMED"),Map.entry("scan1.active.complete","false"),Map.entry("scan2.active.complete","false"),Map.entry("nonterminalOwnJobs","1"),Map.entry("schemaCompatible","false"),Map.entry("existingDataUnchanged","false"),Map.entry("newPrecisionFieldsStillNull","false"),Map.entry("timestamp",NOW.minusSeconds(121).toString()),Map.entry("jobEvidenceUtc",NOW.minusSeconds(91).toString()),Map.entry("timestamp",NOW.plusSeconds(6).toString()),Map.entry("jobEvidenceUtc",NOW.plusSeconds(1).toString()))){
   var p=evidence();p.setProperty(e.getKey(),e.getValue());check("reject changed/expired evidence: "+e.getKey(),false,()->PostShutdownPolicy.fresh(p,NOW));
  }
  check("closed local admission accepted",true,()->PostShutdownPolicy.local(local()));
  for(String key:local().stringPropertyNames()){
   var p=local();p.setProperty(key,key.equals("afterStartAdmission")?"OPEN":key.equals("releaseWrites")?"true":"false");check("reject unsafe local condition: "+key,false,()->PostShutdownPolicy.local(p));
  }
  Path root=Path.of(args[0]);Files.createDirectories(root);Path pin=root.resolve("pinned-fixture.txt");Files.writeString(pin,"original");String hash=FencedPolicy.hash(pin);
  check("unchanged preserved file",true,()->PostShutdownPolicy.pin(pin,hash));Files.writeString(pin,"changed");check("changed preserved artifact/file",false,()->PostShutdownPolicy.pin(pin,hash));
  Path attempt=root.resolve("exclusive-attempt.properties");Files.writeString(attempt,"one attempt",StandardOpenOption.CREATE_NEW);boolean denied=false;try{Files.writeString(attempt,"second",StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException expected){denied=true;}if(!denied||!Files.readString(attempt).equals("one attempt"))throw new AssertionError("Attempt reset");count++;
  System.out.println("PASS exclusive one-attempt marker cannot be reset");
  System.out.println("POST_SHUTDOWN_POLICY_TESTS_PASS count="+count+"; no service, database or network actions");
 }
}
