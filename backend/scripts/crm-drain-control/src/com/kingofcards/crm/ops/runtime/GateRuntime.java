package com.kingofcards.crm.ops.runtime;
import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import java.security.*;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.catalina.*;import org.apache.catalina.connector.Connector;import org.apache.coyote.AbstractProtocol;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import com.google.cloud.bigquery.BigQuery;import com.google.auth.oauth2.ServiceAccountCredentials;
public final class GateRuntime {
 static ConfigurableApplicationContext context;static Connector connector;static AdmissionValve valve;static String applicationPin="",helperPin="";
 private static final String OLD="A7CB8433FF70F0D218819FB03D817FE8F9AC24E502050DB3B73D8E84A9281F97",NEW="405926DC620E64C72A56170526D365783A380CC33B963DE4581D6F2ABE8E8EB5";
 public static synchronized void setContext(ConfigurableApplicationContext value,AdmissionValve gate){context=value;valve=gate;}
 private static void locate()throws Exception{
  if(context==null){Class<?> app=Class.forName("org.springframework.boot.SpringApplication");Field field=app.getDeclaredField("shutdownHook");field.setAccessible(true);Object hook=field.get(null);Field contexts=hook.getClass().getDeclaredField("contexts");contexts.setAccessible(true);
   Object[] values;synchronized(hook.getClass()){values=((Set<?>)contexts.get(hook)).toArray();}
   for(Object c:values)if(c instanceof ConfigurableApplicationContext candidate&&candidate.isActive()&&"crm-service".equals(candidate.getEnvironment().getProperty("spring.application.name"))){if(context!=null)throw new IllegalStateException("Multiple CRM contexts");context=candidate;}
  }
  if(context==null||!context.isActive())throw new IllegalStateException("CRM context unavailable");
  Object server=context.getClass().getMethod("getWebServer").invoke(context);if(!(server instanceof TomcatWebServer tomcat)||tomcat.getPort()!=8082)throw new IllegalStateException("Unexpected existing server");
  var connectors=tomcat.getTomcat().getService().findConnectors();if(connectors.length!=1||connectors[0].getPort()!=8082)throw new IllegalStateException("Unverified alternate connector");connector=connectors[0];
  if(!(connector.getProtocolHandler() instanceof AbstractProtocol<?> p)||!p.getClass().getName().equals("org.apache.coyote.http11.Http11NioProtocol")||connector.findUpgradeProtocols().length!=0)throw new IllegalStateException("Only the verified HTTP/1.1 connector is supported");
  if(valve==null){var children=tomcat.getTomcat().getHost().findChildren();if(children.length!=1||!(children[0] instanceof Context c)||!c.getPath().isEmpty())throw new IllegalStateException("Unexpected application contexts");for(Valve v:c.getPipeline().getValves())if(v instanceof AdmissionValve g)valve=g;}
 }
 public static Path applicationJar(){
  var candidates=new ArrayList<Path>();for(String entry:System.getProperty("java.class.path","").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))){
   if(entry.endsWith("crm-service-0.1.0-SNAPSHOT.jar"))candidates.add(Path.of(entry).toAbsolutePath().normalize());
  }
  if(candidates.size()!=1)throw new IllegalStateException("One hash-pinned CRM application classpath entry required");return candidates.getFirst();
 }
 private static Path actualJar(){return applicationJar();}

 private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).toUpperCase(Locale.ROOT);}
 private static Map<String,String> snapshot()throws Exception{
  var result=new LinkedHashMap<String,String>();var p=(AbstractProtocol<?>)connector.getProtocolHandler();Object executor=p.getExecutor();if(executor==null||!List.of("org.apache.tomcat.util.threads.ThreadPoolExecutor","java.util.concurrent.ThreadPoolExecutor").contains(executor.getClass().getName()))throw new IllegalStateException("Unverified HTTP executor");
  result.put("applicationSha256",applicationPin);result.put("agentSha256",helperPin);result.put("pid",Long.toString(ProcessHandle.current().pid()));result.put("paused",Boolean.toString(p.isPaused()));result.put("httpActive",Integer.toString((int)executor.getClass().getMethod("getActiveCount").invoke(executor)));result.put("queued",Integer.toString(((java.util.Collection<?>)executor.getClass().getMethod("getQueue").invoke(executor)).size()));
  Object global=p.getHandler().getGlobal();Field processors=global.getClass().getDeclaredField("processors");processors.setAccessible(true);int processing=0;
  synchronized(global){for(Object info:(List<?>)processors.get(global)){int stage=(int)info.getClass().getMethod("getStage").invoke(info);if(stage>=1&&stage<=5)processing++;}}
  result.put("processing",Integer.toString(processing));result.put("gateInstalled",Boolean.toString(valve!=null));result.put("writesBlocked",Boolean.toString(valve!=null));result.put("allClosed",Boolean.toString(valve!=null&&valve.allClosed()));result.put("gateActive",Integer.toString(valve==null?0:valve.active()));result.put("rejected",Long.toString(valve==null?0:valve.rejected()));
  BigQuery bq=context.getBean(BigQuery.class);if(!(bq.getOptions().getCredentials() instanceof ServiceAccountCredentials credentials))throw new IllegalStateException("Unverified BigQuery principal type");result.put("principalSha256",sha(credentials.getClientEmail().getBytes(java.nio.charset.StandardCharsets.UTF_8)));result.put("project",bq.getOptions().getProjectId());result.put("location",bq.getOptions().getLocation());
  for(String key:List.of("crm.bigquery.enabled","crm.bigquery.writes-enabled","crm.directional.enabled","crm.directional.writes-enabled","crm.bigquery.schema-auto-create","crm.bigquery.schema-init-enabled"))result.put(key,context.getEnvironment().getProperty(key,"unknown"));return result;
 }
 private static void save(Path dir,String phase)throws Exception{var data=new Properties();data.putAll(snapshot());data.setProperty("phase",phase);data.setProperty("timestamp",java.time.Instant.now().toString());Path temp=dir.resolve("status-next.properties");try(var out=Files.newOutputStream(temp)){data.store(out,"No credentials or customer values");}Files.move(temp,dir.resolve("status.properties"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
 private static void waitIdle(Path dir)throws Exception{int stable=0;long deadline=System.nanoTime()+180_000_000_000L;while(System.nanoTime()<deadline){var s=snapshot();if(s.get("httpActive").equals("0")&&s.get("queued").equals("0")&&s.get("processing").equals("0")&&s.get("gateActive").equals("0"))stable++;else stable=0;save(dir,"PAUSED_DRAINING");if(stable>=10)return;Thread.sleep(200);}throw new IllegalStateException("Drain timed out; connector left paused; no request interrupted");}
 public static synchronized void execute(Properties request,Path dir)throws Exception{
  Path jar=actualJar();Path base=Path.of("C:/kocbackend/king-of-cards-crm/backend/crm-service").toAbsolutePath().normalize();if(!jar.startsWith(base)||!jar.getFileName().toString().equals("crm-service-0.1.0-SNAPSHOT.jar"))throw new IllegalStateException("Unapproved application artifact");String hash=sha(Files.readAllBytes(jar));if(!(hash.equals(OLD)||hash.equals(NEW))||!hash.equals(request.getProperty("applicationSha256")))throw new IllegalStateException("Application hash mismatch");
  applicationPin=hash;helperPin=request.getProperty("agentSha256");locate();String action=request.getProperty("action");
  try{
   if(action.equals("inspect")||action.equals("status")){save(dir,"INSPECTED");return;}
   if(action.equals("install-drain")){
    ((AbstractProtocol<?>)connector.getProtocolHandler()).pause();if(!((AbstractProtocol<?>)connector.getProtocolHandler()).isPaused())throw new IllegalStateException("Admission pause not confirmed");waitIdle(dir);
    if(valve==null){var tomcat=(TomcatWebServer)context.getClass().getMethod("getWebServer").invoke(context);Context c=(Context)tomcat.getTomcat().getHost().findChildren()[0];valve=new AdmissionValve();c.getPipeline().addValve(valve);}
    valve.readsOnly();((AbstractProtocol<?>)connector.getProtocolHandler()).resume();save(dir,"READS_ONLY_WRITES_BLOCKED");return;
   }
   if(action.equals("drain-all")){if(valve==null)throw new IllegalStateException("Gate not installed");valve.closeAll();((AbstractProtocol<?>)connector.getProtocolHandler()).pause();waitIdle(dir);save(dir,"ALL_ADMISSION_CLOSED_DRAINED");return;}
   if(action.equals("resume-reads")){if(valve==null)throw new IllegalStateException("Cannot resume without a write gate");valve.readsOnly();((AbstractProtocol<?>)connector.getProtocolHandler()).resume();save(dir,"READS_ONLY_WRITES_BLOCKED");return;}
   if(action.equals("shutdown-approved")){
    if(!"true".equals(request.getProperty("approveStop"))||valve==null||!valve.allClosed()||!((AbstractProtocol<?>)connector.getProtocolHandler()).isPaused())throw new IllegalStateException("Separate stop approval and a closed admission gate required");
    var proof=new Properties();try(var in=Files.newInputStream(dir.resolve("drain-evidence.properties"))){proof.load(in);}
    for(String key:List.of("pid","applicationSha256","agentSha256"))if(!Objects.equals(proof.getProperty(key),request.getProperty(key)))throw new IllegalStateException("Drain proof does not match this process/artifact");
    if(!"true".equals(proof.getProperty("ready"))||!"0".equals(proof.getProperty("activeCrmWriteJobs"))||!"0".equals(proof.getProperty("unresolvedKnownOperations"))||java.time.Instant.parse(proof.getProperty("timestamp")).isBefore(java.time.Instant.now().minusSeconds(120)))throw new IllegalStateException("Fresh completed drain/receipt evidence required");
    waitIdle(dir);if(!snapshot().get("principalSha256").equals(proof.getProperty("principalSha256")))throw new IllegalStateException("BigQuery principal differs");
    save(dir,"SEPARATELY_APPROVED_SHUTDOWN_REQUESTED");context.close();System.exit(0);return;
   }
   throw new IllegalArgumentException("Unsupported action; no automatic stop/start/release exists");
  }catch(Exception error){try{save(dir,"ERROR_DO_NOT_DEPLOY");}catch(Exception ignored){}throw error;}
 }
}
