package com.kingofcards.identity.ops;
import java.io.IOException;import java.util.Set;
import jakarta.servlet.ServletException;import org.apache.catalina.connector.*;import org.apache.catalina.valves.ValveBase;

/** Prepared control only. No installer, endpoint, stop or startup action is exposed. */
public final class IdentityAdmissionValve extends ValveBase {
 public enum Mode { SESSION_PAUSED, ALL_CLOSED, SESSION_ONLY }
 private Mode mode=Mode.SESSION_PAUSED;private int active,mutations;private long rejected;
 private static final Set<String> READS=Set.of("GET","HEAD","OPTIONS");
 private static final Set<String> SESSIONS=Set.of("/auth/login","/auth/refresh","/auth/logout","/api/identity/auth/login","/api/identity/auth/refresh","/api/identity/auth/logout");
 public IdentityAdmissionValve(){super(true);}
 public synchronized void pauseSessions(){mode=Mode.SESSION_PAUSED;}
 public synchronized void closeAll(){mode=Mode.ALL_CLOSED;}
 public synchronized void allowSessionTraffic(String approval){if(!"EXPLICIT_APPROVED_SESSION_RELEASE".equals(approval))throw new IllegalStateException("Separate session release approval required");mode=Mode.SESSION_ONLY;}
 public synchronized Snapshot snapshot(){return new Snapshot(mode,active,mutations,rejected);}
 public record Snapshot(Mode mode,int active,int mutations,long rejected){}
 @Override public void invoke(Request request,Response response)throws IOException,ServletException {
  boolean mutation=!READS.contains(request.getMethod());boolean admitted;
  synchronized(this){
   admitted=mode!=Mode.ALL_CLOSED&&(!mutation||(mode==Mode.SESSION_ONLY&&"POST".equals(request.getMethod())&&SESSIONS.contains(request.getRequestURI())));
   if(admitted){active++;if(mutation)mutations++;}else rejected++;
  }
  if(!admitted){response.setStatus(503);response.setHeader("X-KOC-Identity-Admission","closed");response.setContentType("application/problem+json");response.setCharacterEncoding("UTF-8");response.getWriter().write("{\"status\":503,\"code\":\"IDENTITY_MAINTENANCE\",\"detail\":\"Authentication admission is temporarily closed. No request was replayed.\"}");return;}
  try{getNext().invoke(request,response);}finally{synchronized(this){active--;if(mutation)mutations--;}}
 }
}
