package com.kingofcards.identity.config;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Synthetic current-account fixture for MVC tests of other contracts. No runtime bypass. */
@TestConfiguration
public class TestAccountStatusConfiguration {
    @Bean @Primary AccountStatusVerifier syntheticCurrentAccountVerifier(){return jwt->{};}
}
