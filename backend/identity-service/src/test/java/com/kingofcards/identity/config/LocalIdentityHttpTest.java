package com.kingofcards.identity.config;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.google.cloud.bigquery.BigQuery;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Actual loopback HTTP + BCrypt + RS256 + refresh cookies. No mocked authentication,
 * no ADC and no cloud persistence. Test-only ephemeral key is removed after the test. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"KOC_LOCAL_ADMIN_PASSWORD=offline-admin-fixture-only","KOC_LOCAL_VENDOR_PASSWORD=offline-vendor-fixture-only"})
@ActiveProfiles("lead-dashboard-local")
class LocalIdentityHttpTest {
    static final Path key=createTestKey();
    static Path createTestKey(){try{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();var path=Files.createTempFile("koc-local-http-test-",".pem");Files.writeString(path,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");return path;}catch(Exception e){throw new IllegalStateException("Cannot create isolated test key",e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("identity.jwt.private-key-location",()->key.toString());}
    @AfterAll static void removeOnlyGeneratedTestKey() throws Exception{Files.deleteIfExists(key);}
    @LocalServerPort int port;
    @Autowired ApplicationContext context;
    final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final ObjectMapper json=new ObjectMapper();
    HttpResponse<String> send(String method,String path,String body,String bearer,String cookie,String csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin","http://127.0.0.1:15173");
        if(bearer!=null)request.header("Authorization","Bearer "+bearer);if(cookie!=null)request.header("Cookie",cookie);if(csrf!=null)request.header("X-XSRF-TOKEN",csrf);
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    Map<String,String> cookies(HttpResponse<String> response){var result=new HashMap<String,String>();for(String cookie:response.headers().allValues("set-cookie")){String pair=cookie.split(";",2)[0];int at=pair.indexOf('=');result.put(pair.substring(0,at),pair.substring(at+1));}return result;}
    String header(Map<String,String> cookies){return "KOC_REFRESH="+cookies.get("KOC_REFRESH")+"; XSRF-TOKEN="+cookies.get("XSRF-TOKEN");}
    @Test void realLocalLoginMeVendorProfileRefreshLogoutAndDeniedAccess() throws Exception {
        assertThat(context.getBeansOfType(BigQuery.class)).isEmpty();
        assertThat(send("GET","/actuator/health",null,null,null,null).statusCode()).isEqualTo(200);
        assertThat(send("GET","/auth/me",null,null,null,null).statusCode()).isEqualTo(401);
        var bad=send("POST","/auth/login","{\"email\":\"local-admin@example.test\",\"password\":\"incorrect-password\"}",null,null,null);assertThat(bad.statusCode()).isEqualTo(401);
        for(String kind:List.of("admin","vendor")){
            var login=send("POST","/auth/login","{\"email\":\"local-"+kind+"@example.test\",\"password\":\"offline-"+kind+"-fixture-only\"}",null,null,null);assertThat(login.statusCode()).isEqualTo(200);
            String access=json.readTree(login.body()).get("accessToken").asString();
            var me=send("GET","/auth/me",null,access,null,null);assertThat(me.statusCode()).isEqualTo(200);assertThat(json.readTree(me.body()).get("roles").get(0).asString()).isEqualTo(kind.equals("admin")?"CRM_ADMIN":"CRM_VENDOR");
            assertThat(send("GET","/auth/vendor-profile",null,access,null,null).statusCode()).isEqualTo(kind.equals("vendor")?200:403);
            assertThat(send("GET","/admin/vendors/accounts",null,access,null,null).statusCode()).isEqualTo(kind.equals("admin")?200:403);
            var old=cookies(login);assertThat(old).containsKeys("KOC_REFRESH","XSRF-TOKEN");
            assertThat(send("POST","/auth/refresh",null,null,header(old),"incorrect").statusCode()).isEqualTo(403);
            var refreshed=send("POST","/auth/refresh",null,null,header(old),old.get("XSRF-TOKEN"));assertThat(refreshed.statusCode()).isEqualTo(200);
            var next=cookies(refreshed);assertThat(send("POST","/auth/logout",null,null,header(next),next.get("XSRF-TOKEN")).statusCode()).isEqualTo(204);
            assertThat(send("POST","/auth/refresh",null,null,header(next),next.get("XSRF-TOKEN")).statusCode()).isEqualTo(401);
        }
    }
}
