import com.kingofcards.identity.maintenance.*;
import java.nio.file.*;import java.net.*;import java.net.http.*;import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.server.WebServerFactoryCustomizerBeanPostProcessor;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.core.env.*;
import jakarta.servlet.http.*;

public class StartupRegistrationTest {
    @Configuration(proxyBeanMethods=false) static class FactoryConfig {
        @Bean static WebServerFactoryCustomizerBeanPostProcessor factoryPostProcessor(){return new WebServerFactoryCustomizerBeanPostProcessor();}
        @Bean TomcatServletWebServerFactory serverFactory(){return new TomcatServletWebServerFactory(0);}
    }
    public static void main(String[]args)throws Exception {
        var gate=new IdentityGate();try(var context=new AnnotationConfigApplicationContext()){
            // Same registration order as production: initializer singleton, THEN Boot factory BPP.
            context.getBeanFactory().registerSingleton("kocIdentityMaintenanceCustomizer",new IdentityMaintenanceInitializer.StartupCustomizer(gate));context.register(FactoryConfig.class);context.refresh();
            var factory=context.getBean(TomcatServletWebServerFactory.class);factory.setBaseDirectory(Files.createTempDirectory(Path.of(args[0]),"factory-registration-").toFile());
            var server=factory.getWebServer(c->{var s=c.addServlet("unit",new HttpServlet(){protected void doPost(HttpServletRequest q,HttpServletResponse r){throw new AssertionError("unguarded startup");}});s.addMapping("/*");});
            try{server.start();var result=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getPort()+"/auth/login")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());if(result.statusCode()!=503)throw new AssertionError("Factory BPP missed the startup gate");}finally{server.stop();server.destroy();}
        }
        var values=new HashMap<String,Object>();values.put("spring.profiles.active","dev");values.put("server.port","8081");values.put("spring.application.name","identity-service");values.put("identity.bigquery.enabled","true");values.put("identity.bigquery.production-mutations-enabled","true");values.put("identity.bigquery.project-id","king-of-cards-509610");values.put("identity.bigquery.dataset","shared_rds_data");values.put("identity.bigquery.location","asia-south1");
        var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("fixture",values));env.setActiveProfiles("dev");RuntimeControl.validateEnvironment(env,new Properties());int checks=2;
        for(String key:List.of("server.port","spring.application.name","identity.bigquery.enabled","identity.bigquery.production-mutations-enabled","identity.bigquery.project-id","identity.bigquery.dataset","identity.bigquery.location","spring.profiles.active")){
            Object original=values.put(key,"wrong");try{RuntimeControl.validateEnvironment(env,new Properties());throw new AssertionError("Unsafe startup config accepted: "+key);}catch(IllegalStateException expected){checks++;}finally{values.put(key,original);}
        }
        env.setActiveProfiles("dev","other");try{RuntimeControl.validateEnvironment(env,new Properties());throw new AssertionError("Extra profile accepted");}catch(IllegalStateException expected){checks++;}
        System.out.println("STARTUP_REGISTRATION_PASS checks="+checks+"; real Boot customizer postprocessor, fixture port only");
    }
}
