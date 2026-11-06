import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Offline archive/Boot-loader verification. Does not run SpringApplication or open a listener. */
public final class GatewayArtifactVerifier {
    private static final List<String> CLASSES = List.of("io.netty.buffer.AdaptivePoolingAllocator",
        "io.netty.buffer.FreeChunkEvent", "io.netty.util.concurrent.SingleThreadEventExecutor",
        "reactor.netty.http.client.HttpClient", "org.springframework.cloud.gateway.filter.NettyRoutingFilter");

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("probe")) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            for (String name : CLASSES) {
                Class.forName(name, false, loader);
                if (loader.getResource(name.replace('.', '/') + ".class") == null)
                    throw new IllegalStateException("Packaged resource unavailable: " + name);
            }
            var allocator = Class.forName("io.netty.buffer.ByteBufAllocator", true, loader);
            Object pool = allocator.getField("DEFAULT").get(null);
            var buffer = allocator.getMethod("buffer", int.class);
            var release = Class.forName("io.netty.buffer.ByteBuf", true, loader).getMethod("release");
            for (int i = 0; i < 128; i++) {
                if (!Boolean.TRUE.equals(release.invoke(buffer.invoke(pool, 32768))))
                    throw new IllegalStateException("Buffer not released");
            }
            Class.forName("io.netty.util.concurrent.FastThreadLocal", true, loader).getMethod("removeAll").invoke(null);
            System.out.println("PACKAGED_LOADER_AND_NETTY_RELEASE=PASS");
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("JAR path or probe required");
        Path path = Path.of(args[0]);
        try (var jar = new JarFile(path.toFile(), true)) {
            var attributes = jar.getManifest().getMainAttributes();
            require("org.springframework.boot.loader.launch.JarLauncher".equals(attributes.getValue("Main-Class")), "Boot launcher");
            require("com.kingofcards.gateway.ApiGatewayApplication".equals(attributes.getValue("Start-Class")), "Gateway main");
            require(jar.getEntry("org/springframework/boot/loader/launch/JarLauncher.class") != null, "Boot launcher class");
            var found = new HashMap<String, Integer>();
            var versions = new HashSet<String>();
            int libraries = 0;
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                byte[] bytes;
                try (var input = jar.getInputStream(entry)) { bytes = input.readAllBytes(); }
                var crc = new CRC32(); crc.update(bytes);
                require(entry.getCrc() == crc.getValue(), "Archive CRC " + entry.getName());
                if (!entry.getName().startsWith("BOOT-INF/lib/") || !entry.getName().endsWith(".jar")) continue;
                libraries++;
                if (entry.getName().startsWith("BOOT-INF/lib/netty-")) {
                    var matcher = java.util.regex.Pattern.compile("-(4\\.[0-9]+\\.[0-9]+\\.Final)").matcher(entry.getName());
                    require(matcher.find(), "Recognized Netty version"); versions.add(matcher.group(1));
                }
                try (var nested = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                    ZipEntry child;
                    while ((child = nested.getNextEntry()) != null) {
                        nested.transferTo(OutputStream.nullOutputStream()); // validates nested CRC on read
                        for (String name : CLASSES) {
                            if (child.getName().equals(name.replace('.', '/') + ".class")) found.merge(name, 1, Integer::sum);
                        }
                    }
                }
            }
            for (String name : CLASSES) require(found.getOrDefault(name, 0) == 1, "Exactly one packaged " + name);
            require(versions.size() == 1, "Aligned Netty versions");
            System.out.println("ARCHIVE_INTEGRITY=PASS libraries=" + libraries + " netty=" + versions.iterator().next());
        }
        var hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] chunk = new byte[65536]; int count;
            while ((count = input.read(chunk)) != -1) hash.update(chunk, 0, count);
        }
        System.out.println("JAR_SHA256=" + HexFormat.of().withUpperCase().formatHex(hash.digest()));
    }
    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("Artifact verification failed: " + label);
    }
}
