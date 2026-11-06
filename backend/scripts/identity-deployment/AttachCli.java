package com.kingofcards.identity.maintenance;

import java.nio.file.*;
import java.util.*;
import com.sun.tools.attach.VirtualMachine;

/** Detachment acknowledgment is written only AFTER VM.detach returns. Never stops the target. */
public final class AttachCli {
    public static void main(String[] args)throws Exception {
        Safety.require(args.length==3,"PID, pinned helper and install request required");long pid=Long.parseLong(args[0]);
        Path helper=Safety.inside(args[1],Safety.ROOT.resolve("identity-service/target-identity-deployment-verify-20261106"));
        Path request=Safety.inside(args[2],Safety.ROOT.resolve("local-dev/identity-deployment"));var p=Safety.load(request);
        Safety.require(Safety.hash(helper).equals(p.getProperty("helperSha256")),"Helper pin mismatch");
        VirtualMachine vm=VirtualMachine.attach(Long.toString(pid));try{vm.loadAgent(helper.toString(),request+"|"+Safety.hash(request));}finally{vm.detach();}
        var ack=new Properties();ack.setProperty("detached","true");ack.setProperty("installNonce",p.getProperty("nonce"));ack.setProperty("callerPid",Long.toString(ProcessHandle.current().pid()));
        Safety.save(ack,request.getParent().resolve("detached.properties"));System.out.println("ADMISSION_CONTROLLER_INSTALLED_AND_DETACHED; no stop was requested");
    }
}
