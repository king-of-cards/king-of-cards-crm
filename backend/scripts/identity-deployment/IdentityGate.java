package com.kingofcards.identity.maintenance;

import jakarta.servlet.*;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.connector.*;
import org.apache.catalina.valves.ValveBase;

/** Installed inside Identity's only Tomcat context, before Spring Security. Never handles CRM. */
public final class IdentityGate extends ValveBase {
    public enum Mode { MUTATIONS_CLOSED, ALL_CLOSED, RESTORED }
    private Mode mode = Mode.MUTATIONS_CLOSED;
    private int active, mutations;
    private long rejected;
    public IdentityGate() { super(true); }
    public synchronized void closeMutations() { mode=Mode.MUTATIONS_CLOSED; }
    public synchronized void closeAll() { mode=Mode.ALL_CLOSED; }
    public synchronized void restore(String approval) {
        if (!"APPROVE_IDENTITY_ADMISSION_RELEASE".equals(approval)) throw new IllegalStateException("Release approval required");
        mode=Mode.RESTORED;
    }
    public record Counts(Mode mode,int active,int mutations,long rejected) {}
    public synchronized Counts counts() { return new Counts(mode,active,mutations,rejected); }
    @Override public void invoke(Request request,Response response) throws IOException,ServletException {
        boolean mutation=!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod());
        synchronized(this) {
            if(mode==Mode.ALL_CLOSED || (mutation&&mode!=Mode.RESTORED)) {
                rejected++; response.setStatus(503); response.setHeader("X-KOC-Identity-Admission","closed");
                response.setContentType("application/problem+json"); response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"status\":503,\"code\":\"IDENTITY_MAINTENANCE\",\"detail\":\"Authentication maintenance. No request was replayed.\"}"); return;
            }
            active++; if(mutation)mutations++;
        }
        var completed=new AtomicBoolean();
        Runnable finish=()->{if(completed.compareAndSet(false,true))synchronized(this){active--;if(mutation)mutations--;}};
        try { getNext().invoke(request,response); }
        finally {
            if(request.isAsyncStarted()) {
                try { request.getAsyncContext().addListener(new AsyncListener(){
                    public void onComplete(AsyncEvent e){finish.run();}
                    public void onError(AsyncEvent e){} // Do not certify completion on an uncertain timeout/error.
                    public void onTimeout(AsyncEvent e){}
                    public void onStartAsync(AsyncEvent e){e.getAsyncContext().addListener(this);}
                }); } catch(IllegalStateException alreadyComplete){finish.run();}
            } else finish.run();
        }
    }
}
