import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.kingofcards.crm.ops.fenced.FencedPolicy;

/** Only archived metadata/SELECT helpers and active own-job pagination are callable. */
public final class PostShutdownProbe {
 public static void main(String[]args)throws Exception{
  PostShutdownPolicy.need(args.length==1,"New private evidence output required");
  Path out=Path.of(args[0]).toAbsolutePath().normalize();PostShutdownPolicy.need(out.getParent().toRealPath().equals(PostShutdownPolicy.CONTROL.toRealPath())&&!Files.exists(out),"New private evidence file required");
  PostShutdownPolicy.historical();
  RollbackCompatibility.main(new String[]{PostShutdownPolicy.ARCHIVE_PATH.toString(),PostShutdownPolicy.ARCHIVE});
  var p=new Properties();CommitReceiptInvestigation.list(true,p,1);Thread.sleep(2000);
  CommitReceiptInvestigation.integrity(p);CommitReceiptInvestigation.list(true,p,2);
  PostShutdownPolicy.need("0".equals(CommitReceiptInvestigation.query("SELECT COUNTIF(sheet_date IS NOT NULL OR sheet_time_supplied IS NOT NULL OR sheet_source_value IS NOT NULL) AS populated FROM `king-of-cards-509610.shared_rds_data.crm_koc_to_vendor_details`").getFirst().get("populated")),"Precision data changed during verification");
  long pending=p.stringPropertyNames().stream().filter(k->k.startsWith("nonterminal.")).count();
  PostShutdownPolicy.need(pending==0,"Nonterminal own-principal jobs remain; no recovery");
  PostShutdownPolicy.historical();
  p.setProperty("purpose","POST_SHUTDOWN_RECOVERY_PREPARATION");p.setProperty("historicalOperationId",FencedPolicy.OP);p.setProperty("historicalState","UNKNOWN");p.setProperty("originalCertificateSha256",PostShutdownPolicy.CERT);p.setProperty("originalStopSha256",PostShutdownPolicy.STOP);p.setProperty("archiveManifestSha256",PostShutdownPolicy.ARCHIVE);p.setProperty("applicationSha256",FencedPolicy.NEW);p.setProperty("bootstrapSha256",FencedPolicy.BARRIER);p.setProperty("afterStartAdmission","READS_ONLY");p.setProperty("releaseWrites","false");p.setProperty("schemaCompatible","true");p.setProperty("existingDataUnchanged","true");p.setProperty("newPrecisionFieldsStillNull","true");p.setProperty("jobScope","OWN_PRINCIPAL");p.setProperty("nonterminalOwnJobs","0");p.setProperty("jobEvidenceUtc",Instant.now().toString());p.setProperty("timestamp",Instant.now().toString());
  PostShutdownPolicy.fresh(p,Instant.now());
  try(var stream=Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)){p.store(stream,"Fresh read-only recovery preparation; NOT approval to start; original certificate is unchanged");}
  System.out.println("POST_SHUTDOWN_READONLY_PROBE_PASS; own-principal active jobs=0; complete pagination twice; historical UNKNOWN unchanged");
 }
}
