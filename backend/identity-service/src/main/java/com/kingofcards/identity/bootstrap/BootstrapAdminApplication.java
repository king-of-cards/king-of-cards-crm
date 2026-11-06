package com.kingofcards.identity.bootstrap;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import com.kingofcards.identity.bigquery.IdentityBigQueryConfiguration;
import com.kingofcards.identity.config.PasswordConfig;
import java.util.Arrays;
import com.kingofcards.identity.service.AdminBootstrapService;

public final class BootstrapAdminApplication {
    private BootstrapAdminApplication() {}

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("Bootstrap accepts no command-line arguments. Use environment configuration.");
        var console = System.console();
        if (console == null) throw new IllegalStateException("Run bootstrap from an interactive Java console with hidden password input.");
        String email = System.getenv("IDENTITY_BOOTSTRAP_EMAIL");
        if (email == null || email.isBlank()) email = console.readLine("First administrator email: ");
        String name = console.readLine("First administrator name: ");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Administrator name is required.");
        char[] password = console.readPassword("First administrator password: ");
        char[] confirmation = console.readPassword("Confirm password: ");
        try {
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation))
                throw new IllegalArgumentException("Passwords must match.");
            bootstrap(email, new String(password), name);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    static void bootstrap(String email, String password) {
        bootstrap(email, password, null);
    }
    static void bootstrap(String email, String password, String name) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BootstrapConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties("eureka.client.enabled=false", "spring.cloud.discovery.enabled=false")
                .run()) {
            var service = context.getBean(AdminBootstrapService.class);
            if (name == null) service.createFirstAdministrator(email, password);
            else service.createFirstAdministrator(email, password, name);
            System.out.println("Initial administrator created. This command is one-time and requires an empty user store.");
        }
    }

    // No web security beans, JWT signing material, discovery client or account auto-runner.
    @Import({IdentityBigQueryConfiguration.class, PasswordConfig.class, AdminBootstrapService.class})
    static class BootstrapConfiguration {}
}
