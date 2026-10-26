package com.kingofcards.crm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crm.jwt")
public record CrmJwtSettings(String issuer, String audience, String jwkSetUri) {}