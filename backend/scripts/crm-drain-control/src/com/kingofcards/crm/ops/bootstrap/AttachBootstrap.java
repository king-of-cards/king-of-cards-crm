package com.kingofcards.crm.ops.bootstrap;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.security.*;
public final class AttachBootstrap {
 private static Class<?> runtime;
 public static synchronized void agentmain(String requestPath,Instrumentation instrumentation)throws Exception {
  Path request=Path.of(requestPath).toAbsolutePath().normalize();Path allowed=Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control").toAbsolutePath().normalize();
  if(!request.startsWith(allowed)||Files.size(request)>16384)throw new IllegalArgumentException("Unapproved local control path");
  var p=new Properties();try(var in=Files.newInputStream(request)){p.load(in);}
  if(ProcessHandle.current().pid()!=Long.parseLong(p.getProperty("pid")))throw new IllegalArgumentException("PID mismatch");
  Path jar=Path.of(AttachBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))).toUpperCase(Locale.ROOT);
  if(!hash.equals(p.getProperty("agentSha256")))throw new IllegalArgumentException("Helper hash mismatch");
  if(runtime==null){
   ClassLoader app=null;for(Class<?> c:instrumentation.getAllLoadedClasses())if(c.getName().equals("com.kingofcards.crm.CrmServiceApplication")){if(app!=null&&app!=c.getClassLoader())throw new IllegalStateException("Multiple CRM classloaders");app=c.getClassLoader();}
   if(app==null)throw new IllegalStateException("Existing CRM application not found");
   for(Class<?> c:instrumentation.getAllLoadedClasses())if(c.getName().equals("com.kingofcards.crm.ops.runtime.GateRuntime")&&c.getClassLoader()==app)runtime=c;
   if(runtime==null){var loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},app){
    @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
     if(name.startsWith("com.kingofcards.crm.ops.runtime.")){Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;}return super.loadClass(name,resolve);
    }};runtime=Class.forName("com.kingofcards.crm.ops.runtime.GateRuntime",true,loader);
   }
  }
  try{runtime.getMethod("execute",Properties.class,Path.class).invoke(null,p,request.getParent());}
  catch(InvocationTargetException e){throw new IllegalStateException("CRM drain action failed; inspect local status before further action",e.getCause());}
 }
}
