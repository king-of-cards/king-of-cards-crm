import java.net.*;import java.net.http.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;import java.security.*;import java.security.spec.*;
import com.google.auth.oauth2.*;import com.google.gson.*;

/** Metadata GET and aggregate SELECT only. Never prints credentials, row data or job parameters. */
class IdentityDeploymentReads {
 static final String REGION="asia-south1",PREFIX="koc_auth_runtime_";
 static final Set<String> ACTIONS=Set.of("BOOTSTRAP","CREATE_USER","UPDATE_ACCOUNT","CREATE_SESSION","ROTATE","REVOKE_TOKEN","REVOKE_FAMILY");
 static final Properties evidence=new Properties();static Path output;
 static void emit(String key,Object value){evidence.setProperty(key,String.valueOf(value));System.out.println(key+"="+value);}
 static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).toUpperCase(Locale.ROOT);}
 static void active()throws Exception{
  String token=null;int pages=0,listed=0,auth=0;var tokens=new HashSet<String>();
  do{
   String path="/jobs?allUsers=false&projection=minimal&maxResults=1000&stateFilter=pending&stateFilter=running&fields="+CommitReceiptInvestigation.enc("nextPageToken,jobs(jobReference,status)");
   if(token!=null)path+="&pageToken="+CommitReceiptInvestigation.enc(token);
   var page=CommitReceiptInvestigation.request("GET",path,null);pages++;
   if(page.has("jobs"))for(var item:page.getAsJsonArray("jobs")){
    listed++;var ref=item.getAsJsonObject().getAsJsonObject("jobReference");String id=ref.get("jobId").getAsString();
    if(id.startsWith(PREFIX)){
     auth++;String location=CommitReceiptInvestigation.val(ref,"location");if(!REGION.equalsIgnoreCase(location))throw new IllegalStateException("Unexpected auth-job region");
     emit("activeAuthJob."+auth+".id",id);emit("activeAuthJob."+auth+".state",item.getAsJsonObject().getAsJsonObject("status").get("state").getAsString());
    }
   }
   token=CommitReceiptInvestigation.val(page,"nextPageToken");if(token!=null&&!tokens.add(token))throw new IllegalStateException("Repeated metadata page token");
   if(pages>=10&&token!=null)throw new IllegalStateException("Active scan incomplete at safety bound");
  }while(token!=null);
  emit("activeOwnJobs.pages",pages);emit("activeOwnJobs.listed",listed);emit("activeOwnAuthJobs",auth);emit("activeOwnJobs.complete",true);
  emit("projectWideVisibilityClaimed",false);
 }
 static void recent(Instant start,Instant end)throws Exception{
  if(start.isAfter(end)||Duration.between(start,end).compareTo(Duration.ofHours(24))>0)throw new IllegalArgumentException("A bounded observed-QA window <=24h is required");
  emit("authWindow.startUtc",start);emit("authWindow.endUtc",end);String token=null;int pages=0,auth=0,conflicts=0,otherErrors=0;var tokens=new HashSet<String>();
  do{
   String path="/jobs?allUsers=false&projection=minimal&maxResults=1000&minCreationTime="+start.toEpochMilli()+"&maxCreationTime="+end.toEpochMilli()+"&fields="+CommitReceiptInvestigation.enc("nextPageToken,jobs(jobReference,status)");
   if(token!=null)path+="&pageToken="+CommitReceiptInvestigation.enc(token);
   var page=CommitReceiptInvestigation.request("GET",path,null);pages++;
   if(page.has("jobs"))for(var item:page.getAsJsonArray("jobs")){
    var ref=item.getAsJsonObject().getAsJsonObject("jobReference");String id=ref.get("jobId").getAsString();if(!id.startsWith(PREFIX))continue;
    if(auth>=60)throw new IllegalStateException("Auth metadata limit reached; narrow the window");
    String location=CommitReceiptInvestigation.val(ref,"location");if(!REGION.equalsIgnoreCase(location))throw new IllegalStateException("Unexpected auth region");
    var job=CommitReceiptInvestigation.request("GET","/jobs/"+CommitReceiptInvestigation.enc(id)+"?location="+REGION,null);
    var status=job.getAsJsonObject("status");String action="UNAVAILABLE";
    if(job.has("configuration")){
     var query=job.getAsJsonObject("configuration").getAsJsonObject("query");
     if(query!=null&&query.has("queryParameters"))for(var parameter:query.getAsJsonArray("queryParameters")){
      var p=parameter.getAsJsonObject();if("operation".equals(CommitReceiptInvestigation.val(p,"name"))){String candidate=CommitReceiptInvestigation.val(p.getAsJsonObject("parameterValue"),"value");if(ACTIONS.contains(candidate))action=candidate;}
     }
    }
    auth++;String outcome="DONE_NO_JOB_ERROR";if(!"DONE".equals(CommitReceiptInvestigation.val(status,"state")))outcome="NONTERMINAL";
    if(status.has("errorResult")){
     var error=status.getAsJsonObject("errorResult");String reason=CommitReceiptInvestigation.val(error,"reason"),message=CommitReceiptInvestigation.val(error,"message");
     if("concurrentUpdate".equals(reason)||(message!=null&&message.contains("Transaction is aborted due to concurrent update"))){conflicts++;outcome="CONFIRMED_TRANSACTION_ABORT";}
     else {otherErrors++;outcome="OTHER_ERROR_REVIEW_REQUIRED";}
    }
    emit("authJob."+auth+".id",id);emit("authJob."+auth+".action",action);emit("authJob."+auth+".outcome",outcome);
    String email=CommitReceiptInvestigation.val(job,"user_email");if(email!=null)emit("authJob."+auth+".configuredPrincipalMatches",sha(email.getBytes(StandardCharsets.UTF_8)).equals(evidence.getProperty("configuredPrincipalSha256")));
   }
   token=CommitReceiptInvestigation.val(page,"nextPageToken");if(token!=null&&!tokens.add(token))throw new IllegalStateException("Repeated page token");if(pages>=10&&token!=null)throw new IllegalStateException("Bounded history pagination incomplete");
  }while(token!=null);
  emit("recentAuth.pages",pages);emit("recentAuth.complete",true);emit("recentAuth.jobs",auth);emit("recentAuth.confirmedAborts",conflicts);emit("recentAuth.otherErrors",otherErrors);
  emit("httpCookieDeliveryProvenByJobMetadata",false);
 }
 static Map<String,String> snapshot()throws Exception{
  String prefix="`king-of-cards-509610.shared_rds_data.";
  String sql="SELECT (SELECT COUNT(*) FROM "+prefix+"identity_user`) AS accounts,"
   +"(SELECT COUNT(*) FROM "+prefix+"identity_refresh_session`) AS sessions,"
   +"(SELECT COUNT(*) FROM "+prefix+"identity_refresh_session` s LEFT JOIN "+prefix+"identity_user` u ON u.id=s.user_id WHERE u.id IS NULL) AS orphan_sessions,"
   +"(SELECT TO_HEX(SHA256(COALESCE(STRING_AGG(h,'' ORDER BY h),''))) FROM (SELECT TO_HEX(SHA256(TO_JSON_STRING(t))) AS h FROM "+prefix+"identity_user` t)) AS account_digest,"
   +"(SELECT TO_HEX(SHA256(COALESCE(STRING_AGG(h,'' ORDER BY h),''))) FROM (SELECT TO_HEX(SHA256(TO_JSON_STRING(t))) AS h FROM "+prefix+"identity_refresh_session` t)) AS session_digest";
  var rows=CommitReceiptInvestigation.query(sql);if(rows.size()!=1)throw new IllegalStateException("Aggregate result incomplete");return rows.getFirst();
 }
 static void jwks(Path publicKey)throws Exception{
  String pem=Files.readString(publicKey).replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
  var saved=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
  var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,java.io.IOException e){}}).build();
  for(String url:List.of("http://localhost:8081/auth/jwks","http://localhost:8080/api/identity/auth/jwks")){
   var response=client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException("Public JWKS unavailable");
   var keys=JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("keys");boolean found=false;
   for(var item:keys){var key=item.getAsJsonObject();if(!"RSA".equals(CommitReceiptInvestigation.val(key,"kty")))continue;var n=new java.math.BigInteger(1,Base64.getUrlDecoder().decode(key.get("n").getAsString()));var e=new java.math.BigInteger(1,Base64.getUrlDecoder().decode(key.get("e").getAsString()));var active=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n,e));if(Arrays.equals(active.getEncoded(),saved.getEncoded()))found=true;}
   if(!found)throw new IllegalStateException("Live signing key differs from saved Gateway public key");
  }
  emit("liveIdentityAndGatewayJwksMatchSavedKey",true);emit("publicKeySha256",sha(saved.getEncoded()));
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=4)throw new IllegalArgumentException("New evidence directory, observed start/end UTC and saved public key required");
  output=Path.of(args[0]).toAbsolutePath().normalize();Path root=Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev").toRealPath();if(!output.startsWith(root)||Files.exists(output))throw new IllegalArgumentException("New workspace evidence directory required");Files.createDirectory(output);
  evidence.setProperty("deploymentAuthorized","false");evidence.setProperty("liveAdmissionClosureProven","false");
  try{
   var credentials=GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/bigquery");if(!(credentials instanceof ServiceAccountCredentials account))throw new IllegalStateException("Expected configured service-account credential");CommitReceiptInvestigation.credentials=credentials;
   emit("configuredPrincipalSha256",sha(account.getClientEmail().getBytes(StandardCharsets.UTF_8)));
   if(!REGION.equalsIgnoreCase(CommitReceiptInvestigation.request("GET","/datasets/shared_rds_data",null).get("location").getAsString()))throw new IllegalStateException("Dataset region mismatch");
   for(String table:List.of("identity_user","identity_refresh_session")){var metadata=CommitReceiptInvestigation.request("GET","/datasets/shared_rds_data/tables/"+table,null);emit("schemaSha256."+table,sha(metadata.getAsJsonObject("schema").toString().getBytes(StandardCharsets.UTF_8)));}
   jwks(Path.of(args[3]));var before=snapshot();for(var entry:before.entrySet())emit("before."+entry.getKey(),entry.getValue());
   active();recent(Instant.parse(args[1]),Instant.parse(args[2]));var after=snapshot();for(var entry:after.entrySet())emit("after."+entry.getKey(),entry.getValue());
   emit("accountsUnchangedDuringReadChecks",before.get("account_digest").equals(after.get("account_digest")));emit("sessionsUnchangedDuringReadChecks",before.get("session_digest").equals(after.get("session_digest")));emit("readChecksComplete",true);
  }catch(Exception failure){emit("readChecksComplete",false);emit("failureType",failure.getClass().getSimpleName());throw new IllegalStateException("Read-only Identity evidence incomplete; do not deploy",failure);}
  finally{evidence.setProperty("completedUtc",Instant.now().toString());try(var stream=Files.newOutputStream(output.resolve("read-only.properties"),StandardOpenOption.CREATE_NEW)){evidence.store(stream,"Aggregate/metadata evidence only; not a drain certificate or startup authorization");}}
 }
}
