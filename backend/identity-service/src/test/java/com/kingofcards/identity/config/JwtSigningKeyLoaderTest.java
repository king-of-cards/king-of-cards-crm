package com.kingofcards.identity.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtSigningKeyLoaderTest {
    @Test void loadsPkcs8RsaPrivatePemAndDerivesMatchingPublicKeyWithoutNetwork() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair=generator.generateKeyPair();
        Path privatePem=Files.createTempFile("koc-identity-private-", ".pem");
        try {
            String body=Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded());
            Files.writeString(privatePem,"-----BEGIN PRIVATE KEY-----\n"+body+"\n-----END PRIVATE KEY-----\n",StandardCharsets.US_ASCII);
            var settings=new JwtSettings("http://localhost:8081","king-of-cards-crm",Duration.ofMinutes(15),privatePem.toString());
            var material=new JwtConfig().rsaSigningMaterial(settings);
            assertArrayEquals(pair.getPublic().getEncoded(),material.publicKey().getEncoded());
            assertEquals(2048,material.publicKey().getModulus().bitLength());
            assertNotNull(material.jwk().getKeyID());
            assertFalse(material.toString().contains("PRIVATE KEY"));
        } finally { Files.deleteIfExists(privatePem); }
    }

    @Test void rejectsUnresolvedPlaceholderInsteadOfTreatingItAsAKeyFilename() {
        var settings=new JwtSettings("http://localhost:8081","king-of-cards-crm",Duration.ofMinutes(15),"${JWT_PRIVATE_KEY_LOCATION}");
        Exception exception=assertThrows(Exception.class,()->new JwtConfig().rsaSigningMaterial(settings));
        assertFalse(exception.toString().contains("PRIVATE KEY"));
    }
}
