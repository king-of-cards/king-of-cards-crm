import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Read-only, bounded class/resource inspection. Never transforms classes or controls services. */
public final class GatewayRuntimeProbe {
    public static void agentmain(String output, Instrumentation instrumentation) throws Exception {
        var report = new StringBuilder("pid=" + ProcessHandle.current().pid() + "\n");
        var names = List.of("io.netty.buffer.AdaptivePoolingAllocator",
            "io.netty.buffer.FreeChunkEvent", "io.netty.util.concurrent.SingleThreadEventExecutor");
        ClassLoader nettyLoader = null;
        for (var type : instrumentation.getAllLoadedClasses()) {
            if (!names.contains(type.getName())) continue;
            report.append(type.getName()).append(".loaded=true\n");
            report.append(type.getName()).append(".version=")
                .append(type.getPackage().getImplementationVersion()).append('\n');
            report.append(type.getName()).append(".source=")
                .append(type.getProtectionDomain().getCodeSource().getLocation()).append('\n');
            if (type.getName().equals(names.getFirst())) nettyLoader = type.getClassLoader();
        }
        if (nettyLoader == null) throw new IllegalStateException("Expected Netty allocator is not loaded");
        for (String name : names) {
            var resource = nettyLoader.getResource(name.replace('.', '/') + ".class");
            report.append(name).append(".resource=").append(resource).append('\n');
        }
        Files.writeString(Path.of(output), report.toString(), StandardOpenOption.CREATE_NEW);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("PID agent-JAR output-file required");
        var vm = VirtualMachine.attach(args[0]);
        try { vm.loadAgent(args[1], args[2]); }
        finally { vm.detach(); }
    }
}
