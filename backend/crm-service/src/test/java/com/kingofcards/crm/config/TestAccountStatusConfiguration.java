package com.kingofcards.crm.config;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** Synthetic current-account fixture; real lookup/filter behavior is tested separately. */
@TestConfiguration
public class TestAccountStatusConfiguration {
    @Bean @Primary AccountStatusVerifier syntheticCurrentAccountVerifier(){return jwt->{};}
}
