package com.kingofcards.identity.config;
import org.springframework.security.oauth2.jwt.Jwt;
@FunctionalInterface
public interface AccountStatusVerifier { void verify(Jwt jwt); }
