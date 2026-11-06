package com.kingofcards.gateway.config;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods=false)
@Profile("lead-dashboard-local")
public class LocalDashboardGuard {
    @Bean static BeanFactoryPostProcessor rejectProductionRoutes(Environment env){return factory->{
        String prefix="spring.cloud.gateway.server.webflux.routes";
        if(!"127.0.0.1".equals(env.getProperty("server.address")) || env.getProperty("eureka.client.enabled",Boolean.class,true)
            || !"urn:koc:lead-dashboard-local".equals(env.getProperty("gateway.jwt.issuer"))
            || !"koc-lead-dashboard-local".equals(env.getProperty("gateway.jwt.audience"))
            || !"http://127.0.0.1:18081".equals(env.getProperty(prefix+"[0].uri")) || !"http://127.0.0.1:18082".equals(env.getProperty(prefix+"[1].uri")) || env.getProperty(prefix+"[2].uri")!=null)
            throw new IllegalStateException("Local Gateway must use only isolated JWT claims and loopback local services.");
    };}
}
