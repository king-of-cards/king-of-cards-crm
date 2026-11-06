package com.kingofcards.identity.ops;
import java.nio.file.*;import java.security.*;import java.lang.reflect.*;import java.time.*;import java.util.*;
import org.springframework.boot.SpringApplication;import org.springframework.context.ConfigurableApplicationContext;import org.springframework.boot.tomcat.TomcatWebServer;
import org.apache.catalina.*;import org.apache.catalina.connector.Connector;import org.apache.coyote.AbstractProtocol;
import com.google.cloud.bigquery.BigQuery;import com.google.auth.oauth2.ServiceAccountCredentials;
import com.kingofcards.identity.bigquery.IdentityBigQueryProperties;import com.kingofcards.identity.repository.*;
/** No SQL, authentication, token issuance, stop, exit, cancellation or write reopening. */
public final class IdentityObserveRuntime {
 private static ConfigurableApplicationContext context;private static Connector connector;private static IdentityAdmissionValve gate;
 private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).toUpperCase(Locale.ROOT);}
 private static void locate()throws Exception{
  if(context==null){Field hook=SpringApplication.class.getDeclaredField("shutdownHook");hook.setAccessible(true);Object value=hook.get(null);Field contexts=value.getClass().getDeclaredField("contexts");contexts.setAccessible(true);
   for(Object item:((Set<?>)contexts.get(value)).toArray())if(item instanceof ConfigurableApplicationContext candidate&&candidate.isActive()&&"identity-service".equals(candidate.getEnvironment().getProperty("spring.application.name"))){if(context!=null)throw new IllegalStateException("Multiple Identity contexts");context=candidate;}
  }
  if(context==null||!context.isActive())throw new IllegalStateException("Identity context is unavailable");
  Object server=context.getClass().getMethod("getWebServer").invoke(context);if(!(server instanceof TomcatWebServer tomcat)||tomcat.getPort()!=8081)throw new IllegalStateException("Unexpected Identity server/port");
  var connectors=tomcat.getTomcat().getService().findConnectors();if(connectors.length!=1||connectors[0].getPort()!=8081)throw new IllegalStateException("Unexpected alternate Identity connector");connector=connectors[0];
  if(!(connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol)||!protocol.getClass().getName().equals("org.apache.coyote.http11.Http11NioProtocol"))throw new IllegalStateException("Unsupported connector");
 }
 private static Properties snapshot(Properties request)throws Exception{
  var result=new Properties();result.setProperty("pid",Long.toString(ProcessHandle.current().pid()));result.setProperty("observedUtc",Instant.now().toString());result.setProperty("certifiedForStop","false");
  var protocol=(AbstractProtocol<?>)connector.getProtocolHandler();Object executor=protocol.getExecutor();if(executor==null)throw new IllegalStateException("HTTP executor is unavailable");
  result.setProperty("httpActive",String.valueOf(executor.getClass().getMethod("getActiveCount").invoke(executor)));result.setProperty("httpQueued",String.valueOf(((Collection<?>)executor.getClass().getMethod("getQueue").invoke(executor)).size()));
  Object global=protocol.getHandler().getGlobal();Field processors=global.getClass().getDeclaredField("processors");processors.setAccessible(true);int processing=0; synchronized(global){for(Object info:(List<?>)processors.get(global)){int stage=(int)info.getClass().getMethod("getStage").invoke(info);if(stage>=1&&stage<=5)processing++;}}
  result.setProperty("httpProcessing",Integer.toString(processing));result.setProperty("connectorPaused",Boolean.toString(protocol.isPaused()));
  result.setProperty("liveGateVerified",Boolean.toString(gate!=null));if(gate!=null){var s=gate.snapshot();result.setProperty("gateMode",s.mode().name());result.setProperty("gateActive",Integer.toString(s.active()));result.setProperty("gateMutations",Integer.toString(s.mutations()));result.setProperty("gateRejected",Long.toString(s.rejected()));}
  int mutations=0;for(var trace:Thread.getAllStackTraces().values())if(Arrays.stream(trace).anyMatch(frame->frame.getClassName().equals("com.kingofcards.identity.bigquery.production.ProductionAuthAdapter")&&Set.of("submit","recover","await").contains(frame.getMethodName())))mutations++;
  result.setProperty("observedAuthMutationThreads",Integer.toString(mutations));
  var bq=context.getBean(BigQuery.class);if(!(bq.getOptions().getCredentials() instanceof ServiceAccountCredentials account))throw new IllegalStateException("Unsupported credential identity");String principal=sha(account.getClientEmail().getBytes(java.nio.charset.StandardCharsets.UTF_8));result.setProperty("principalSha256",principal);
  if(!principal.equals(request.getProperty("expectedPrincipalSha256")))throw new IllegalStateException("Running principal differs from saved launcher credential");
  var properties=context.getBean(IdentityBigQueryProperties.class);properties.validate();result.setProperty("project",properties.projectId());result.setProperty("dataset",properties.dataset());result.setProperty("location",properties.location());
  for(String name:List.of("identity.jwt.issuer","identity.jwt.audience")){
   String value=context.getEnvironment().getProperty(name);if(value==null||!sha(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(request.getProperty("expectedSha256."+name)))throw new IllegalStateException("Running JWT setting differs from saved configuration");result.setProperty("matchesSaved."+name,"true");
  }
  for(String name:List.of("spring.profiles.active","identity.bigquery.enabled","identity.bigquery.production-mutations-enabled","identity.jwt.access-token-ttl","identity.refresh.token-ttl","identity.cookie.secure","identity.cookie.same-site"))result.setProperty(name,context.getEnvironment().getProperty(name,"UNAVAILABLE"));
  result.setProperty("userRepositoryClass",context.getBean(IdentityUserRepository.class).getClass().getName());result.setProperty("sessionRepositoryClass",context.getBean(RefreshTokenSessionRepository.class).getClass().getName());return result;
 }
 private static void waitIdle(Properties request)throws Exception{
  long deadline=System.nanoTime()+90_000_000_000L;int stable=0;
  while(System.nanoTime()<deadline){var p=snapshot(request);if(p.getProperty("httpActive").equals("0")&&p.getProperty("httpQueued").equals("0")&&p.getProperty("httpProcessing").equals("0")&&p.getProperty("observedAuthMutationThreads").equals("0"))stable++;else stable=0;if(stable>=5)return;Thread.sleep(200);}
  throw new IllegalStateException("Drain did not settle; connector left paused, no request interrupted");
 }
 public static synchronized void execute(Properties request,Path root)throws Exception{
  Path jar=null;for(String entry:System.getProperty("java.class.path","").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))if(entry.endsWith("identity-service-0.1.0-SNAPSHOT.jar")){if(jar!=null)throw new IllegalStateException("Multiple Identity application artifacts");jar=Path.of(entry).toAbsolutePath().normalize();}
  if(jar==null||!jar.startsWith(Path.of("C:/kocbackend/king-of-cards-crm/backend/identity-service").toAbsolutePath().normalize()))throw new IllegalStateException("Expected Identity artifact missing");
  String hash=sha(Files.readAllBytes(jar));if(!Set.of("2B7E8A0655EC5C87DD761A756D70A6D233F9EF09E93BAB564B0B23A538F328DC","8CC080E57C602C4D24CFABFACC35B7788B6D37D8B60AE2FA5038CB73777910B5").contains(hash)||!hash.equals(request.getProperty("applicationSha256")))throw new IllegalStateException("Identity application pin differs");
  locate();snapshot(request);String action=request.getProperty("action");
  if(action.equals("pause-auth")){
   if(!"APPROVE_TEMPORARY_IDENTITY_AUTH_FENCE".equals(request.getProperty("approval")))throw new IllegalStateException("Admission approval missing");
   var protocol=(AbstractProtocol<?>)connector.getProtocolHandler();protocol.pause();
   if(gate==null){var tomcat=(TomcatWebServer)context.getClass().getMethod("getWebServer").invoke(context);var children=tomcat.getTomcat().getHost().findChildren();if(children.length!=1||!(children[0] instanceof Context c)||!c.getPath().isEmpty())throw new IllegalStateException("Unexpected application context");gate=new IdentityAdmissionValve();c.getPipeline().addValve(gate);}
   gate.pauseSessions();waitIdle(request);protocol.resume();
  }else if(action.equals("drain-all")){
   if(!"APPROVE_IDENTITY_ALL_ADMISSION_DRAIN".equals(request.getProperty("approval"))||gate==null)throw new IllegalStateException("Verified gate/drain approval required");
   gate.closeAll();((AbstractProtocol<?>)connector.getProtocolHandler()).pause();waitIdle(request);
  }else if(!action.equals("inspect"))throw new IllegalArgumentException("Only observation and separately approved admission controls supported");
  var status=snapshot(request);status.setProperty("applicationSha256",hash);status.setProperty("action",action);status.setProperty("helperSha256",request.getProperty("helperSha256"));
  String nonce=request.getProperty("nonce");if(nonce==null||!nonce.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("Valid observation nonce required");
  try(var output=Files.newOutputStream(root.resolve("observation-"+nonce+".properties"),StandardOpenOption.CREATE_NEW)){status.store(output,"No credentials, tokens, customer data or stop/start authorization");}
 }
}
