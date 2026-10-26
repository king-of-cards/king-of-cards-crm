package com.kingofcards.identity.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.nimbusds.jose.jwk.JWKSet;

@RestController
public class JwkSetController {
    private final JWKSet publicJwkSet;

    public JwkSetController(JWKSet publicJwkSet) {
        this.publicJwkSet = publicJwkSet;
    }

    @GetMapping("/auth/jwks")
    public Map<String, Object> keys() {
        return publicJwkSet.toJSONObject();
    }
}