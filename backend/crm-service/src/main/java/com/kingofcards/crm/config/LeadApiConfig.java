package com.kingofcards.crm.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import com.google.cloud.bigquery.BigQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import com.kingofcards.crm.repository.BigQueryLeadRepository;
import com.kingofcards.crm.repository.LeadRepository;
import com.kingofcards.crm.service.IdentityVendorDirectory;
import com.kingofcards.crm.service.VendorDirectory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BigQueryProperties.class)
public class LeadApiConfig {
    @Bean Clock crmLeadClock() { return Clock.systemUTC(); }

    @Bean LeadRepository leadRepository(ObjectProvider<BigQuery> clients, BigQueryProperties properties) {
        return new BigQueryLeadRepository(clients::getIfAvailable, properties);
    }

    @Bean
    @Primary
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder directHttpRestClientBuilder() {
        // Eureka 5.0.x performs an unqualified builder lookup for physical registry URLs.
        // It must not inherit the Identity client's service-discovery interceptor.
        return RestClient.builder();
    }

    @Bean
    @LoadBalanced
    @org.springframework.context.annotation.Profile("!lead-dashboard-local")
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder identityVendorRestClientBuilder() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requests);
    }

    @Bean(name="identityVendorRestClientBuilder")
    @org.springframework.context.annotation.Profile("lead-dashboard-local")
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder localIdentityRestClientBuilder() {
        return identityVendorRestClientBuilder();
    }

    @Bean VendorDirectory vendorDirectory(@Qualifier("identityVendorRestClientBuilder") RestClient.Builder builder,@org.springframework.beans.factory.annotation.Value("${crm.identity.base-url:http://identity-service}")String base) {
        return new IdentityVendorDirectory(builder.build(),base);
    }
    @Bean AccountStatusVerifier crmAccountStatusVerifier(@Qualifier("identityVendorRestClientBuilder") RestClient.Builder builder,@org.springframework.beans.factory.annotation.Value("${crm.identity.base-url:http://identity-service}")String base) {
        return new IdentityAccountStatusVerifier(builder.build(),base);
    }
}
