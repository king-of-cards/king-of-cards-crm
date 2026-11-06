package com.kingofcards.crm.ops.fenced;
import java.lang.instrument.*;import java.lang.reflect.*;import java.nio.file.*;import java.time.*;import java.util.*;
/** Reflective read-only inspection of existing singleton metadata. Never initializes lazy beans. */
public final class LiveInspection {
 public record Result(Properties evidence,Object context){}
 static Object call(Object target,String method,Class<?>[] types,Object...args)throws Exception{return target.getClass().getMethod(method,types).invoke(target,args);}
 public static Result capture(Instrumentation instrumentation)throws Exception{
  Class<?> runtime=null;Object context=null;
  for(Class<?> c:instrumentation.getAllLoadedClasses())if(c.getName().equals("com.kingofcards.crm.ops.runtime.GateRuntime")){Field field=c.getDeclaredField("context");field.setAccessible(true);Object candidate=field.get(null);if(candidate!=null){if(context!=null&&context!=candidate)throw new IllegalStateException("Multiple CRM contexts");context=candidate;runtime=c;}}
  if(context==null)throw new IllegalStateException("Existing guarded CRM context not found");
  Path barrier=Path.of(runtime.getProtectionDomain().getCodeSource().getLocation().toURI());FencedPolicy.require(FencedPolicy.hash(barrier).equals(FencedPolicy.BARRIER),"Installed barrier hash differs");
  Path app=(Path)runtime.getMethod("applicationJar").invoke(null);FencedPolicy.require(FencedPolicy.hash(app).equals(FencedPolicy.OLD),"Expected current CRM JAR differs");
  var snapshot=runtime.getDeclaredMethod("snapshot");snapshot.setAccessible(true);var p=new Properties();p.putAll((Map<?,?>)snapshot.invoke(null));p.setProperty("processStartUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
  Object env=call(context,"getEnvironment",new Class<?>[0]);String[] profiles=(String[])call(env,"getActiveProfiles",new Class<?>[0]);p.setProperty("profile",String.join(",",profiles));
  Object server=call(context,"getWebServer",new Class<?>[0]),tomcat=call(server,"getTomcat",new Class<?>[0]),host=call(tomcat,"getHost",new Class<?>[0]);Object[] children=(Object[])call(host,"findChildren",new Class<?>[0]);FencedPolicy.require(children.length==1,"Unexpected servlet contexts");Object manager=call(children[0],"getManager",new Class<?>[0]);p.setProperty("httpSessions",manager==null?"0":call(manager,"getActiveSessions",new Class<?>[0]).toString());
  Object factory=call(context,"getBeanFactory",new Class<?>[0]);String[] names=(String[])call(factory,"getSingletonNames",new Class<?>[0]);int risks=0,applicationBeans=0;boolean local=false,keyPresent=false;
  var beanTypes=new TreeSet<String>();var mapMetadata=new TreeSet<String>();
  for(String name:names){Object bean=call(factory,"getSingleton",new Class<?>[]{String.class},name);if(bean==null)continue;Class<?> type=bean.getClass();String className=type.getName();if(!className.startsWith("com.kingofcards.crm.")||className.contains(".ops."))continue;applicationBeans++;beanTypes.add(className);
   if(className.contains("LocalDirectionalRepository")){local=true;risks++;}if(className.equals("com.kingofcards.crm.directional.BigQueryDirectionalRepository"))p.setProperty("directionalRepository",className);
   for(Class<?> owner=type;owner!=null&&owner.getName().startsWith("com.kingofcards.crm.");owner=owner.getSuperclass())for(Field f:owner.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&(Map.class.isAssignableFrom(f.getType())||Collection.class.isAssignableFrom(f.getType()))){f.setAccessible(true);Object value=f.get(bean);int size=value instanceof Map<?,?> m?m.size():value instanceof Collection<?> c?c.size():0;mapMetadata.add(owner.getSimpleName()+"."+f.getName()+" size="+size);
    if(value instanceof Map<?,?> m)for(Object key:m.keySet())if((key instanceof String||key instanceof UUID)&&key.toString().contains(FencedPolicy.OP))keyPresent=true;
    // Only immutable parser/property configuration is allowed to be kept in app maps.
    boolean config=owner.getName().equals("com.kingofcards.crm.service.LeadUploadParser")||owner.getSimpleName().endsWith("Properties");if(!config)risks++;
   }
  }
  int mutations=0;var stackKinds=new TreeSet<String>();for(var entry:Thread.getAllStackTraces().entrySet())for(var frame:entry.getValue())if(frame.getClassName().startsWith("com.google.cloud.bigquery.")&&Set.of("create","insertAll","query","runQuery").contains(frame.getMethodName())){mutations++;stackKinds.add(frame.getClassName()+"."+frame.getMethodName());break;}
  p.setProperty("applicationSingletons",Integer.toString(applicationBeans));p.setProperty("applicationBeanTypes",String.join(";",beanTypes));p.setProperty("appMapMetadata",String.join(";",mapMetadata));p.setProperty("uniqueStateRisks",Integer.toString(risks));p.setProperty("localRepositoryPresent",Boolean.toString(local));p.setProperty("operationKeyInAppMaps",Boolean.toString(keyPresent));p.setProperty("mutationStackCount",Integer.toString(mutations));p.setProperty("mutationStackKinds",String.join(";",stackKinds));p.setProperty("observedAtUtc",Instant.now().toString());return new Result(p,context);
 }
}
