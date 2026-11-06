package com.kingofcards.crm.config;
import org.springframework.security.oauth2.jwt.Jwt;
@FunctionalInterface
public interface AccountStatusVerifier { void verify(Jwt jwt); }
