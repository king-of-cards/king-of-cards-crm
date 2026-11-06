import com.kingofcards.identity.maintenance.*;
import java.io.*;import java.net.*;import java.net.http.*;import java.nio.file.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import jakarta.servlet.*;import jakarta.servlet.http.*;
import org.apache.catalina.startup.Tomcat;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

public class MaintenanceOfflineTest {
    static int passed;static void check(boolean b,String m){if(!b)throw new AssertionError(m);passed++;}
    static void rejects(Runnable f,String m){try{f.run();throw new AssertionError(m);}catch(IllegalStateException ok){passed++;}}
    static Properties certificate(Instant now){var p=new Properties();p.setProperty("issuedUtc",now.toString());p.setProperty("expiresUtc",now.plusSeconds(30).toString());p.setProperty("pid","123");p.setProperty("processStartedUtc","start");p.setProperty("applicationSha256",Safety.OLD);p.setProperty("helperSha256","helper");p.setProperty("configurationSha256","config");p.setProperty("archiveSha256",Safety.ARCHIVE);p.setProperty("gateMode","ALL_CLOSED");p.setProperty("connectorPaused","true");for(String n:List.of("httpActive","httpQueued","httpProcessing","gateActive","gateMutations","mutationThreads","activeAuthJobs","orphans"))p.setProperty(n,"0");p.setProperty("ownJobPagesComplete","true");p.setProperty("principalVerified","true");return p;}
    static void valid(Properties p,Instant now){Safety.closed(p,now,Safety.OLD,"helper","config",Safety.ARCHIVE,123,"start");}
    static Properties completeCertificate(Instant now){var p=certificate(now);p.setProperty("recentAuthNonterminal","0");p.setProperty("recentAuthPagesComplete","true");return p;}
    public static void main(String[]args)throws Exception {
        Files.createDirectories(Path.of(args[0]));Path folder=Files.createTempDirectory(Path.of(args[0]),"suite-");var gate=new IdentityGate();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var writes=new AtomicInteger();var interrupted=new AtomicBoolean();var async=new AtomicReference<AsyncContext>();
        var tomcat=new Tomcat();tomcat.setBaseDir(Files.createTempDirectory(folder,"tomcat-").toString());tomcat.setPort(0);var context=tomcat.addContext("",Files.createTempDirectory(folder,"context-").toString());context.getPipeline().addValve(gate);
        var wrapper=Tomcat.addServlet(context,"fixture",new HttpServlet(){protected void doGet(HttpServletRequest q,HttpServletResponse r)throws IOException{if(q.getRequestURI().equals("/async")){async.set(q.startAsync());}else r.getWriter().write("fixture");}protected void doPost(HttpServletRequest q,HttpServletResponse r)throws IOException{writes.incrementAndGet();entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted.set(true);Thread.currentThread().interrupt();}r.getWriter().write("finished");}});wrapper.setAsyncSupported(true);context.addServletMappingDecoded("/*","fixture");
        try {
            tomcat.start();String base="http://127.0.0.1:"+tomcat.getConnector().getLocalPort();var http=HttpClient.newHttpClient();
            for(String verb:List.of("POST","PUT","PATCH","DELETE","TRACE"))for(String path:List.of("/auth/login","/auth/refresh","/auth/logout","/admin/vendors/accounts","/api/identity/auth/login","/api/identity/auth/refresh","/api/identity/auth/logout")){
                var response=http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(3)).method(verb,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
                check((response.statusCode()==503&&response.headers().firstValue("X-KOC-Identity-Admission").orElse("").equals("closed"))||(verb.equals("TRACE")&&response.statusCode()==405),"mutation bypass: "+verb+path);
            }
            check(writes.get()==0,"rejected mutations reached a handler");
            check(http.send(HttpRequest.newBuilder(URI.create(base+"/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200,"maintenance reads unavailable");
            rejects(()->gate.restore("wrong"),"unapproved release");gate.restore("APPROVE_IDENTITY_ADMISSION_RELEASE");
            var pending=http.sendAsync(HttpRequest.newBuilder(URI.create(base+"/auth/refresh")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());check(entered.await(3,TimeUnit.SECONDS),"refresh did not enter");
            gate.closeMutations();check(gate.counts().active()==1&&gate.counts().mutations()==1&&!pending.isDone(),"admitted refresh interrupted/uncounted");
            check(http.send(HttpRequest.newBuilder(URI.create(base+"/auth/logout")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==503,"new logout admitted");
            release.countDown();check(pending.get(3,TimeUnit.SECONDS).statusCode()==200&&!interrupted.get(),"admitted refresh did not finish");check(gate.counts().active()==0&&gate.counts().mutations()==0,"drain counters nonzero");
            var asyncRead=http.sendAsync(HttpRequest.newBuilder(URI.create(base+"/async")).GET().build(),HttpResponse.BodyHandlers.discarding());long end=System.nanoTime()+3_000_000_000L;while(async.get()==null&&System.nanoTime()<end)Thread.sleep(10);
            check(async.get()!=null&&gate.counts().active()==1,"asynchronous request escaped counter");async.get().complete();asyncRead.get(3,TimeUnit.SECONDS);end=System.nanoTime()+1_000_000_000L;while(gate.counts().active()!=0&&System.nanoTime()<end)Thread.sleep(10);check(gate.counts().active()==0,"async completion not drained");
            gate.closeAll();check(http.send(HttpRequest.newBuilder(URI.create(base+"/health")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==503,"all-admission fence bypassed");gate.restore("APPROVE_IDENTITY_ADMISSION_RELEASE");check(http.send(HttpRequest.newBuilder(URI.create(base+"/auth/logout")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200,"cancel did not restore original admission");
        } finally {release.countDown();tomcat.stop();tomcat.destroy();}
        // Exercise the SAME factory customizer used by the production initializer, before first connection.
        var startupGate=new IdentityGate();var factory=new TomcatServletWebServerFactory(0);factory.setBaseDirectory(Files.createTempDirectory(folder,"startup-").toFile());new IdentityMaintenanceInitializer.StartupCustomizer(startupGate).customize(factory);
        var server=factory.getWebServer(c->{var servlet=c.addServlet("first",new HttpServlet(){protected void doPost(HttpServletRequest q,HttpServletResponse r){throw new AssertionError("unguarded first request");}});servlet.addMapping("/*");});
        try{server.start();var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getPort()+"/auth/login")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());check(response.statusCode()==503,"startup first request not closed");}finally{server.stop();server.destroy();}
        Instant now=Instant.now();valid(completeCertificate(now),now);passed++;
        for(String key:List.of("applicationSha256","helperSha256","configurationSha256","archiveSha256","pid","processStartedUtc","gateMode","connectorPaused","httpActive","httpQueued","httpProcessing","gateActive","gateMutations","mutationThreads","activeAuthJobs","recentAuthNonterminal","orphans","ownJobPagesComplete","recentAuthPagesComplete","principalVerified")){
            var p=completeCertificate(now);p.setProperty(key,"changed");rejects(()->valid(p,now),"unsafe certificate: "+key);
        }
        rejects(()->valid(completeCertificate(now.minusSeconds(31)),now),"expired certificate");rejects(()->valid(completeCertificate(now.plusSeconds(1)),now),"future certificate");
        var excessive=completeCertificate(now);excessive.setProperty("expiresUtc",now.plusSeconds(31).toString());rejects(()->valid(excessive,now),"overlong certificate");
        for(int bit=0;bit<5;bit++){final int b=bit;rejects(()->Safety.rollback(b==0,b==1,b==2,b!=3,b!=4),"unsafe rollback");}Safety.rollback(false,false,false,true,true);passed++;
        var data=new Properties();for(String k:List.of("accounts","sessions","orphans","accountDigest","sessionDigest","usersSchema","sessionsSchema"))data.setProperty(k,"original");Safety.sameData(data,data);passed++;
        for(String k:data.stringPropertyNames()){var changed=(Properties)data.clone();changed.setProperty(k,"different");rejects(()->Safety.sameData(data,changed),"data drift: "+k);}
        var ack=new Properties();ack.setProperty("detached","true");ack.setProperty("installNonce","nonce");Safety.detached(ack,"nonce",false);passed++;rejects(()->Safety.detached(ack,"nonce",true),"exit while helper alive");rejects(()->Safety.detached(ack,"other",false),"wrong attach acknowledgment");ack.setProperty("detached","false");rejects(()->Safety.detached(ack,"nonce",false),"no detach");
        var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("offline",Map.of("spring.profiles.active","lead-dashboard-local")));rejects(()->{try{RuntimeControl.validateEnvironment(env,new Properties());}catch(IllegalStateException e){throw e;}catch(Exception e){throw new RuntimeException(e);}},"local profile accepted");
        rejects(()->Safety.settled(now,now.plusSeconds(314)),"request deadline settling window skipped");rejects(()->Safety.settled(now.plusSeconds(1),now),"future drain");Safety.settled(now,now.plusSeconds(315));passed++;
        Path marker=folder.resolve("consumed.marker");Safety.save(new Properties(),marker);try{Safety.save(new Properties(),marker);throw new AssertionError("attempt marker overwritten");}catch(FileAlreadyExistsException ok){passed++;}
        System.out.println("IDENTITY_MAINTENANCE_OFFLINE_PASS checks="+passed+"; no live service/account/database calls");
    }
}
