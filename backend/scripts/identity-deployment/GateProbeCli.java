package com.kingofcards.identity.maintenance;
import java.net.*;import java.net.http.*;import java.nio.file.*;import java.time.*;import java.util.*;
/** ONLY after the running controller proves MUTATIONS_CLOSED. Empty auth requests, never credentials. */
public final class GateProbeCli {
    public static void main(String[]args)throws Exception {
        Safety.require(args.length==3,"Bundle, hash and nonce-bound live status required");var b=RuntimeControl.verifyBundle(Path.of(args[0]),args[1]);var status=Safety.load(Path.of(args[2]));
        Safety.require("MUTATIONS_CLOSED".equals(status.getProperty("gateMode"))&&b.getProperty("helper.sha256").equals(status.getProperty("helperSha256"))&&Duration.between(Instant.parse(status.getProperty("observedUtc")),Instant.now()).abs().compareTo(Duration.ofSeconds(10))<0,"Fresh live closed-admission evidence required before probing");
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,java.io.IOException e){}}).build();
        for(String prefix:List.of("http://127.0.0.1:8081","http://127.0.0.1:8080/api/identity"))for(String path:List.of("/auth/login","/auth/refresh","/auth/logout")){
            var r=http.send(HttpRequest.newBuilder(URI.create(prefix+path)).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
            boolean fenced=r.statusCode()==503&&r.headers().firstValue("X-KOC-Identity-Admission").orElse("").equals("closed");
            boolean gatewayDenied=prefix.contains(":8080/")&&(r.statusCode()==401||r.statusCode()==403);
            Safety.require(fenced||gatewayDenied,"Authentication admission probe not fenced");
        }
        System.out.println("IDENTITY_DIRECT_AND_GATEWAY_MUTATION_PROBES_PASS; no credentials or real auth payloads submitted");
    }
}
