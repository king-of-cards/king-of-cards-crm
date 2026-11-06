import java.lang.instrument.*;import java.nio.file.*;
public class DetachFixtureAgent {
    public static void agentmain(String root,Instrumentation ignored)throws Exception{Files.writeString(Path.of(root).resolve("agent-returned.marker"),"fixture install only",StandardOpenOption.CREATE_NEW);}
}
