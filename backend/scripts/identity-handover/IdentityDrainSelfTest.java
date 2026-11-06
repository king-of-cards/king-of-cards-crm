import java.io.*;import java.net.*;import java.net.http.*;import java.nio.file.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import jakarta.servlet.http.*;import org.apache.catalina.Context;import org.apache.catalina.startup.Tomcat;
import com.kingofcards.identity.ops.*;
/** Offline, ephemeral HTTP fixture: no CRM/Identity app, accounts, credentials or database. */
class IdentityDrainSelfTest {
 static int passed;static void check(boolean value,String message){if(!value)throw new AssertionError(message);passed++;System.out.println("PASS "+message);}
 static void denied(IdentityDrainPolicy.Evidence evidence,Instant now){try{IdentityDrainPolicy.requireFreshClosed(evidence,now);throw new AssertionError("Unsafe evidence accepted");}catch(IllegalStateException expected){passed++;}}
 static IdentityDrainPolicy.Evidence valid(Instant now){return new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,true,true,0,0,true,true,true,true);}
 public static void main(String[]args)throws Exception{
  Path directory=Path.of(args[0]);Files.createDirectories(directory);var gate=new IdentityAdmissionValve();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var writes=new AtomicInteger();var interrupted=new AtomicBoolean();
  var tomcat=new Tomcat();tomcat.setBaseDir(Files.createTempDirectory(directory,"unit-tomcat-").toString());tomcat.setPort(0);Context context=tomcat.addContext("",Files.createTempDirectory(directory,"unit-context-").toString());context.getPipeline().addValve(gate);
  Tomcat.addServlet(context,"memory",new HttpServlet(){protected void doGet(HttpServletRequest q,HttpServletResponse r)throws IOException{r.getWriter().write("memory-only");}protected void doPost(HttpServletRequest q,HttpServletResponse r)throws IOException{writes.incrementAndGet();entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException x){interrupted.set(true);Thread.currentThread().interrupt();}r.getWriter().write("memory-only completed");}});context.addServletMappingDecoded("/*","memory");
  try{
   tomcat.start();String root="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
   for(String path:List.of("/auth/login","/auth/refresh","/auth/logout","/api/identity/auth/login","/api/identity/auth/refresh","/api/identity/auth/logout")){
    var response=client.send(HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    check(response.statusCode()==503&&response.headers().firstValue("X-KOC-Identity-Admission").orElse("").equals("closed"),"auth path denied before handler: "+path);
   }
   check(writes.get()==0,"no rejected auth request reaches the handler");
   check(client.send(HttpRequest.newBuilder(URI.create(root+"/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200,"read checks work while authentication is paused");
   try{gate.allowSessionTraffic("unapproved");throw new AssertionError("Unapproved release accepted");}catch(IllegalStateException expected){passed++;}
   gate.allowSessionTraffic("EXPLICIT_APPROVED_SESSION_RELEASE");
   var inFlight=client.sendAsync(HttpRequest.newBuilder(URI.create(root+"/auth/refresh")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());check(entered.await(3,TimeUnit.SECONDS),"in-flight simulated refresh entered before closure");
   gate.closeAll();check(gate.snapshot().active()==1&&gate.snapshot().mutations()==1&&!inFlight.isDone(),"closing admission does not interrupt an admitted request");
   var denied=client.send(HttpRequest.newBuilder(URI.create(root+"/auth/logout")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());check(denied.statusCode()==503&&writes.get()==1,"new logout is blocked during drain");
   release.countDown();check(inFlight.get(3,TimeUnit.SECONDS).statusCode()==200&&!interrupted.get(),"admitted refresh completes normally");
   check(gate.snapshot().active()==0&&gate.snapshot().mutations()==0,"request and mutation counters settle to zero");
   check(client.send(HttpRequest.newBuilder(URI.create(root+"/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==503,"all-admission closure also blocks new reads");
   gate.pauseSessions();gate.allowSessionTraffic("EXPLICIT_APPROVED_SESSION_RELEASE");
   check(client.send(HttpRequest.newBuilder(URI.create(root+"/admin/vendors")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==503,"session-only release never opens account mutations");
   Instant now=Instant.now();IdentityDrainPolicy.requireFreshClosed(valid(now),now);passed++;
   denied(valid(now.minusSeconds(31)),now);
   denied(new IdentityDrainPolicy.Evidence(now,false,true,0,0,0,true,true,true,true,0,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,false,0,0,0,true,true,true,true,0,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,1,1,0,true,true,true,true,0,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,1,true,true,true,true,0,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,false,true,true,true,0,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,false,true,true,0,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,false,true,0,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,true,false,0,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,true,true,1,0,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,true,true,0,1,true,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,true,true,0,0,false,true,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,true,true,0,0,true,false,true,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,true,true,0,0,true,true,false,true),now);
   denied(new IdentityDrainPolicy.Evidence(now,true,true,0,0,0,true,true,true,true,0,0,true,true,true,false),now);
   System.out.println("IDENTITY_DRAIN_MODEL_PASS checks="+passed+"; offline only, no live gate/certificate/service change");
  }finally{release.countDown();tomcat.stop();tomcat.destroy();}
 }
}
