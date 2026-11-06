import java.io.*;import java.net.*;import java.net.http.*;import java.time.*;import java.nio.file.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import jakarta.servlet.http.*;import org.apache.catalina.*;import org.apache.catalina.startup.Tomcat;import org.apache.coyote.AbstractProtocol;
import com.kingofcards.crm.ops.runtime.*;
/** Ephemeral in-memory HTTP fixture only: no CRM, credentials, database or Eureka. */
class DrainSelfTest {
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);System.out.println("PASS "+label);}
 public static void main(String[]args)throws Exception{
  AtomicInteger writes=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();
  var tomcat=new Tomcat();tomcat.setBaseDir(Files.createTempDirectory(Path.of(args[0]),"unit-tomcat-").toString());tomcat.setPort(0);
  Context context=tomcat.addContext("",Files.createTempDirectory(Path.of(args[0]),"unit-context-").toString());
  Tomcat.addServlet(context,"memory",new HttpServlet(){
   protected void doGet(HttpServletRequest req,HttpServletResponse resp)throws IOException{resp.getWriter().write("read-only");}
   protected void doPost(HttpServletRequest req,HttpServletResponse resp)throws IOException{
    writes.incrementAndGet();entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted.set(true);Thread.currentThread().interrupt();}resp.getWriter().write("memory-only completed");
   }
  });context.addServletMappingDecoded("/*","memory");
  try{
   tomcat.start();int port=tomcat.getConnector().getLocalPort();URI uri=URI.create("http://127.0.0.1:"+port+"/memory");var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
   check(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"preexisting keep-alive read works");
   var existing=client.sendAsync(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("memory")).build(),HttpResponse.BodyHandlers.ofString());check(entered.await(5,TimeUnit.SECONDS),"existing handler admitted before pause");
   var protocol=(AbstractProtocol<?>)tomcat.getConnector().getProtocolHandler();protocol.pause();check(protocol.isPaused(),"actual Tomcat connector pause acknowledged");
   var denied=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(1500)).POST(HttpRequest.BodyPublishers.ofString("not admitted")).build(),HttpResponse.BodyHandlers.ofString());
   Thread.sleep(300);check(!existing.isDone()&&!interrupted.get()&&writes.get()==1,"pause neither interrupts the active handler nor admits a new write");
   release.countDown();check(existing.get(5,TimeUnit.SECONDS).statusCode()==200&&!interrupted.get(),"existing handler completes normally while paused");
   try{var response=denied.get(3,TimeUnit.SECONDS);check(response.statusCode()==503,"paused admission rejects with 503");}catch(ExecutionException expected){check(writes.get()==1,"paused fresh connection is not processed");}
   long deadline=System.nanoTime()+5_000_000_000L;Object executor=protocol.getExecutor();while((int)executor.getClass().getMethod("getActiveCount").invoke(executor)!=0&&System.nanoTime()<deadline)Thread.sleep(20);
   check((int)executor.getClass().getMethod("getActiveCount").invoke(executor)==0,"HTTP executor drains without a shutdown deadline");
   AdmissionValve gate=new AdmissionValve();context.getPipeline().addValve(gate);protocol.resume();
   check(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"reads resume through the original pipeline");
   for(String method:new String[]{"POST","PATCH","PUT","DELETE"}){
    var response=client.send(HttpRequest.newBuilder(uri).method(method,HttpRequest.BodyPublishers.ofString("synthetic only")).build(),HttpResponse.BodyHandlers.ofString());
    check(response.statusCode()==503&&response.headers().firstValue("X-KOC-Write-Admission").orElse("").equals("closed"),method+" is rejected before the handler");
   }
   check(writes.get()==1&&gate.active()==0&&gate.rejected()>=4,"no rejected mutation reaches the handler and counters settle");
   gate.closeAll();check(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==503,"all-admission mode also blocks GET");
   gate.readsOnly();check(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,"reads-only restoration never reopens writes");
   var factory=new org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory(0);var startupGate=new AdmissionValve();new GatedCrmMain.GateCustomizer(startupGate).customize(factory);check(factory.getContextValves().contains(startupGate),"startup customizer installs the gate before the connector starts");
   try{GatedCrmMain.main(new String[0]);throw new AssertionError("unguarded bootstrap must not run");}catch(IllegalStateException expected){System.out.println("PASS missing bootstrap hash/mode fails before Spring starts");}
   System.out.println("ALL_DRAIN_SELF_TESTS_PASS; no CRM or BigQuery request made");
  }finally{release.countDown();tomcat.stop();tomcat.destroy();}
 }
}
