package tools.ds.modkit;

import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.security.CodeSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public final class EnsureInstalled {

    private static final boolean DEBUG =
            Boolean.parseBoolean(System.getProperty("modkit.bootstrap.debug", "false"));

    private static final String AGENT_ACTIVE_FLAG   = "modkit.agent.active";
    private static final String AGENT_BOOTSTRAP_CLASS = "tools.ds.modkit.AgentBootstrap";
    private static final String EXTERNAL_ATTACH_MAIN  = "tools.ds.modkit.attach.ExternalAttacher";

    private static final Duration WAIT_FOR_INST = Duration.ofSeconds(8);
    private static final Duration WAIT_FOR_FLAG = Duration.ofSeconds(3);

    /** Ensure we only run once per JVM. */
    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    /** Public entry point: ensure agent + ModKit are active, or throw with guidance. */
    public static void ensureOrDie() {
        if (DONE.get()) return;

        synchronized (EnsureInstalled.class) {
            if (DONE.get()) return;

            // 0) Already present via -javaagent or earlier attach?
            if (InstrumentationHolder.isPresent()) {
                log("[modkit] Instrumentation present; installing transforms");
                afterInstrumentationAvailable(InstrumentationHolder.get());
                DONE.set(true);
                return;
            }

            // 0b) Agent premain/agentmain in-flight (flag set by AgentBootstrap)
            if ("true".equals(System.getProperty(AGENT_ACTIVE_FLAG))) {
                log("[modkit] Agent flag detected; waiting for premain/agentmain to provide Instrumentation");
                waitForInstrumentationOrDie("Premain/agentmain did not initialize Instrumentation", WAIT_FOR_FLAG);
                afterInstrumentationAvailable(InstrumentationHolder.get());
                DONE.set(true);
                return;
            }

            // 1) Self-attach (fast path)
            try {
                System.setProperty("jdk.attach.allowAttachSelf", "true");
                Instrumentation inst = ByteBuddyAgent.install();
                InstrumentationHolder.set(inst);
                log("[modkit] self-attach succeeded");
                afterInstrumentationAvailable(inst);
                DONE.set(true);
                return;
            } catch (Throwable t) {
                log("[modkit] self-attach failed: " + summarize(t));
            }

            // 2) External attach (helper JVM). Builds a temp agent jar if running from classes.
            try {
                externalAttachOrThrow();
                waitForInstrumentationOrDie("Agent did not report installed after external attach", WAIT_FOR_INST);
                log("[modkit] external attach succeeded");
                afterInstrumentationAvailable(InstrumentationHolder.get());
                DONE.set(true);
                return;
            } catch (Throwable t2) {
                // 3) Total failure → guidance.
                String agentHint = guessOwnJarPathForPrint();
                String msg =
                        "[modkit] FATAL: could not enable instrumentation.\n" +
                                envLine() + "\n" +
                                "Cause: " + summarize(t2) + "\n\n" +
                                Guidance.message(agentHint);
                System.err.println(msg);
                throw new IllegalStateException(msg, t2);
            }
        }
    }

    /** Called when Instrumentation is ready. Installs ModKit (which applies your DSL). */
    private static void afterInstrumentationAvailable(Instrumentation inst) {
        // Your ModKitCore should call BlackBoxBootstrap.register(), then use Weaver to
        // apply both method and constructor plans (including InstanceRegistry hooks).
        ModKitCore.install(inst);
    }

    // ------------------------ External attach helper ------------------------

    private static void externalAttachOrThrow() throws Exception {
        boolean hasAttachModule = ModuleLayer.boot().findModule("jdk.attach").isPresent();
        try {
            Class.forName("com.sun.tools.attach.VirtualMachine", false, EnsureInstalled.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(hasAttachModule
                    ? "com.sun.tools.attach.VirtualMachine not found"
                    : "No jdk.attach module (JRE/jlink runtime). External attach unavailable.");
        }

        long pid = ProcessHandle.current().pid();
        Path agentJar = resolveAgentJar();
        String javaBin = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("--add-modules"); cmd.add("jdk.attach");
        cmd.add("-cp"); cmd.add(agentJar.toString());
        cmd.add(EXTERNAL_ATTACH_MAIN);
        cmd.add(Long.toString(pid));
        cmd.add(agentJar.toString());

        if (DEBUG) log("[modkit] external attach launching: " + String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = readAll(p.getInputStream());
        int code = p.waitFor();
        if (DEBUG) log("[modkit] external attach exit=" + code + " output:\n" + out);
        if (code != 0) {
            throw new IllegalStateException("External attach helper failed (exit " + code + ")\n" + out);
        }
    }

    private static Path resolveAgentJar() {
        Path codeSource = codeSourcePathOf(loadClass(AGENT_BOOTSTRAP_CLASS));
        if (codeSource != null && Files.isRegularFile(codeSource)) {
            return codeSource; // packaged agent jar
        }
        try {
            return buildTempAgentJarFromClasses();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot package temporary agent jar from classes", e);
        }
    }

    private static String guessOwnJarPathForPrint() {
        Path p = codeSourcePathOf(loadClass(AGENT_BOOTSTRAP_CLASS));
        if (p != null && Files.isRegularFile(p)) return p.toString();
        return "<path-to-your-modkit-jar>";
    }

    private static Class<?> loadClass(String fqcn) {
        try {
            return Class.forName(fqcn, false, EnsureInstalled.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return EnsureInstalled.class;
        }
    }

    private static Path codeSourcePathOf(Class<?> anchor) {
        try {
            CodeSource cs = anchor.getProtectionDomain().getCodeSource();
            if (cs == null) return null;
            return Paths.get(cs.getLocation().toURI());
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static Path buildTempAgentJarFromClasses() throws IOException {
        Path classesRoot = codeSourcePathOf(EnsureInstalled.class);
        if (classesRoot == null || !Files.isDirectory(classesRoot)) {
            throw new IOException("Cannot locate classes root for temp agent packaging");
        }

        Manifest mf = new Manifest();
        Attributes attrs = mf.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(new Attributes.Name("Premain-Class"), AGENT_BOOTSTRAP_CLASS);
        attrs.put(new Attributes.Name("Agent-Class"),  AGENT_BOOTSTRAP_CLASS);
        attrs.put(new Attributes.Name("Can-Redefine-Classes"),    "true");
        attrs.put(new Attributes.Name("Can-Retransform-Classes"), "true");

        Path tmp = Files.createTempFile("modkit-agent-", ".jar");
        tmp.toFile().deleteOnExit();

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tmp), mf)) {
            Path pkgRoot = classesRoot.resolve("tools").resolve("ds").resolve("modkit");
            if (!Files.exists(pkgRoot)) {
                throw new IOException("Expected package root not found under classes: " + pkgRoot);
            }
            addTreeToJar(jos, classesRoot, pkgRoot);

            Path services = classesRoot.resolve("META-INF").resolve("services");
            if (Files.exists(services)) {
                addTreeToJar(jos, classesRoot, services);
            }
        }

        if (DEBUG) log("[modkit] built temp agent jar: " + tmp);
        return tmp;
    }

    private static void addTreeToJar(JarOutputStream jos, Path root, Path dir) throws IOException {
        Files.walk(dir)
                .filter(Files::isRegularFile)
                .forEach(p -> {
                    String entry = root.relativize(p).toString().replace('\\','/');
                    try (InputStream in = Files.newInputStream(p)) {
                        JarEntry je = new JarEntry(entry);
                        jos.putNextEntry(je);
                        in.transferTo(jos);
                        jos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private static void waitForInstrumentationOrDie(String reason, Duration maxWait) {
        long deadline = System.currentTimeMillis() + Math.max(1, maxWait.toMillis());
        while (!InstrumentationHolder.isPresent() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(25); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        if (!InstrumentationHolder.isPresent()) throw new IllegalStateException(reason);
    }

    private static String readAll(InputStream in) throws IOException {
        var bos = new ByteArrayOutputStream();
        in.transferTo(bos);
        return bos.toString();
    }

    private static String envLine() {
        return "[modkit] Env: java=" + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")"
                + ", home=" + System.getProperty("java.home")
                + ", os=" + System.getProperty("os.name")
                + ", pid=" + ProcessHandle.current().pid();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name","").toLowerCase().contains("win");
    }

    private static void log(String s) { if (DEBUG) System.err.println(s); }

    private static String summarize(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : (": " + m));
    }

    static final class Guidance {
        static String message(String agentPath) {
            return """
            How to run with the javaagent (choose one):

            1) Gradle (build.gradle):
               test {
                 jvmArgs "-javaagent:%s"
                 systemProperty "modkit.patches", "BlackBoxPatch"
               }

            2) Maven Surefire/Failsafe (pom.xml):
               <plugin>
                 <groupId>org.apache.maven.plugins</groupId>
                 <artifactId>maven-surefire-plugin</artifactId>
                 <version>3.2.5</version>
                 <configuration>
                   <argLine>-javaagent:%s -Dmodkit.patches=BlackBoxPatch</argLine>
                 </configuration>
               </plugin>

            3) IntelliJ Run/Debug Configuration → VM Options:
               -javaagent:%s -Dmodkit.patches=BlackBoxPatch

            4) Environment variable:
               # Linux/macOS
               export JDK_JAVA_OPTIONS="-javaagent:%s -Dmodkit.patches=BlackBoxPatch"
               # Windows PowerShell
               setx JDK_JAVA_OPTIONS "-javaagent:%s -Dmodkit.patches=BlackBoxPatch"

            Notes:
            - Dynamic attach can be restricted by policy; -javaagent avoids that.
            - Replace BlackBoxPatch with your patch id, if different.
            """.formatted(agentPath, agentPath, agentPath, agentPath, agentPath);
        }
    }

    private EnsureInstalled() {}
}
