import java.nio.file.*;import java.util.*;import java.security.*;import com.sun.tools.attach.VirtualMachine;
/** Local operator only. No login, SQL, stop/start, release-writes or repeated runtime action. */
class CrmDrainControl {
 public static void main(String[]args)throws Exception {
  if(args.length!=5&&args.length!=6)throw new IllegalArgumentException("Expected PID action helperJar helperSHA applicationSHA");
  if(!List.of("inspect","status","install-drain","drain-all","resume-reads","shutdown-approved").contains(args[1]))throw new IllegalArgumentException("Unsupported control action");
  if(args[1].equals("shutdown-approved")&&(args.length!=6||!args[5].equals("--approve-stop-after-drain")))throw new IllegalArgumentException("Separate actual stop approval required");
  long pid=Long.parseLong(args[0]);if(pid<=0||pid==ProcessHandle.current().pid())throw new IllegalArgumentException("Expected existing CRM PID");
  Path helper=Path.of(args[2]).toAbsolutePath().normalize();Path allowed=Path.of("C:/kocbackend/king-of-cards-crm/backend/crm-service/target-drain-control-verify").toAbsolutePath().normalize();
  if(!helper.startsWith(allowed))throw new IllegalArgumentException("Helper path not approved");
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(helper))).toUpperCase(Locale.ROOT);if(!hash.equals(args[3]))throw new IllegalArgumentException("Helper hash differs");
  Path dir=Path.of("C:/kocbackend/king-of-cards-crm/backend/local-dev/crm-drain-control");if(!Files.isDirectory(dir))throw new IllegalArgumentException("Private control directory must be prepared first");
  Properties request=new Properties();request.setProperty("pid",Long.toString(pid));request.setProperty("action",args[1]);request.setProperty("agentSha256",hash);request.setProperty("applicationSha256",args[4]);if(args[1].equals("shutdown-approved"))request.setProperty("approveStop","true");
  Path file=dir.resolve("request-"+UUID.randomUUID()+".properties");try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){request.store(out,"Local maintenance request; no credentials or customer data");}
  VirtualMachine vm=VirtualMachine.attach(Long.toString(pid));try{vm.loadAgent(helper.toString(),file.toString());}catch(Exception error){if(!(args[1].equals("shutdown-approved")&&!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)))throw error;}finally{try{vm.detach();}catch(Exception ignored){}}
  var status=new Properties();try(var in=Files.newInputStream(dir.resolve("status.properties"))){status.load(in);}
  for(String key:List.of("phase","pid","paused","httpActive","queued","processing","gateInstalled","writesBlocked","allClosed","gateActive","rejected","project","location","principalSha256","timestamp"))System.out.println(key+"="+status.getProperty(key));
 }
}
