package com.kingofcards.crm.ops.fenced;
import java.lang.instrument.*;import java.nio.file.*;import java.util.*;
/** Separate operator guard. Does not replace/redefine the installed barrier or CRM classes. */
public final class FencedAgent {
 public static final Path CONTROL=Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control");
 static void save(Properties p,Path dest)throws Exception{Path temp=dest.resolveSibling(dest.getFileName()+".next");try(var out=Files.newOutputStream(temp)){p.store(out,"Operator metadata only; no credentials/customer payloads");}Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
 public static void agentmain(String input,Instrumentation instrumentation)throws Exception{
  Path path=Path.of(input).toRealPath();FencedPolicy.require(path.startsWith(CONTROL.toRealPath())&&Files.size(path)<16384,"Private control request required");var request=FencedPolicy.read(path);
  FencedPolicy.require(Long.toString(ProcessHandle.current().pid()).equals(request.getProperty("pid")),"PID differs");Path self=Path.of(FencedAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());FencedPolicy.require(FencedPolicy.hash(self).equals(request.getProperty("guardSha256")),"Guard pin differs");
  var result=LiveInspection.capture(instrumentation);save(result.evidence(),CONTROL.resolve("fenced-inspection.properties"));String action=request.getProperty("action");if("inspect".equals(action))return;
  FencedPolicy.require("stop-read-only-approved".equals(action)&&"APPROVE_READ_ONLY_STOP".equals(request.getProperty("approval")),"Separate read-only stop approval required");
  Path cert=Path.of(request.getProperty("certificate")).toRealPath(),archive=Path.of(request.getProperty("archive")).toRealPath();FencedPolicy.require(cert.startsWith(CONTROL.toRealPath())&&archive.startsWith(CONTROL.toRealPath()),"Private certificate/archive required");FencedPolicy.require(FencedPolicy.hash(cert).equals(request.getProperty("certificateSha256")),"Certificate pin differs");
  FencedPolicy.verify(FencedPolicy.read(cert),result.evidence(),archive,request.getProperty("archiveManifestSha256"),java.time.Instant.now());
  // Exactly one separately approved attempt. A rejected/failed attempt is never silently reset.
  Path marker=CONTROL.resolve("fenced-stop-attempt-3160.properties");try(var out=Files.newOutputStream(marker,StandardOpenOption.CREATE_NEW)){request.store(out,"Approved read-only stop; historical UNKNOWN retained in pinned archive");}
  result.context().getClass().getMethod("close").invoke(result.context());System.exit(0);
 }
}
