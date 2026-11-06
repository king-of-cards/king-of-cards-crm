package com.kingofcards.crm.ops.runtime;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.*;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
/** Installed only while the existing connector is paused and processing is drained. */
public final class AdmissionValve extends ValveBase {
 private volatile boolean allClosed;
 private int active;
 private long rejected;
 public AdmissionValve(){super(true);}
 public synchronized void closeAll(){allClosed=true;}
 public synchronized void readsOnly(){allClosed=false;}
 public synchronized int active(){return active;}
 public synchronized long rejected(){return rejected;}
 public boolean allClosed(){return allClosed;}
 private synchronized boolean enter(String method){
  if(allClosed||!(method.equals("GET")||method.equals("HEAD")||method.equals("OPTIONS"))){rejected++;return false;}active++;return true;
 }
 private synchronized void finish(){active--;if(active<0)throw new IllegalStateException("Invalid admission accounting");}
 @Override public void invoke(Request request,Response response)throws IOException,ServletException{
  if(!enter(request.getMethod())){response.setStatus(503);response.setHeader("X-KOC-Write-Admission","closed");response.setHeader("Retry-After","60");response.setContentType("application/json");response.getWriter().write("{\"code\":\"CRM_MAINTENANCE\",\"detail\":\"CRM writes are paused for controlled maintenance; no write was admitted.\"}");return;}
  var done=new AtomicBoolean();Runnable complete=()->{if(done.compareAndSet(false,true))finish();};
  try{getNext().invoke(request,response);}finally{
   if(request.isAsyncStarted())try{request.getAsyncContext().addListener(new AsyncListener(){
    public void onComplete(AsyncEvent e){complete.run();}public void onTimeout(AsyncEvent e){}public void onError(AsyncEvent e){}
    public void onStartAsync(AsyncEvent e){e.getAsyncContext().addListener(this);}
   });}catch(IllegalStateException alreadyComplete){complete.run();}
   else complete.run();
  }
 }
}
