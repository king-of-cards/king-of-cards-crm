import java.nio.file.*;import java.time.*;import java.util.*;import com.kingofcards.crm.ops.fenced.*;
/** Files/approval evidence only. Does not stop/start anything or access BigQuery. */
class VerifyFencedStart {
 static void need(boolean b,String s){if(!b)throw new IllegalStateException(s);}
 public static void main(String[]a)throws Exception{
  if(a.length==3&&a[0].equals("archive")){FencedPolicy.archive(Path.of(a[1]),a[2]);System.out.println("UNKNOWN archive verified; no startup");return;}
  if(a.length!=6||!List.of("Updated","Rollback").contains(a[0]))throw new IllegalArgumentException("Role cert certSHA archive manifestSHA guardSHA");
  Path root=Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control").toRealPath(),certPath=Path.of(a[1]).toRealPath(),archive=Path.of(a[3]).toRealPath();need(certPath.startsWith(root)&&archive.startsWith(root),"Private retained state required");FencedPolicy.archive(archive,a[4]);need(FencedPolicy.hash(certPath).equals(a[2]),"Certificate pin differs");var c=FencedPolicy.read(certPath);
  need("READ_ONLY_FENCED_HANDOVER".equals(c.getProperty("purpose"))&&FencedPolicy.OP.equals(c.getProperty("historicalOperationId"))&&"UNKNOWN".equals(c.getProperty("historicalState"))&&a[4].equals(c.getProperty("archiveManifestSha256")),"Read-only UNKNOWN certificate required");
  for(String k:List.of("afterStartAdmission","rollbackAdmission"))need("READS_ONLY".equals(c.getProperty(k)),"Write admission may not open");need("false".equals(c.getProperty("releaseWrites")),"No write release allowed");
  var stop=FencedPolicy.read(root.resolve("fenced-stop-attempt-3160.properties"));need(a[2].equals(stop.getProperty("certificateSha256"))&&a[4].equals(stop.getProperty("archiveManifestSha256"))&&a[5].equals(stop.getProperty("guardSha256")),"Validated stop attempt does not match");
  Instant timestamp=Instant.parse(c.getProperty("timestamp")),now=Instant.now();need(!timestamp.isAfter(now.plusSeconds(5))&&!timestamp.isBefore(now.minusSeconds(a[0].equals("Updated")?300:1800)),"Start/recovery window expired; review required");
  if(a[0].equals("Rollback")){var start=FencedPolicy.read(root.resolve("fenced-Updated-start.properties"));need(FencedPolicy.NEW.equals(start.getProperty("appHash"))&&FencedPolicy.BARRIER.equals(start.getProperty("bootstrapHash"))&&a[4].equals(start.getProperty("archiveManifestSha256")),"Only failed gated updated startup may be recovered");}
  System.out.println("Read-only "+a[0]+" certificate verified; historical UNKNOWN unchanged; no startup executed");
 }
}
