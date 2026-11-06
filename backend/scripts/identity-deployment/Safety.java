package com.kingofcards.identity.maintenance;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public final class Safety {
    public static final Path ROOT=Path.of("C:/kocbackend/king-of-cards-crm/backend").toAbsolutePath().normalize();
    public static final String OLD="2B7E8A0655EC5C87DD761A756D70A6D233F9EF09E93BAB564B0B23A538F328DC";
    public static final String NEW="8CC080E57C602C4D24CFABFACC35B7788B6D37D8B60AE2FA5038CB73777910B5";
    public static final String ARCHIVE="9BBE58931F5D9769DAEAF6DB7F856E92B5A3BE96AF781C3A7C55748392354E87";
    public static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)).toUpperCase(Locale.ROOT);}
    public static String hash(Path p)throws Exception{return hash(Files.readAllBytes(p));}
    public static String textHash(String s)throws Exception{return hash(s.getBytes(StandardCharsets.UTF_8));}
    public static void require(boolean ok,String detail){if(!ok)throw new IllegalStateException(detail);}
    public static Properties load(Path p)throws Exception {var r=new Properties();try(var in=Files.newInputStream(p)){r.load(in);}return r;}
    public static void save(Properties p,Path f)throws Exception {
        // Publish a complete file atomically. createLink is CREATE_NEW even when
        // racing another writer; ATOMIC_MOVE alone can replace an existing target.
        Path staged=f.resolveSibling(".writing-"+UUID.randomUUID());
        try(var out=Files.newOutputStream(staged,StandardOpenOption.CREATE_NEW)){p.store(out,"Identity maintenance evidence; no secrets; append-only");}
        Files.createLink(f,staged); // Retain staging output; never overwrite/delete evidence.
    }
    public static Path inside(String value,Path root)throws Exception {Path p=Path.of(value).toRealPath();require(p.startsWith(root.toRealPath()),"Path outside approved root");return p;}
    public static void privateControl(Path directory,Properties pins)throws Exception {
        var view=Files.getFileAttributeView(directory,java.nio.file.attribute.AclFileAttributeView.class);
        require(view!=null,"Windows ACL verification unavailable");
        var owner=directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        require(view.getOwner().equals(owner)&&view.getOwner().getName().equals(pins.getProperty("controlOwner.name")),"Control directory owner differs from JVM owner");
        var allowed=Set.of(pins.getProperty("controlOwner.name"),pins.getProperty("controlSystem.name"),pins.getProperty("controlAdministrators.name"));
        var writes=Set.of(java.nio.file.attribute.AclEntryPermission.WRITE_DATA,java.nio.file.attribute.AclEntryPermission.APPEND_DATA,java.nio.file.attribute.AclEntryPermission.WRITE_ATTRIBUTES,java.nio.file.attribute.AclEntryPermission.WRITE_NAMED_ATTRS,java.nio.file.attribute.AclEntryPermission.WRITE_ACL,java.nio.file.attribute.AclEntryPermission.WRITE_OWNER,java.nio.file.attribute.AclEntryPermission.DELETE,java.nio.file.attribute.AclEntryPermission.DELETE_CHILD);
        for(var entry:view.getAcl())if(entry.type()==java.nio.file.attribute.AclEntryType.ALLOW&&entry.permissions().stream().anyMatch(writes::contains))require(allowed.contains(entry.principal().getName()),"Other local principal can write maintenance controls");
    }
    public static void fresh(Properties p,Instant now) {
        Instant issued=Instant.parse(p.getProperty("issuedUtc")),expires=Instant.parse(p.getProperty("expiresUtc"));
        require(!issued.isAfter(now)&&expires.isAfter(now)&&expires.isAfter(issued)&&Duration.between(issued,expires).compareTo(Duration.ofSeconds(30))<=0,"Expired/invalid certificate");
    }
    public static void closed(Properties p,Instant now,String app,String helper,String config,String archive,long pid,String started) {
        fresh(p,now);
        require(app.equals(p.getProperty("applicationSha256"))&&helper.equals(p.getProperty("helperSha256"))&&config.equals(p.getProperty("configurationSha256"))&&archive.equals(p.getProperty("archiveSha256")),"Certificate pin mismatch");
        require(Long.toString(pid).equals(p.getProperty("pid"))&&started.equals(p.getProperty("processStartedUtc")),"Certificate process mismatch");
        require("ALL_CLOSED".equals(p.getProperty("gateMode"))&&"true".equals(p.getProperty("connectorPaused")),"Admission is not closed");
        for(String n:List.of("httpActive","httpQueued","httpProcessing","gateActive","gateMutations","mutationThreads","activeAuthJobs","recentAuthNonterminal","orphans"))require("0".equals(p.getProperty(n)),"Outstanding or uncertain activity: "+n);
        require("true".equals(p.getProperty("ownJobPagesComplete"))&&"true".equals(p.getProperty("recentAuthPagesComplete"))&&"true".equals(p.getProperty("principalVerified")),"Incomplete job visibility");
    }
    public static void sameData(Properties a,Properties b){for(String n:List.of("accounts","sessions","orphans","accountDigest","sessionDigest","usersSchema","sessionsSchema"))require(Objects.equals(a.getProperty(n),b.getProperty(n))&&a.getProperty(n)!=null,"Identity data/schema differs: "+n);}
    public static void rollback(boolean newAlive,boolean portOccupied,boolean priorAttempt,boolean pins,boolean compatible) {
        require(!newAlive&&!portOccupied&&!priorAttempt&&pins&&compatible,"Rollback refused: running JVM, occupied port, spent approval or incompatibility");
    }
    public static void detached(Properties ack,String nonce,boolean callerAlive) {
        require("true".equals(ack.getProperty("detached"))&&nonce.equals(ack.getProperty("installNonce"))&&!callerAlive,"Attach caller has not detached and exited");
    }
    public static void settled(Instant drainedAt,Instant now){require(drainedAt!=null&&!drainedAt.isAfter(now)&&Duration.between(drainedAt,now).compareTo(Duration.ofSeconds(315))>=0,"Auth request-deadline settling window incomplete");}
}
