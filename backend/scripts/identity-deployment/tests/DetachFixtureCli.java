import java.nio.file.*;import java.util.*;import com.sun.tools.attach.VirtualMachine;import com.kingofcards.identity.maintenance.Safety;
public class DetachFixtureCli {
    public static void main(String[]a)throws Exception{VirtualMachine vm=VirtualMachine.attach(a[0]);try{vm.loadAgent(a[1],a[2]);}finally{vm.detach();}var p=new Properties();p.setProperty("detached","true");p.setProperty("installNonce","fixture");p.setProperty("callerPid",Long.toString(ProcessHandle.current().pid()));Safety.save(p,Path.of(a[2]).resolve("detached.properties"));long end=System.nanoTime()+30_000_000_000L;while(!Files.exists(Path.of(a[2]).resolve("release-caller.marker"))){if(System.nanoTime()>end)throw new IllegalStateException("Offline caller release timed out");Thread.sleep(20);}}
}
