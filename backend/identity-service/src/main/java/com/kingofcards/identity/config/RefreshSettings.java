package com.kingofcards.identity.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.refresh")
public record RefreshSettings(Duration tokenTtl) {}