package com.kingofcards.identity.maintenance;

import org.springframework.context.*;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/** Loaded by PropertiesLauncher BEFORE Tomcat construction, never after its connector opens. */
public final class IdentityMaintenanceInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public static final class StartupCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
        private final IdentityGate gate;
        public StartupCustomizer(IdentityGate gate){this.gate=gate;}
        public void customize(TomcatServletWebServerFactory factory){factory.addContextValves(gate);}
    }
    public void initialize(ConfigurableApplicationContext context) {
        try {
            RuntimeControl.validateStartup(context.getEnvironment());
            var gate=new IdentityGate();
            context.getBeanFactory().registerSingleton("kocIdentityMaintenanceCustomizer",new StartupCustomizer(gate));
            context.addApplicationListener(event->{if(event instanceof ApplicationReadyEvent ready&&ready.getApplicationContext()==context){
                try{RuntimeControl.start(context,gate);}catch(Exception e){throw new IllegalStateException("Identity maintenance startup check failed; admission remains closed",e);}
            }});
        }catch(Exception e){throw new IllegalStateException("Refusing unguarded Identity startup",e);}
    }
}
