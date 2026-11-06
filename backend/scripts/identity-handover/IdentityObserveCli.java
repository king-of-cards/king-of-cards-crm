import java.nio.file.*;import java.security.*;import java.util.*;import com.sun.tools.attach.VirtualMachine;
/** One bounded caller request. No stop, exit, SQL or retry action. */
class IdentityObserveCli {
 public static void main(String[]args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("PID helperJar hashedRequest argument required");
  long pid=Long.parseLong(args[0]);if(pid<=0||pid==ProcessHandle.current().pid())throw new IllegalArgumentException("Invalid target PID");
  Path helper=Path.of(args[1]).toRealPath();Path allowed=Path.of("C:/kocbackend/king-of-cards-crm/backend/identity-service/target-identity-handover-verify-20261106").toRealPath();if(!helper.startsWith(allowed))throw new IllegalArgumentException("Unexpected observer artifact");
  VirtualMachine vm=VirtualMachine.attach(Long.toString(pid));try{vm.loadAgent(helper.toString(),args[2]);}finally{vm.detach();}
  System.out.println("IDENTITY_OBSERVER_RETURNED; inspect the nonce-bound observation; no stop/start command exists");
 }
}
