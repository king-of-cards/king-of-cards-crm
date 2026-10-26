package com.kingofcards.identity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity.cookie")
public record AuthCookieSettings(boolean secure, String sameSite, String allowedOrigin) {}