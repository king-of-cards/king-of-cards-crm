package com.kingofcards.crm.ops.fenced;
import java.nio.file.*;import java.util.*;import com.sun.tools.attach.*;
/** Inspect is read-only. Stop requires the distinct approval phrase and verified certificate/archive. */
public final class FencedControl {
 public static void main(String[]args)throws Exception{
  if(args.length!=3&&args.length!=8)throw new IllegalArgumentException("inspect PID guardSHA OR stop-read-only-approved PID guardSHA cert certSHA archive manifestSHA APPROVE_READ_ONLY_STOP");
  boolean stop=args[0].equals("stop-read-only-approved");FencedPolicy.require(args[0].equals("inspect")||stop,"Unsupported action");FencedPolicy.require(!stop||(args.length==8&&args[7].equals("APPROVE_READ_ONLY_STOP")),"Separate stop approval required");
  String pid=Long.toString(Long.parseLong(args[1]));FencedPolicy.require(pid.equals("3160"),"Only reviewed current CRM PID allowed");Path self=Path.of(FencedControl.class.getProtectionDomain().getCodeSource().getLocation().toURI());FencedPolicy.require(FencedPolicy.hash(self).equals(args[2]),"Guard hash differs");
  var p=new Properties();p.setProperty("pid",pid);p.setProperty("action",args[0]);p.setProperty("guardSha256",args[2]);if(stop){p.setProperty("approval",args[7]);p.setProperty("certificate",args[3]);p.setProperty("certificateSha256",args[4]);p.setProperty("archive",args[5]);p.setProperty("archiveManifestSha256",args[6]);}
  Path request=FencedAgent.CONTROL.resolve("fenced-request-"+UUID.randomUUID()+".properties");try(var out=Files.newOutputStream(request,StandardOpenOption.CREATE_NEW)){p.store(out,"Local operator control; no credentials/customer fields");}
  VirtualMachine vm=VirtualMachine.attach(pid);try{vm.loadAgent(self.toString(),request.toString());}catch(Exception e){if(!stop||ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false))throw e;}finally{try{vm.detach();}catch(Exception ignored){}}
  if(!stop){var state=FencedPolicy.read(FencedAgent.CONTROL.resolve("fenced-inspection.properties"));for(String key:List.of("pid","processStartUtc","profile","paused","allClosed","writesBlocked","httpActive","queued","processing","gateActive","directionalRepository","localRepositoryPresent","operationKeyInAppMaps","uniqueStateRisks","mutationStackCount","appMapMetadata","observedAtUtc"))System.out.println(key+"="+state.getProperty(key));}
 }
}
