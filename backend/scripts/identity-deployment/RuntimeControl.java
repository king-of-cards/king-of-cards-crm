package com.kingofcards.identity.maintenance;

import java.nio.file.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import com.google.cloud.bigquery.BigQuery;

/** Local-file control only. Shutdown never runs in the Windows Attach Listener thread. */
public final class RuntimeControl {
    private static ConfigurableApplicationContext context;
    private static IdentityGate gate;
    private static Connector connector;
    private static Path root,bundleFile;
    private static Properties bundle;
    private static String bundleHash,appHash,installNonce;
    private static final Set<String> consumed=new HashSet<>();
    private static boolean started;
    private static Instant drainedAt;

    public static Properties verifyBundle(Path file,String expected)throws Exception {
        Safety.inside(file.toString(),Safety.ROOT.resolve("local-dev/identity-deployment"));
        Safety.require(Safety.hash(file).equals(expected),"Bundle hash differs");var p=Safety.load(file);
        Safety.privateControl(file.toRealPath().getParent(),p);Safety.privateControl(file.toRealPath().getParent().getParent(),p);
        Safety.require(Safety.NEW.equals(p.getProperty("newJar.sha256"))&&Safety.OLD.equals(p.getProperty("oldJar.sha256"))&&Safety.ARCHIVE.equals(p.getProperty("archive.sha256")),"Unapproved artifact/archive pins");
        for(String n:List.of("newJar","oldJar","helper","identityLaunch","gatewayLaunch","privateKey","publicKey","credentials","evidence","archive","runner"))
            Safety.require(Safety.hash(Path.of(p.getProperty(n+".path"))).equals(p.getProperty(n+".sha256")),"Pinned file changed: "+n);
        Path archivePath=Path.of(p.getProperty("archive.path")).toRealPath();var archived=Safety.load(archivePath);
        for(int i=0;i<Integer.parseInt(archived.getProperty("fileCount"));i++){
            Path entry=archivePath.getParent().resolve(archived.getProperty("file."+i+".path")).toRealPath();
            Safety.require(entry.startsWith(archivePath.getParent())&&Safety.hash(entry).equals(archived.getProperty("file."+i+".sha256")),"UNKNOWN operation archive content differs");
        }
        Safety.require(Path.of(p.getProperty("newJar.path")).toRealPath().equals(Safety.ROOT.resolve("identity-service/target-final-integration-verify-20261106/identity-service-0.1.0-SNAPSHOT.jar").toRealPath()),"Unexpected new JAR");
        Safety.require(Path.of(p.getProperty("oldJar.path")).toRealPath().equals(Safety.ROOT.resolve("identity-service/target/identity-service-0.1.0-SNAPSHOT.jar").toRealPath()),"Unexpected recovery JAR");
        Safety.require(Path.of(p.getProperty("helper.path")).toRealPath().equals(Path.of(RuntimeControl.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath()),"Runtime helper differs");
        return p;
    }
    public static void validateEnvironment(Environment env,Properties pins)throws Exception {
        Safety.require("dev".equals(env.getProperty("spring.profiles.active"))&&Arrays.equals(env.getActiveProfiles(),new String[]{"dev"}),"Identity must use dev only");
        Safety.require("8081".equals(env.getProperty("server.port"))&&"identity-service".equals(env.getProperty("spring.application.name")),"Identity server differs");
        for(String n:pins.stringPropertyNames())if(n.startsWith("expected.")) {
            String property=n.substring(9);String actual=env.getProperty(property);
            Safety.require(actual!=null&&Safety.textHash(actual).equals(pins.getProperty(n)),"Runtime setting differs: "+property);
        }
        Safety.require("true".equals(env.getProperty("identity.bigquery.enabled"))&&"true".equals(env.getProperty("identity.bigquery.production-mutations-enabled")),"Existing authentication storage flags differ");
        Safety.require("king-of-cards-509610".equals(env.getProperty("identity.bigquery.project-id"))&&"shared_rds_data".equals(env.getProperty("identity.bigquery.dataset"))&&"asia-south1".equals(env.getProperty("identity.bigquery.location")),"Identity storage target differs");
    }
    public static void validateStartup(Environment env)throws Exception {
        var p=verifyBundle(Path.of(System.getProperty("koc.identity.bundle")),System.getProperty("koc.identity.bundle.sha256"));validateEnvironment(env,p);
        Safety.require("true".equals(System.getProperty("koc.identity.gated")),"Closed startup flag required");
    }
    private static ConfigurableApplicationContext locate()throws Exception {
        Field hook=SpringApplication.class.getDeclaredField("shutdownHook");hook.setAccessible(true);Object h=hook.get(null);Field f=h.getClass().getDeclaredField("contexts");f.setAccessible(true);
        ConfigurableApplicationContext found=null;
        for(Object o:((Set<?>)f.get(h)).toArray())if(o instanceof ConfigurableApplicationContext c&&c.isActive()&&"identity-service".equals(c.getEnvironment().getProperty("spring.application.name"))){Safety.require(found==null,"Multiple Identity contexts");found=c;}
        Safety.require(found!=null,"Identity context unavailable");return found;
    }
    private static TomcatWebServer server()throws Exception{return (TomcatWebServer)context.getClass().getMethod("getWebServer").invoke(context);}
    private static void connector()throws Exception {
        var cs=server().getTomcat().getService().findConnectors();Safety.require(cs.length==1&&cs[0].getPort()==8081&&server().getPort()==8081,"Unexpected connector");connector=cs[0];
        Safety.require(connector.getProtocolHandler().getClass().getName().equals("org.apache.coyote.http11.Http11NioProtocol"),"Unsupported connector");
    }
    private static String application()throws Exception {
        // Spring Boot nested: URLs differ by launcher; process classpath is the authoritative outer JAR.
        var jars=Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))).filter(s->s.endsWith("identity-service-0.1.0-SNAPSHOT.jar")).toList();
        Safety.require(jars.size()==1,"Expected one Identity outer JAR");Path p=Path.of(jars.getFirst()).toRealPath();
        String hash=Safety.hash(p);Safety.require(hash.equals(Safety.OLD)||hash.equals(Safety.NEW),"Unapproved Identity executable");return hash;
    }
    private static void configure(Path f,String hash)throws Exception {
        bundleFile=f.toRealPath();bundleHash=hash;bundle=verifyBundle(f,hash);root=f.toRealPath().getParent();
        validateEnvironment(context.getEnvironment(),bundle);connector();appHash=application();
    }
    public static synchronized void install(Properties request)throws Exception {
        Safety.require(!started,"Controller already installed; use its file channel");context=locate();
        configure(Path.of(request.getProperty("bundle")),request.getProperty("bundleSha256"));
        Safety.require(Safety.OLD.equals(appHash)&&Long.toString(ProcessHandle.current().pid()).equals(request.getProperty("pid")),"Unexpected existing Identity");
        installNonce=request.getProperty("nonce");var protocol=(AbstractProtocol<?>)connector.getProtocolHandler();
        var children=server().getTomcat().getHost().findChildren();Safety.require(children.length==1&&children[0] instanceof Context,"Unexpected web context");
        var c=(Context)children[0];Safety.require(c.getPath().isEmpty(),"Unexpected context path");gate=new IdentityGate();
        // Make the independent cancel/status channel available before changing admission.
        startWatcher();protocol.pause();try{c.getPipeline().addValve(gate);}finally{protocol.resume();}
        var p=snapshot();p.setProperty("installNonce",installNonce);Safety.save(p,root.resolve("installed.properties"));
    }
    public static synchronized void start(ConfigurableApplicationContext c,IdentityGate g)throws Exception {
        Safety.require(!started,"Controller already started");context=c;gate=g;
        configure(Path.of(System.getProperty("koc.identity.bundle")),System.getProperty("koc.identity.bundle.sha256"));
        Safety.require(gate.counts().mode()==IdentityGate.Mode.MUTATIONS_CLOSED,"Startup admission is not closed");
        installNonce="STARTUP_NO_ATTACH";startWatcher();Safety.save(snapshot(),root.resolve("startup-"+ProcessHandle.current().pid()+".properties"));
    }
    private static void startWatcher() {
        started=true;Thread worker=new Thread(()->{
            while(context.isActive()) {
                try(var files=Files.list(root)){
                    for(Path file:files.filter(p->p.getFileName().toString().endsWith(".command.properties")).sorted().toList()) {
                        String name=file.getFileName().toString();if(!consumed.add(name))continue;
                        Properties response=new Properties();String nonce=name.replace(".command.properties","");
                        try {var request=Safety.load(file);Safety.require(nonce.equals(request.getProperty("nonce")),"Nonce mismatch");checkRequest(request);response=execute(request);response.setProperty("ok","true");}
                        catch(Exception failed){response.setProperty("ok","false");response.setProperty("failureType",failed.getClass().getSimpleName());}
                        Safety.save(response,root.resolve(nonce+".response.properties"));
                    }
                }catch(Exception ignored){/* Fail closed; never release on an unreadable control file. */}
                try{Thread.sleep(100);}catch(InterruptedException stop){Thread.currentThread().interrupt();return;}
            }
        },"identity-maintenance-control");worker.setDaemon(true);worker.start();
    }
    private static void checkRequest(Properties r)throws Exception {
        Safety.require(Files.size(root.resolve(r.getProperty("nonce")+".command.properties"))<16384,"Oversize control request");
        Safety.require(r.getProperty("nonce").matches("[0-9a-f-]{36}")&&r.getProperty("bundleSha256").equals(bundleHash),"Command scope differs");
        Safety.require(Long.toString(ProcessHandle.current().pid()).equals(r.getProperty("pid"))&&startedAt().equals(r.getProperty("processStartedUtc")),"Command process differs");
        Instant issued=Instant.parse(r.getProperty("issuedUtc")),expires=Instant.parse(r.getProperty("expiresUtc"));
        Safety.require(!issued.isAfter(Instant.now())&&expires.isAfter(Instant.now())&&Duration.between(issued,expires).compareTo(Duration.ofSeconds(180))<=0,"Expired command");
        verifyBundle(bundleFile,bundleHash);validateEnvironment(context.getEnvironment(),bundle);
    }
    private static String startedAt(){return ProcessHandle.current().info().startInstant().orElseThrow().toString();}
    private static Properties snapshot()throws Exception {
        var contexts=server().getTomcat().getHost().findChildren();Safety.require(contexts.length==1&&contexts[0] instanceof Context&&Arrays.stream(((Context)contexts[0]).getPipeline().getValves()).anyMatch(v->v==gate),"Admission valve is not in the live Identity pipeline");
        var p=new Properties();var protocol=(AbstractProtocol<?>)connector.getProtocolHandler();Object executor=protocol.getExecutor();Safety.require(executor!=null,"HTTP executor unavailable");
        p.setProperty("httpActive",String.valueOf(executor.getClass().getMethod("getActiveCount").invoke(executor)));
        p.setProperty("httpQueued",String.valueOf(((Collection<?>)executor.getClass().getMethod("getQueue").invoke(executor)).size()));
        Object global=protocol.getHandler().getGlobal();Field processors=global.getClass().getDeclaredField("processors");processors.setAccessible(true);int processing=0;
        synchronized(global){for(Object info:(List<?>)processors.get(global)){int stage=(int)info.getClass().getMethod("getStage").invoke(info);if(stage>=1&&stage<=5)processing++;}}
        p.setProperty("httpProcessing",Integer.toString(processing));var counts=gate.counts();p.setProperty("gateMode",counts.mode().name());p.setProperty("gateActive",Integer.toString(counts.active()));p.setProperty("gateMutations",Integer.toString(counts.mutations()));p.setProperty("gateRejected",Long.toString(counts.rejected()));
        p.setProperty("connectorPaused",Boolean.toString(protocol.isPaused()));int mutations=0;
        for(var trace:Thread.getAllStackTraces().values())if(Arrays.stream(trace).anyMatch(frame->frame.getClassName().equals("com.kingofcards.identity.bigquery.production.ProductionAuthAdapter")&&Set.of("submit","recover","await").contains(frame.getMethodName())))mutations++;
        p.setProperty("mutationThreads",Integer.toString(mutations));p.setProperty("pid",Long.toString(ProcessHandle.current().pid()));p.setProperty("processStartedUtc",startedAt());p.setProperty("applicationSha256",appHash);p.setProperty("helperSha256",bundle.getProperty("helper.sha256"));p.setProperty("configurationSha256",bundleHash);p.setProperty("archiveSha256",bundle.getProperty("archive.sha256"));p.setProperty("observedUtc",Instant.now().toString());return p;
    }
    private static void idle()throws Exception {
        long deadline=System.nanoTime()+30_000_000_000L;int stable=0;
        while(System.nanoTime()<deadline){var p=snapshot();boolean zero=true;for(String n:List.of("httpActive","httpQueued","httpProcessing","gateActive","gateMutations","mutationThreads"))zero&="0".equals(p.getProperty(n));if(zero)stable++;else stable=0;if(stable==5)return;Thread.sleep(200);}
        throw new IllegalStateException("Drain incomplete; admission stays closed, no request interrupted");
    }
    private static Properties audit()throws Exception{return ReadAudit.inspect(context.getBean(BigQuery.class),bundle.getProperty("principalSha256"));}
    private static Properties execute(Properties r)throws Exception {
        String action=r.getProperty("action");var protocol=(AbstractProtocol<?>)connector.getProtocolHandler();
        if(action.equals("inspect"))return snapshot();
        if(action.equals("pause-auth")){gate.closeMutations();protocol.resume();return snapshot();}
        if(action.equals("cancel")||action.equals("release")) {
            Safety.require("APPROVE_IDENTITY_ADMISSION_RELEASE".equals(r.getProperty("approval")),"Explicit admission release approval required");
            if(action.equals("cancel")){
                Safety.require(Safety.OLD.equals(appHash)&&!Files.exists(root.resolve("stop-consumed.properties")),"Cancellation only before old JVM shutdown");
                var cancelled=new Properties();cancelled.setProperty("cancelledUtc",Instant.now().toString());Safety.save(cancelled,root.resolve("cancelled.properties"));
            }
            else {
                Path proofFile=Safety.inside(r.getProperty("verificationPath"),root);var proof=Safety.load(proofFile);
                Safety.require(Safety.hash(proofFile).equals(r.getProperty("verificationSha256")),"Post-start verification pin differs");
                Instant observed=Instant.parse(proof.getProperty("observedUtc"));Safety.require(!observed.isAfter(Instant.now())&&Duration.between(observed,Instant.now()).compareTo(Duration.ofSeconds(60))<0,"Post-start verification expired; obtain a fresh read-only proof");
                Safety.require(appHash.equals(proof.getProperty("applicationSha256"))&&startedAt().equals(proof.getProperty("processStartedUtc")),"Wrong verification process");
                for(String n:List.of("health","readiness","eureka","gateway","jwks","admissionProbes"))Safety.require("PASS".equals(proof.getProperty(n)),"Missing post-start verification: "+n);
                Safety.require((Safety.NEW.equals(appHash)?"PASS":"EXPECTED_ABSENT_OLD").equals(proof.getProperty("vendorProfile")),"Vendor-profile verification is incomplete");
                Safety.sameData(Safety.load(root.resolve("certificate.properties")),audit());
                Safety.require(Duration.between(observed,Instant.now()).compareTo(Duration.ofSeconds(60))<0,"Verification expired while reading Identity storage");
            }
            checkRequest(r);gate.restore(r.getProperty("approval"));protocol.resume();return snapshot();
        }
        if(action.equals("prepare-stop")) {
            Safety.require(!Files.exists(root.resolve("cancelled.properties"))&&Safety.OLD.equals(appHash),"Only original, non-cancelled approved JVM may be stopped");gate.closeAll();protocol.pause();idle();
            Safety.require(drainedAt==null,"Drain preparation is one-use");drainedAt=Instant.now();gate.closeMutations();protocol.resume();
            var p=snapshot();p.setProperty("quietUntilUtc",drainedAt.plusSeconds(315).toString());Safety.save(p,root.resolve("drain-started.properties"));return p;
        }
        if(action.equals("certify")) {
            Safety.require(!Files.exists(root.resolve("cancelled.properties"))&&gate.counts().mode()==IdentityGate.Mode.MUTATIONS_CLOSED&&Safety.OLD.equals(appHash),"Cancelled or changed admission");
            // The pinned production plan binds a 5-minute request_deadline in SQL.
            // No new mutations enter during this window; reads and cancellation remain available.
            Safety.settled(drainedAt,Instant.now());gate.closeAll();protocol.pause();idle();
            Properties a=audit(),b=audit();Safety.sameData(a,b);Safety.require("0".equals(b.getProperty("activeAuthJobs")),"Pending authentication job");idle();
            var p=snapshot();p.putAll(b);Instant now=Instant.now();p.setProperty("issuedUtc",now.toString());p.setProperty("expiresUtc",now.plusSeconds(30).toString());
            Safety.closed(p,now,appHash,bundle.getProperty("helper.sha256"),bundleHash,Safety.ARCHIVE,ProcessHandle.current().pid(),startedAt());
            Safety.save(p,root.resolve("certificate.properties"));return p;
        }
        if(action.equals("stop")) {
            Safety.require(!Files.exists(root.resolve("cancelled.properties"))&&"APPROVE_ONE_IDENTITY_DEPLOYMENT".equals(r.getProperty("approval"))&&Safety.OLD.equals(appHash),"Separate non-cancelled deployment execution approval required");
            Safety.settled(drainedAt,Instant.now());
            var ack=Safety.load(root.resolve("detached.properties"));long caller=Long.parseLong(ack.getProperty("callerPid"));
            Safety.detached(ack,installNonce,ProcessHandle.of(caller).filter(ProcessHandle::isAlive).isPresent());
            var certificate=root.resolve("certificate.properties");Safety.require(Safety.hash(certificate).equals(r.getProperty("certificateSha256")),"Certificate hash differs");var p=Safety.load(certificate);
            var live=snapshot();live.putAll(audit());Safety.sameData(p,live);Safety.closed(liveWithWindow(live,p),Instant.now(),appHash,bundle.getProperty("helper.sha256"),bundleHash,Safety.ARCHIVE,ProcessHandle.current().pid(),startedAt());
            checkRequest(r);Safety.save(p,root.resolve("stop-consumed.properties"));
            // This worker is independent of Attach Listener; CLI has detached AND exited.
            SpringApplication.exit(context);System.exit(0);throw new IllegalStateException("JVM exit did not occur");
        }
        throw new IllegalArgumentException("Unsupported control action");
    }
    private static Properties liveWithWindow(Properties live,Properties old){live.setProperty("issuedUtc",old.getProperty("issuedUtc"));live.setProperty("expiresUtc",old.getProperty("expiresUtc"));return live;}
}
