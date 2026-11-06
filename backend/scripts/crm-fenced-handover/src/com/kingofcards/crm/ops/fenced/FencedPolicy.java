package com.kingofcards.crm.ops.fenced;
import java.nio.file.*;import java.io.*;import java.security.*;import java.time.*;import java.util.*;
/** Read-only handover policy. Historical UNKNOWN is retained, never converted to success/failure. */
public final class FencedPolicy {
 public static final String OP="d2d869c8-f0f0-4662-8720-126134c52efe";
 public static final String OLD="A7CB8433FF70F0D218819FB03D817FE8F9AC24E502050DB3B73D8E84A9281F97",NEW="405926DC620E64C72A56170526D365783A380CC33B963DE4581D6F2ABE8E8EB5",BARRIER="C7576BB7E685E1F8C7265F46BDE398D5D747EB1B53B7D960785B9CD0DD0D82D9";
 public static Properties read(Path path)throws IOException{var p=new Properties();try(var in=Files.newInputStream(path)){p.load(in);}return p;}
 public static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))).toUpperCase(Locale.ROOT);}
 static void require(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
 static void eq(Properties p,String key,String expected){require(expected.equals(p.getProperty(key)),"Required evidence differs: "+key);}
 public static void archive(Path root,String manifestHash)throws Exception{
  root=root.toRealPath();Path manifest=root.resolve("manifest.properties");require(hash(manifest).equals(manifestHash),"Archive manifest pin differs");var m=read(manifest);int count=Integer.parseInt(m.getProperty("fileCount","0"));require(count>=3&&count<=30,"Archive file count invalid");
  for(int i=0;i<count;i++){Path file=root.resolve(m.getProperty("file."+i+".path")).normalize();require(file.startsWith(root)&&file.toRealPath().startsWith(root),"Archive path escaped");require(hash(file).equals(m.getProperty("file."+i+".sha256")),"Preserved evidence changed");}
  var u=read(root.resolve("historical-unknown.properties"));eq(u,"operationId",OP);eq(u,"state","UNKNOWN");eq(u,"neverReplay","true");eq(u,"neverDiscard","true");eq(u,"originalBindingRecovered","false");
 }
 public static void verify(Properties cert,Properties live,Path root,String manifestHash,Instant now)throws Exception{
  archive(root,manifestHash);eq(cert,"purpose","READ_ONLY_FENCED_HANDOVER");eq(cert,"historicalOperationId",OP);eq(cert,"historicalState","UNKNOWN");eq(cert,"historicalUnknownCount","1");eq(cert,"archiveManifestSha256",manifestHash);
  eq(cert,"afterStartAdmission","READS_ONLY");eq(cert,"rollbackAdmission","READS_ONLY");eq(cert,"releaseWrites","false");eq(cert,"newApplicationSha256",NEW);eq(cert,"rollbackApplicationSha256",OLD);
  eq(live,"pid","3160");eq(live,"applicationSha256",OLD);eq(live,"agentSha256",BARRIER);eq(live,"project","king-of-cards-509610");eq(live,"location","asia-south1");
  for(String key:List.of("pid","processStartUtc","applicationSha256","agentSha256","principalSha256"))require(Objects.equals(cert.getProperty(key),live.getProperty(key))&&live.getProperty(key)!=null,"Current process binding differs: "+key);
  Instant generated=Instant.parse(cert.getProperty("timestamp"));require(!generated.isBefore(now.minusSeconds(120))&&!generated.isAfter(now.plusSeconds(5)),"Certificate expired/future dated");
  Instant observed=Instant.parse(live.getProperty("observedAtUtc"));require(!observed.isBefore(now.minusSeconds(15))&&!observed.isAfter(now.plusSeconds(5)),"Runtime measurement stale");
  var u=read(root.resolve("historical-unknown.properties"));require(Instant.parse(u.getProperty("firstReportedUtc")).isBefore(Instant.parse(live.getProperty("processStartUtc"))),"Unknown operation is not proven historical to this process");
  var plan=read(root.resolve("handover-plan.properties"));require(Objects.equals(plan.getProperty("processStartUtc"),live.getProperty("processStartUtc")),"Reviewed process start differs (PID reuse)");eq(plan,"pid","3160");eq(plan,"newApplicationSha256",NEW);eq(plan,"rollbackApplicationSha256",OLD);
  for(String key:List.of("paused","allClosed","gateInstalled","writesBlocked"))eq(live,key,"true");
  for(String key:List.of("httpActive","queued","processing","gateActive","mutationStackCount","uniqueStateRisks","httpSessions"))eq(live,key,"0");
  eq(live,"directionalRepository","com.kingofcards.crm.directional.BigQueryDirectionalRepository");eq(live,"localRepositoryPresent","false");eq(live,"operationKeyInAppMaps","false");eq(live,"profile","dev");
  for(String key:List.of("crm.bigquery.enabled","crm.directional.enabled","crm.bigquery.writes-enabled","crm.directional.writes-enabled"))eq(live,key,"true");for(String key:List.of("crm.bigquery.schema-auto-create","crm.bigquery.schema-init-enabled"))eq(live,key,"false");
  eq(cert,"jobScope","OWN_PRINCIPAL");eq(cert,"jobScan1Complete","true");eq(cert,"jobScan2Complete","true");eq(cert,"nonterminalOwnJobs","0");eq(cert,"existingDataUnchanged","true");eq(cert,"newPrecisionFieldsStillNull","true");
  eq(cert,"drainStableSamples","10");eq(cert,"drainPhase","ALL_ADMISSION_CLOSED_DRAINED");
  Instant jobs=Instant.parse(cert.getProperty("jobEvidenceUtc"));require(!jobs.isBefore(generated.minusSeconds(90))&&!jobs.isAfter(generated),"Job evidence stale/future");
 }
}
