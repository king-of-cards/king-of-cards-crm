package com.kingofcards.crm.ops.runtime;
import java.nio.file.*;import java.security.*;import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.event.ContextRefreshedEvent;
/** Mandatory pinned loader.main: absent/invalid helper fails before the existing CRM starts. */
public final class GatedCrmMain {
 public static void main(String[] args)throws Exception {
  if(!"READS_ONLY".equals(System.getProperty("koc.ops.admission"))||System.getProperty("koc.ops.helper-sha256")==null)throw new IllegalStateException("Pinned maintenance bootstrap required");
  Path self=Path.of(GatedCrmMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(self))).toUpperCase(Locale.ROOT);
  if(!hash.equals(System.getProperty("koc.ops.helper-sha256"))||!"READS_ONLY".equals(System.getProperty("koc.ops.admission")))throw new IllegalStateException("Pinned maintenance bootstrap required");
  Path applicationJar=GateRuntime.applicationJar();
  if(!applicationJar.startsWith(Path.of("C:/kocbackend/king-of-cards-crm/backend/crm-service").toAbsolutePath().normalize()))throw new IllegalStateException("Expected existing CRM artifact");
  String appHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(applicationJar))).toUpperCase(Locale.ROOT);
  if(!appHash.equals(System.getProperty("koc.ops.application-sha256"))||!java.util.List.of("A7CB8433FF70F0D218819FB03D817FE8F9AC24E502050DB3B73D8E84A9281F97","405926DC620E64C72A56170526D365783A380CC33B963DE4581D6F2ABE8E8EB5").contains(appHash))throw new IllegalStateException("Application artifact pin differs");
  Class<?> existing=Class.forName("com.kingofcards.crm.CrmServiceApplication");
  SpringApplication application=new SpringApplication(existing);
  application.addInitializers(context->{
   AdmissionValve gate=new AdmissionValve();
   context.getBeanFactory().registerSingleton("kocPinnedAdmissionCustomizer",new GateCustomizer(gate));
   context.addApplicationListener(event->{if(event instanceof ContextRefreshedEvent refreshed&&refreshed.getApplicationContext()==context)GateRuntime.setContext(context,gate);});
  });application.run(args);
 }
 public static final class GateCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>{
  private final AdmissionValve gate;public GateCustomizer(AdmissionValve gate){this.gate=gate;}
  @Override public void customize(TomcatServletWebServerFactory factory){factory.addContextValves(gate);}
 }
}
