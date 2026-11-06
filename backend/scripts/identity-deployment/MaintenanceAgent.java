package com.kingofcards.identity.maintenance.attach;

import java.lang.instrument.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Installs admission/control only, returns promptly. Contains NO shutdown operation. */
public final class MaintenanceAgent {
    private static boolean installed;
    public static synchronized void agentmain(String argument,Instrumentation instrumentation)throws Exception {
        if(installed)throw new IllegalStateException("Do not repeat maintenance installation");
        String[] parts=argument.split("\\|",-1);if(parts.length!=2)throw new IllegalArgumentException("Hashed install request required");
        Path path=Path.of(parts[0]).toRealPath(),root=Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/identity-deployment").toRealPath();
        if(!path.startsWith(root)||Files.size(path)>16384||!sha(path).equals(parts[1]))throw new IllegalArgumentException("Install path/hash differs");
        var p=new Properties();try(var in=Files.newInputStream(path)){p.load(in);}
        if(!"APPROVE_ONE_IDENTITY_DEPLOYMENT".equals(p.getProperty("approval"))||ProcessHandle.current().pid()!=Long.parseLong(p.getProperty("pid"))||Duration.between(ProcessHandle.current().info().startInstant().orElseThrow(),Instant.parse(p.getProperty("processStartedUtc"))).abs().compareTo(Duration.ofMillis(10))>0||!Instant.parse(p.getProperty("expiresUtc")).isAfter(Instant.now()))throw new IllegalStateException("Expired/unapproved/wrong-process installation");
        Path self=Path.of(MaintenanceAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());if(!sha(self).equals(p.getProperty("helperSha256")))throw new IllegalStateException("Helper hash differs");
        ClassLoader app=null;for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("com.kingofcards.identity.IdentityServiceApplication")){if(app!=null&&app!=type.getClassLoader())throw new IllegalStateException("Multiple Identity loaders");app=type.getClassLoader();}
        if(app==null)throw new IllegalStateException("Identity app absent");
        var loader=new URLClassLoader(new URL[]{self.toUri().toURL()},app){protected synchronized Class<?> loadClass(String n,boolean resolve)throws ClassNotFoundException{
            if(n.startsWith("com.kingofcards.identity.maintenance.")&&!n.contains(".attach.")){Class<?> c=findLoadedClass(n);if(c==null)c=findClass(n);if(resolve)resolveClass(c);return c;}return super.loadClass(n,resolve);
        }};
        Class.forName("com.kingofcards.identity.maintenance.RuntimeControl",true,loader).getMethod("install",Properties.class).invoke(null,p);installed=true;
    }
    private static String sha(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))).toUpperCase(Locale.ROOT);}
}
