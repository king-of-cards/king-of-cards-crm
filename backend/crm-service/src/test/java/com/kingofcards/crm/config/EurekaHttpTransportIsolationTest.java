package com.kingofcards.crm.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerAutoConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class EurekaHttpTransportIsolationTest {
    private final LoadBalancerClient loadBalancer = mock(LoadBalancerClient.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(LeadApiConfig.class)
        .withConfiguration(AutoConfigurations.of(LoadBalancerAutoConfiguration.class))
        .withBean(LoadBalancerClient.class, () -> loadBalancer)
        .withPropertyValues("spring.cloud.loadbalancer.retry.enabled=false");

    @Test void eurekaProviderSelectsDirectPrototypeBuilder() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Exact unqualified lookup used by installed Netflix 5.0.3 optional-args configuration.
            var builder = context.getBeanProvider(RestClient.Builder.class).getIfAvailable();
            assertThat(builder).isNotNull();
            assertThat(context.getBeanFactory().getBeanDefinition("directHttpRestClientBuilder").isPrimary()).isTrue();
            assertThat(context.findAnnotationOnBean("directHttpRestClientBuilder", LoadBalanced.class)).isNull();
            assertThat(builder).isNotSameAs(context.getBeanProvider(RestClient.Builder.class).getIfAvailable());
        });
    }

    @Test void physicalEurekaUrlDoesNotEnterServiceDiscovery() throws Exception {
        when(loadBalancer.execute(eq("localhost"), any())).thenThrow(new IllegalStateException("Physical Eureka URL incorrectly entered service discovery"));
        runner.run(context -> {
            var builder = context.getBeanProvider(RestClient.Builder.class).getIfAvailable();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://localhost:8761/eureka/apps")).andRespond(withSuccess());
            builder.build().get().uri("http://localhost:8761/eureka/apps").retrieve().toBodilessEntity();
            server.verify();
            verifyNoInteractions(loadBalancer);
        });
    }

    @Test void identityBuilderRetainsLoadBalancing() throws Exception {
        when(loadBalancer.execute(eq("identity-service"), any())).thenThrow(new IllegalStateException("synthetic-load-balancer-call"));
        runner.run(context -> {
            var builder = context.getBean("identityVendorRestClientBuilder", RestClient.Builder.class);
            assertThat(context.findAnnotationOnBean("identityVendorRestClientBuilder", LoadBalanced.class)).isNotNull();
            assertThatThrownBy(() -> builder.build().get().uri("http://identity-service/admin/vendors/test-subject").retrieve().toBodilessEntity())
                .hasMessageContaining("synthetic-load-balancer-call");
            verify(loadBalancer).execute(eq("identity-service"), any());
            verifyNoMoreInteractions(loadBalancer);
        });
    }
}
