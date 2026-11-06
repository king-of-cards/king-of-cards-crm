import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.kingofcards.crm.ops.fenced.FencedPolicy;

/** Historical stop validity and fresh recovery authority are deliberately separate. */
public final class PostShutdownPolicy {
 static final String CERT="FC7545BCE2203F9B0C480378B329A74BDD67058CB653CE841741E26AAC34E654";
 static final String STOP="99CDD81836B91988DF274F9352A1BECEBC9A33AA56954A2EEC38B4DA1D54D644";
 static final String INSPECTION="5BA61AC1FCC9956FA5226359A4A0A344C257E8E970D69852F79666FB5A2188DF";
 static final String LOG="8B5EA6CB745E68DE7992076D9641AD73BB15A9EEA8FDEFBBEF5D35383B2E16F4";
 static final String ARCHIVE="9BBE58931F5D9769DAEAF6DB7F856E92B5A3BE96AF781C3A7C55748392354E87";
 static final String GUARD="34FCCE45CA67531982D57042D8B93865EDCDBB6641B122B96D2F4043A9C19ECC";
 static final Path BACKEND=Path.of("C:/kocbackend/king-of-cards-crm/backend");
 static final Path CONTROL=BACKEND.resolve("local-dev/crm-drain-control");
 static final Path ARCHIVE_PATH=CONTROL.resolve("fenced-handover-20261105");
 static void need(boolean b,String message){if(!b)throw new IllegalStateException(message);}
 static void eq(Properties p,String key,String value){need(value.equals(p.getProperty(key)),"Recovery evidence differs: "+key);}
 static void pin(Path p,String hash)throws Exception{need(hash.equals(FencedPolicy.hash(p)),"Preserved file pin differs: "+p.getFileName());}
 static void historical()throws Exception{
  Path cert=CONTROL.resolve("fenced-certify-20261105-105023-3557478.properties"),stop=CONTROL.resolve("fenced-stop-attempt-3160.properties"),inspection=CONTROL.resolve("fenced-inspection.properties");
  pin(cert,CERT);pin(stop,STOP);pin(inspection,INSPECTION);
  Path log=BACKEND.resolve("local-dev/logs/crm-20261104-105850-553/stdout.log");pin(log,LOG);
  var c=FencedPolicy.read(cert);var s=FencedPolicy.read(stop);var live=FencedPolicy.read(inspection);
  eq(s,"certificateSha256",CERT);eq(s,"archiveManifestSha256",ARCHIVE);eq(s,"guardSha256",GUARD);eq(s,"pid","3160");eq(s,"action","stop-read-only-approved");eq(s,"approval","APPROVE_READ_ONLY_STOP");
  need(Path.of(s.getProperty("certificate")).toRealPath().equals(cert.toRealPath())&&Path.of(s.getProperty("archive")).toRealPath().equals(ARCHIVE_PATH.toRealPath()),"Original stop references differ");
  // Validate the original certificate AT its pinned final pre-stop observation. This
  // is historical evidence, never a new/current stop certificate or startup permit.
  FencedPolicy.verify(c,live,ARCHIVE_PATH,ARCHIVE,Instant.parse(live.getProperty("observedAtUtc")));
  String text=Files.readString(log);
  need(text.contains("2026-11-05T16:20:38.163+05:30  INFO 3160")&&text.contains("Graceful shutdown complete")&&text.contains("Completed shut down of DiscoveryClient"),"Completed graceful shutdown evidence missing");
  pin(BACKEND.resolve("crm-service/target-source-precision-verify/crm-service-0.1.0-SNAPSHOT.jar"),FencedPolicy.NEW);
  pin(BACKEND.resolve("crm-service/target-timestamp-safe-verify/crm-service-0.1.0-SNAPSHOT.jar"),FencedPolicy.OLD);
  pin(BACKEND.resolve("crm-service/target-drain-control-verify/v2/crm-drain-control.jar"),FencedPolicy.BARRIER);
 }
 static void fresh(Properties p,Instant now){
  for(var e:Map.of("purpose","POST_SHUTDOWN_RECOVERY_PREPARATION","historicalOperationId",FencedPolicy.OP,"historicalState","UNKNOWN","originalCertificateSha256",CERT,"originalStopSha256",STOP,"archiveManifestSha256",ARCHIVE,"applicationSha256",FencedPolicy.NEW,"bootstrapSha256",FencedPolicy.BARRIER,"afterStartAdmission","READS_ONLY","releaseWrites","false").entrySet())eq(p,e.getKey(),e.getValue());
  eq(p,"jobScope","OWN_PRINCIPAL");eq(p,"scan1.active.complete","true");eq(p,"scan2.active.complete","true");eq(p,"nonterminalOwnJobs","0");eq(p,"schemaCompatible","true");eq(p,"existingDataUnchanged","true");eq(p,"newPrecisionFieldsStillNull","true");
  Instant generated=Instant.parse(p.getProperty("timestamp")),jobs=Instant.parse(p.getProperty("jobEvidenceUtc"));
  need(!generated.isBefore(now.minusSeconds(120))&&!generated.isAfter(now.plusSeconds(5)),"Recovery preparation expired/future dated");
  need(!jobs.isBefore(generated.minusSeconds(90))&&!jobs.isAfter(generated),"Recovery job evidence stale/future dated");
 }
 static void local(Properties p){
  for(String k:List.of("oldPidAbsent","noCrmJavaProcess","port8082Free","noPreviousStartupAttempt","gatewayCrmUnavailable","eurekaCrmAbsent","directIpv4Closed","directIpv6Closed","gatewayHealthy","identityHealthy","eurekaHealthy","bootstrapArgumentsVerified"))eq(p,k,"true");
  eq(p,"afterStartAdmission","READS_ONLY");eq(p,"releaseWrites","false");
 }
 public static void main(String[]a)throws Exception{
  if(a.length==1&&a[0].equals("historical")){historical();System.out.println("HISTORICAL_STOP_VERIFIED; expired certificate retained unchanged; no startup authorization");return;}
  need(a.length==3&&a[0].equals("check"),"check freshEvidence localObservations OR historical");
  historical();Path evidence=Path.of(a[1]).toRealPath(),local=Path.of(a[2]).toRealPath();need(evidence.startsWith(CONTROL.toRealPath())&&local.startsWith(CONTROL.toRealPath()),"Private recovery observations required");
  fresh(FencedPolicy.read(evidence),Instant.now());var l=FencedPolicy.read(local);local(l);Instant at=Instant.parse(l.getProperty("timestamp"));need(!at.isBefore(Instant.now().minusSeconds(15))&&!at.isAfter(Instant.now().plusSeconds(5)),"Local observations stale");
  eq(l,"evidenceSha256",FencedPolicy.hash(evidence));need(!ProcessHandle.of(3160).map(ProcessHandle::isAlive).orElse(false),"Old PID exists");
  System.out.println("POST_SHUTDOWN_CHECK_PASS; no start/stop/replay; UNKNOWN preserved; READS_ONLY mandatory");
 }
}
