package com.kingofcards.identity.maintenance;

import java.net.*;import java.net.http.*;import java.nio.file.*;import java.nio.charset.*;import java.security.*;import java.security.spec.*;import java.time.*;import java.util.*;
import com.google.gson.*;

/** Public read checks and optional existing Vendor JWT GETs only. Never logs tokens or profiles. */
public final class VerifyHttpCli {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,java.io.IOException e){}}).build();
    private static JsonObject get(String url,String bearer)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Accept","application/json");if(bearer!=null)request.header("Authorization","Bearer "+bearer);
        var result=HTTP.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());Safety.require(result.statusCode()==200,"Read-only HTTP check failed status="+result.statusCode());return JsonParser.parseString(result.body()).getAsJsonObject();
    }
    public static void main(String[] args)throws Exception {
        Safety.require(args.length==4||(args.length==5&&args[4].equals("PRESTART_TOKEN_CHECK")),"Bundle, hash, new output, Vendor JWT file or NONE required");var b=RuntimeControl.verifyBundle(Path.of(args[0]),args[1]);var result=new Properties();
        var publicPem=Files.readString(Path.of(b.getProperty("publicKey.path"))).replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
        var key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicPem)));
        Safety.require("UP".equals(get("http://127.0.0.1:8081/actuator/health",null).get("status").getAsString()),"Identity health not UP");result.setProperty("health","PASS");
        Safety.require("UP".equals(get("http://127.0.0.1:8081/actuator/health/readiness",null).get("status").getAsString()),"Identity readiness not UP");result.setProperty("readiness","PASS");
        Safety.require("UP".equals(get("http://127.0.0.1:8080/api/identity/health",null).get("status").getAsString()),"Gateway health route not UP");result.setProperty("gateway","PASS");
        var registration=get("http://127.0.0.1:8761/eureka/apps/IDENTITY-SERVICE",null).getAsJsonObject("application").get("instance");
        var instances=registration.isJsonArray()?registration.getAsJsonArray():new JsonArray();if(!registration.isJsonArray())instances.add(registration);boolean up=false;
        for(var value:instances){var instance=value.getAsJsonObject();if("UP".equals(instance.get("status").getAsString())&&instance.getAsJsonObject("port").get("$").getAsInt()==8081)up=true;}
        Safety.require(up,"Identity Eureka registration not UP");result.setProperty("eureka","PASS");
        for(String url:List.of("http://127.0.0.1:8081/auth/jwks","http://127.0.0.1:8080/api/identity/auth/jwks")) {
            boolean matches=false;for(var value:get(url,null).getAsJsonArray("keys")){var jwk=value.getAsJsonObject();if(!"RSA".equals(jwk.get("kty").getAsString()))continue;
                var n=new java.math.BigInteger(1,Base64.getUrlDecoder().decode(jwk.get("n").getAsString()));var e=new java.math.BigInteger(1,Base64.getUrlDecoder().decode(jwk.get("e").getAsString()));
                matches|=Arrays.equals(key.getEncoded(),KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,e)).getEncoded());}
            Safety.require(matches,"Live JWKS changed");
        }result.setProperty("jwks","PASS");
        if(!args[3].equals("NONE")) {
            Path file=Path.of(args[3]).toRealPath();Safety.require(Files.size(file)<=16384,"Unexpected token file");String token=Files.readString(file).trim();var parts=token.split("\\.",-1);Safety.require(parts.length==3,"Existing JWT required");
            var header=JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8)).getAsJsonObject();Safety.require("RS256".equals(header.get("alg").getAsString()),"Unsupported token algorithm");
            var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));Safety.require(verifier.verify(Base64.getUrlDecoder().decode(parts[2])),"JWT signature differs");
            var claims=JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8)).getAsJsonObject();
            Safety.require(claims.get("exp").getAsLong()>Instant.now().getEpochSecond()&&(!claims.has("nbf")||claims.get("nbf").getAsLong()<=Instant.now().getEpochSecond()),"Existing Vendor JWT expired/not yet valid");
            Safety.require(Safety.textHash(claims.get("iss").getAsString()).equals(b.getProperty("expected.identity.jwt.issuer")),"JWT issuer differs");
            boolean audience=false;var aud=claims.get("aud");if(aud.isJsonArray()){for(var a:aud.getAsJsonArray())audience|=Safety.textHash(a.getAsString()).equals(b.getProperty("expected.identity.jwt.audience"));}else audience=Safety.textHash(aud.getAsString()).equals(b.getProperty("expected.identity.jwt.audience"));Safety.require(audience,"JWT audience differs");
            var roles=claims.getAsJsonArray("roles");Safety.require(roles.size()==1&&"CRM_VENDOR".equals(roles.get(0).getAsString()),"Existing Vendor JWT role required");
            String subject=UUID.fromString(claims.get("sub").getAsString()).toString();
            if(args.length==5){
                Safety.require(claims.get("exp").getAsLong()>Instant.now().plusSeconds(720).getEpochSecond(),"Existing Vendor JWT must have at least 12 minutes remaining before maintenance");
                get("http://127.0.0.1:8081/auth/me",token);get("http://127.0.0.1:8080/api/identity/auth/me",token);
                result.setProperty("existingVendorToken","PASS");result.setProperty("vendorProfile","NOT_TESTED_CURRENT_OLD_JAR");result.setProperty("observedUtc",Instant.now().toString());Safety.save(result,Path.of(args[2]));System.out.println("IDENTITY_EXISTING_VENDOR_TOKEN_PRECHECK_PASS; no login/refresh/session write");return;
            }
            for(String url:List.of("http://127.0.0.1:8081/auth/vendor-profile","http://127.0.0.1:8080/api/identity/auth/vendor-profile")){
                var profile=get(url,token);Safety.require(subject.equals(profile.get("subjectId").getAsString())&&profile.get("enabled").getAsBoolean()&&!profile.get("displayName").getAsString().isBlank()&&profile.getAsJsonArray("roles").size()==1&&"CRM_VENDOR".equals(profile.getAsJsonArray("roles").get(0).getAsString()),"Vendor profile binding differs");
            }result.setProperty("vendorProfile","PASS");
        }else result.setProperty("vendorProfile","BLOCKED_NO_EXISTING_VENDOR_TOKEN");
        result.setProperty("observedUtc",Instant.now().toString());Safety.save(result,Path.of(args[2]));System.out.println("IDENTITY_HTTP_READ_CHECKS_COMPLETE vendorProfile="+result.getProperty("vendorProfile"));
    }
}
