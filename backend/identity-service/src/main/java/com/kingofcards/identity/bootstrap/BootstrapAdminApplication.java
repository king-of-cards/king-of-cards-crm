package com.kingofcards.identity.bootstrap;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import com.kingofcards.identity.IdentityServiceApplication;
import com.kingofcards.identity.service.AdminBootstrapService;

public final class BootstrapAdminApplication {
    private BootstrapAdminApplication() {}

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(IdentityServiceApplication.class)
                .web(WebApplicationType.NONE)
                .properties("eureka.client.enabled=false", "spring.cloud.discovery.enabled=false")
                .run(args)) {
            String email = context.getEnvironment().getRequiredProperty("IDENTITY_BOOTSTRAP_EMAIL");
            String password = context.getEnvironment().getRequiredProperty("IDENTITY_BOOTSTRAP_PASSWORD");
            context.getBean(AdminBootstrapService.class).createFirstAdministrator(email, password);
            System.out.println("Initial administrator created. This command is one-time and requires an empty user store.");
        }
    }
}