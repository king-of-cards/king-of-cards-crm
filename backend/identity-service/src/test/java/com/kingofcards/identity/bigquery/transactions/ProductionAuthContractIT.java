package com.kingofcards.identity.bigquery.transactions;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.*;
import com.google.cloud.http.HttpTransportOptions;
import com.google.gson.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.kingofcards.identity.bigquery.*;
import com.kingofcards.identity.bigquery.production.*;
import com.kingofcards.identity.config.*;
import com.kingofcards.identity.entity.*;
import com.kingofcards.identity.exception.*;
import com.kingofcards.identity.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;

/** Explicit LIVE contract: standalone production adapter, synthetic accounts only.
 * Not a runtime activation flag, not a browser/HTTP test, no cleanup or real provisioning.
 */
class ProductionAuthContractIT {
    @Test void persistedAdminAndVendorAuthenticationLifecycle() throws Exception {
        if(!"ALLOW_PRODUCTION_ADAPTER_SYNTHETIC_CONTRACT_V1".equals(System.getenv("KOC_PRODUCTION_CONTRACT_APPROVAL")))
            throw new IllegalStateException("Explicit production adapter synthetic-contract approval required before ADC.");
        String run=AuthVerificationContinuation.RUN;
        var manifestPath=Path.of(Objects.requireNonNull(System.getenv("KOC_PRODUCTION_CONTRACT_MANIFEST")));
        byte[] bytes=Files.readAllBytes(manifestPath);
        assertEquals("2893649013cef21feaad3f312277efdd7178c291582e242a0108b2d851242283",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var manifest=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(run,manifest.get("runId").getAsString());
        assertEquals(16,manifest.getAsJsonArray("users").size());assertEquals(18,manifest.getAsJsonArray("sessions").size());
        var directory=Path.of("target","production-auth-contract-v2",run);Files.createDirectories(directory);
        Files.writeString(directory.resolve("started.marker"),"Single synthetic contract execution",StandardOpenOption.CREATE_NEW);
        var used=new AtomicInteger(97); // previous attempt's two reads and confirmed failed budget-limited job included
        var delegate=BigQueryOptions.newBuilder().setProjectId(IsolatedAuthTarget.PROJECT).setLocation(IsolatedAuthTarget.LOCATION)
            .setCredentials(GoogleCredentials.getApplicationDefault())
            .setTransportOptions(HttpTransportOptions.newBuilder().setConnectTimeout(10000).setReadTimeout(20000).build())
            .setRetrySettings(BigQueryOptions.getDefaultRetrySettings().toBuilder().setMaxAttempts(1).build()).build().getService();
        BigQuery client=(BigQuery)Proxy.newProxyInstance(BigQuery.class.getClassLoader(),new Class<?>[]{BigQuery.class},(proxy,method,args)->{
            if(method.getName().equals("create") || method.getName().equals("query")) {
                if(used.incrementAndGet()>150)throw new IllegalStateException("Contract job budget exhausted");
                QueryJobConfiguration query;JobId job;
                if(method.getName().equals("create")){
                    if(!(args[0] instanceof JobInfo info) || !(info.getConfiguration() instanceof QueryJobConfiguration config))
                        throw new IllegalArgumentException("Only allowlisted query jobs are permitted");
                    query=config;job=info.getJobId();
                } else {query=(QueryJobConfiguration)args[0];job=(JobId)args[1];}
                if(!IsolatedAuthTarget.PROJECT.equals(job.getProject()) || !IsolatedAuthTarget.LOCATION.equals(job.getLocation())
                    || query.getMaximumBytesBilled()==null || query.getMaximumBytesBilled()>536870912L || !Boolean.FALSE.equals(query.useLegacySql()))
                    throw new IllegalArgumentException("Unapproved job target or controls");
                var references=java.util.regex.Pattern.compile("`([^`]+)`").matcher(query.getQuery());boolean found=false;
                while(references.find()){found=true;if(!Set.of("king-of-cards-509610.shared_rds_data.identity_user",
                    "king-of-cards-509610.shared_rds_data.identity_refresh_session").contains(references.group(1)))throw new IllegalArgumentException("Unapproved table reference");}
                if(!found)throw new IllegalArgumentException("Missing Auth target");
                var parameters=query.getNamedParameters();
                String operation=parameters.containsKey("operation")?parameters.get("operation").getValue():"READ";
                if(method.getName().equals("create") && parameters.containsKey("email") && !parameters.get("email").getValue().isEmpty()
                    && !parameters.get("email").getValue().startsWith("it+"+run+"-production-"))throw new IllegalArgumentException("Not a contract synthetic account");
                String receipt="job="+job.getJob()+"\noperation="+operation+"\n";
                for(String key:List.of("id","new_id","family_id"))if(parameters.containsKey(key))receipt+=key+"="+parameters.get(key).getValue()+"\n";
                Files.writeString(directory.resolve(job.getJob()+".receipt"),receipt,StandardOpenOption.CREATE_NEW);
            }
            try{return method.invoke(delegate,args);}catch(InvocationTargetException exception){throw exception.getCause();}
        });
        var proposed=new ArrayList<Map<String,String>>();
        try {
            AuthVerificationContinuation.verify(client,manifest);
            var properties=new IdentityBigQueryProperties(true,IsolatedAuthTarget.PROJECT,IsolatedAuthTarget.DATASET,IsolatedAuthTarget.LOCATION);
            new IdentityBigQueryValidator(client,properties).validate();
            var clock=Clock.systemUTC();var encoder=new BCryptPasswordEncoder(12);
            var store=new ProductionIdentityStore(new BigQueryIdentityStore(()->client,properties),new ProductionAuthAdapter(client),clock);
            Path keyPath=Path.of(Objects.requireNonNull(System.getenv("JWT_PRIVATE_KEY_LOCATION")));
            String pem=Files.readString(keyPath);
            var keyFactory=KeyFactory.getInstance("RSA");
            var privateKey=(RSAPrivateCrtKey)keyFactory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----",""))));
            var publicKey=(RSAPublicKey)keyFactory.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(),privateKey.getPublicExponent()));
            var jwk=new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("synthetic-contract").build();
            var settings=new JwtSettings("http://localhost:8081","king-of-cards-crm",Duration.ofMinutes(5),keyPath.toString());
            var jwtTokens=new JwtTokenService(new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk))),settings,clock,new JWKSet(jwk.toPublicJWK()));
            var decoder=NimbusJwtDecoder.withPublicKey(publicKey).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(settings.issuer()));
            var service=new AuthenticationService(store,encoder,jwtTokens,new RefreshTokenService(store,new RefreshSettings(Duration.ofHours(1)),clock));
            for(String role:List.of("CRM_ADMIN","CRM_VENDOR")) {
                String email="it+"+run+"-production-"+UUID.randomUUID()+"@example.test";
                String password=UUID.randomUUID()+UUID.randomUUID().toString().replace("-","");
                var account=new IdentityUser(email,email,encoder.encode(password),true);account.setDisplayName("Synthetic production contract");
                account.addRole(new IdentityRole(role,role));proposed.add(Map.of("id",account.getId().toString(),"email",email,"role",role));
                Files.writeString(directory.resolve("proposed-users.json"),new GsonBuilder().setPrettyPrinting().create().toJson(proposed));
                store.createUnique(account);
                assertThrows(BadCredentialsException.class,()->service.login(email,"deliberately-incorrect"));
                var login=service.login(email,password);var jwt=decoder.decode(login.accessToken());
                assertEquals(account.getId().toString(),jwt.getSubject());assertEquals(List.of(role),jwt.getClaimAsStringList("roles"));
                assertTrue(jwt.getAudience().contains(settings.audience()));
                assertEquals(account.getId(),service.currentUser(account.getId()).id());
                var refreshed=service.refresh(login.refreshToken(),login.csrfToken(),login.csrfToken());
                assertEquals(account.getId().toString(),decoder.decode(refreshed.accessToken()).getSubject());
                service.logout(refreshed.refreshToken(),refreshed.csrfToken(),refreshed.csrfToken());
                assertThrows(RefreshTokenReuseException.class,()->service.refresh(refreshed.refreshToken(),refreshed.csrfToken(),refreshed.csrfToken()));
                var current=store.findById(account.getId()).orElseThrow();
                var disabled=IdentityUser.restore(current.getId(),current.getEmail(),current.getDisplayName(),current.getPasswordHash(),role,false,current.getCreatedAt(),current.getUpdatedAt());
                store.save(disabled);assertThrows(BadCredentialsException.class,()->service.login(email,password));
                System.out.println("Persisted synthetic "+role+": BCrypt login, RS256, current user, refresh, logout, replay and disabled-account checks passed; not an HTTP/browser test.");
            }
            Files.writeString(directory.resolve("passed.marker"),"Both synthetic roles verified; runtime activation remains disabled",StandardOpenOption.CREATE_NEW);
        } finally {Files.writeString(directory.resolve("budget.txt"),"cumulative_submissions="+used.get()+"\n");}
    }
}
