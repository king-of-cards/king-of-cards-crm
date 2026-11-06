package com.kingofcards.identity.ops.attach;
import java.lang.instrument.Instrumentation;import java.lang.reflect.*;import java.net.*;import java.nio.file.*;import java.security.*;import java.time.*;import java.util.*;
/** No class retransformation. Inspect is read-only; admission changes require separate approval. */
public final class IdentityAttachBootstrap {
 private static Class<?> runtime;private static String loadedHash;
 static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).toUpperCase(Locale.ROOT);}
 public static synchronized void agentmain(String argument,Instrumentation instrumentation)throws Exception{
  String[] parts=argument.split("\\|",-1);if(parts.length!=2)throw new IllegalArgumentException("Hashed request required");Path request=Path.of(parts[0]).toRealPath();Path root=Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/identity-handover-control").toRealPath();
  if(!request.startsWith(root)||Files.size(request)>16384||!sha(Files.readAllBytes(request)).equals(parts[1]))throw new IllegalArgumentException("Request path/hash mismatch");
  var p=new Properties();try(var in=Files.newInputStream(request)){p.load(in);}
  if(ProcessHandle.current().pid()!=Long.parseLong(p.getProperty("pid"))||Instant.parse(p.getProperty("expiresUtc")).isBefore(Instant.now()))throw new IllegalStateException("Wrong process or expired request");
  String action=p.getProperty("action");if(!Set.of("inspect","pause-auth","drain-all").contains(action))throw new IllegalArgumentException("Unsupported action; no stop/start action exists");
  if(action.equals("pause-auth")&&!"APPROVE_TEMPORARY_IDENTITY_AUTH_FENCE".equals(p.getProperty("approval")))throw new IllegalStateException("Separate authentication admission approval required");
  if(action.equals("drain-all")&&!"APPROVE_IDENTITY_ALL_ADMISSION_DRAIN".equals(p.getProperty("approval")))throw new IllegalStateException("Separate all-request drain approval required");
  Path self=Path.of(IdentityAttachBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());String hash=sha(Files.readAllBytes(self));if(!hash.equals(p.getProperty("helperSha256"))||(loadedHash!=null&&!hash.equals(loadedHash)))throw new IllegalStateException("Observer pin differs");loadedHash=hash;
  if(runtime==null){
   ClassLoader app=null;for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("com.kingofcards.identity.IdentityServiceApplication")){if(app!=null&&app!=type.getClassLoader())throw new IllegalStateException("Multiple Identity contexts");app=type.getClassLoader();}
   if(app==null)throw new IllegalStateException("Expected existing Identity application is absent");
   var loader=new URLClassLoader(new URL[]{self.toUri().toURL()},app){@Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{if(name.startsWith("com.kingofcards.identity.ops.")&&!name.startsWith("com.kingofcards.identity.ops.attach.")){Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;}return super.loadClass(name,resolve);}};
   runtime=Class.forName("com.kingofcards.identity.ops.IdentityObserveRuntime",true,loader);
  }
  try{runtime.getMethod("execute",Properties.class,Path.class).invoke(null,p,root);}catch(InvocationTargetException failure){throw new IllegalStateException("Identity observation/control did not complete; no stop/start action exists",failure.getCause());}
 }
}
