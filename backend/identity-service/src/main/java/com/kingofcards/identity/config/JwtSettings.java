package com.kingofcards.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.jwt")
public record JwtSettings(String issuer, String audience, Duration accessTokenTtl,
                          String privateKeyLocation) {}