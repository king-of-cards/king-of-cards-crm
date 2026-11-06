package com.kingofcards.crm.directional;
import java.time.Clock;
import com.google.cloud.bigquery.BigQuery;
import com.kingofcards.crm.config.BigQueryProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(DirectionalProperties.class)
public class DirectionalConfig {
    @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor rejectLocalProductionMix(Environment environment){return factory->{
        if(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("lead-dashboard-local")) &&
            (environment.getProperty("crm.bigquery.enabled",Boolean.class,false) || environment.getProperty("crm.bigquery.writes-enabled",Boolean.class,false) || environment.getProperty("crm.bigquery.schema-auto-create",Boolean.class,false)
            || environment.getProperty("eureka.client.enabled",Boolean.class,true) || !"127.0.0.1".equals(environment.getProperty("server.address"))
            || !"urn:koc:lead-dashboard-local".equals(environment.getProperty("crm.jwt.issuer")) || !"koc-lead-dashboard-local".equals(environment.getProperty("crm.jwt.audience"))))throw new IllegalStateException("Refusing production/local CRM mixing before any SDK client is created.");
    };}
    @Bean DirectionalRepository directionalRepository(ObjectProvider<BigQuery> clients,BigQueryProperties bigquery,DirectionalProperties properties,Clock clock,Environment environment){
        if(environment.acceptsProfiles(org.springframework.core.env.Profiles.of("lead-dashboard-local"))){
            if(bigquery.enabled() || bigquery.writesEnabled() || !"127.0.0.1".equals(environment.getProperty("server.address"))
                || !"urn:koc:lead-dashboard-local".equals(environment.getProperty("crm.jwt.issuer")))throw new IllegalStateException("Local dashboard requires isolated loopback settings and BigQuery disabled.");
            return new LocalDirectionalRepository(clock);
        }
        return new BigQueryDirectionalRepository(clients::getIfAvailable,bigquery,properties,clock);
    }
    @Bean DashboardIdentity dashboardIdentity(@Qualifier("identityVendorRestClientBuilder") RestClient.Builder builder,@Value("${crm.identity.base-url:http://identity-service}")String base){
        if(!base.equals("http://identity-service") && !base.equals("http://127.0.0.1:18081"))throw new IllegalArgumentException("Identity endpoint is not allowlisted.");
        return new IdentityDashboardDirectory(builder.build(),base);
    }
    @Bean @org.springframework.core.annotation.Order(-100)
    ApplicationRunner directionalSchemaValidation(ObjectProvider<BigQuery> clients,BigQueryProperties bigquery,DirectionalProperties properties,Environment environment,@Value("${crm.bigquery.schema-auto-create:false}")boolean autoCreate){return args->{
        if(autoCreate){
            if(!properties.enabled() || !bigquery.enabled() || environment.acceptsProfiles(org.springframework.core.env.Profiles.of("lead-dashboard-local")))throw new IllegalStateException("Schema auto-create requires explicitly enabled real BigQuery and directional mode; never local test storage.");
            var client=clients.getIfAvailable();if(client==null)throw new IllegalStateException("Approved BigQuery client is unavailable; no schema changes attempted.");
            new DirectionalSchemaInitializer(client,bigquery).initialize();
            return;
        }
        if(properties.enabled() && !environment.acceptsProfiles(org.springframework.core.env.Profiles.of("lead-dashboard-local"))){
            var client=clients.getIfAvailable();if(!bigquery.enabled() || client==null)throw new IllegalStateException("Directional BigQuery reads require crm.bigquery.enabled and approved ADC.");DirectionalSchema.validate(client,bigquery);
        }
    };}
}
