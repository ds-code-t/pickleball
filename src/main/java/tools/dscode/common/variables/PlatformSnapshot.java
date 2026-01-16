package tools.dscode.common.variables;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.sun.management.OperatingSystemMXBean;

public final class PlatformSnapshot {

    private static final Map<String, Object> DATA;

    static {
        Map<String, Object> m = new HashMap<>();

        /* =========================
           Highly reliable
           ========================= */

        m.put("java.version", System.getProperty("java.version"));
        m.put("java.runtime.version", System.getProperty("java.runtime.version"));

        m.put("jvm.vendor", System.getProperty("java.vendor"));
        m.put("jvm.name", System.getProperty("java.vm.name"));
        m.put("jvm.version", System.getProperty("java.vm.version"));

        m.put("os.name", System.getProperty("os.name"));
        m.put("os.version", System.getProperty("os.version"));
        m.put("os.arch", System.getProperty("os.arch"));

        m.put("jvm.startTime", ManagementFactory.getRuntimeMXBean().getStartTime());
        m.put("pid", ProcessHandle.current().pid());

        m.put("timezone", ZoneId.systemDefault().getId());
        m.put("locale", Locale.getDefault().toString());

        Runtime rt = Runtime.getRuntime();
        m.put("heap.max", rt.maxMemory());
        m.put("heap.initial", rt.totalMemory());

        m.put("processors", rt.availableProcessors());

        m.put("user.name", System.getProperty("user.name"));
        m.put("user.home", System.getProperty("user.home"));
        m.put("working.dir", System.getProperty("user.dir"));

        /* =========================
           Reliable with caveats
           ========================= */

        m.put("hostname", resolveHostname());
        m.put("utc.offset", ZonedDateTime.now().getOffset().toString());

        OperatingSystemMXBean osBean =
                (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        m.put("memory.total.physical", osBean.getTotalMemorySize());
        m.put("cpu.system.load", osBean.getSystemCpuLoad());
        m.put("cpu.process.load", osBean.getProcessCpuLoad());

        m.put("network.interfaces", enumerateNetworkInterfaces());

        m.put("file.separator", System.getProperty("file.separator"));
        m.put("path.separator", System.getProperty("path.separator"));
        m.put("line.separator", System.lineSeparator());
        m.put("tmp.dir", System.getProperty("java.io.tmpdir"));

        /* =========================
           Reliably detectable presence
           ========================= */

        m.put("container", detectContainer());
        m.put("virtualized", detectVirtualization());
        m.put("service.nonInteractive", detectNonInteractive());
        m.put("ci.type", detectCIType());
        m.put("headless", java.awt.GraphicsEnvironment.isHeadless());

        /* =========================
           Inferable / heuristic
           ========================= */

        m.put("primary.network.interface", detectPrimaryInterface());
        m.put("machine.class", detectMachineClass());
        m.put("cloud.provider", detectCloudProvider());
        m.put("container.orchestrator", detectContainerOrchestrator());
        m.put("filesystem.types", detectFileSystemTypes());
        m.put("os.family", detectOSFamily());
        m.put("cpu.model", System.getenv("PROCESSOR_IDENTIFIER"));
        m.put("system.uptime.ms",
                TimeUnit.NANOSECONDS.toMillis(
                        ManagementFactory.getRuntimeMXBean().getUptime()));

        /* =========================
           Weakly reliable
           ========================= */

        m.put("interactive.user", System.getProperty("user.name"));
        m.put("domain.name",
                Optional.ofNullable(System.getenv("USERDOMAIN"))
                        .orElse(System.getenv("USERDNSDOMAIN")));
        m.put("user.groups", null);
        m.put("user.email", null);
        m.put("device.management", null);

        DATA = Collections.unmodifiableMap(m);


        System.out.println("Platform Data: " + DATA);
    }

    private PlatformSnapshot() {}

    /* =========================
       Public getters (examples)
       ========================= */

    public static String getOS() {
        return (String) DATA.get("os.name");
    }

    public static String getOSVersion() {
        return (String) DATA.get("os.version");
    }

    public static String getOSArch() {
        return (String) DATA.get("os.arch");
    }

    public static String getJavaVersion() {
        return (String) DATA.get("java.version");
    }

    public static String getWorkingDirectory() {
        return (String) DATA.get("working.dir");
    }

    public static String getUserName() {
        return (String) DATA.get("user.name");
    }

    public static long getPid() {
        return (long) DATA.get("pid");
    }

    public static Map<String, Object> asMap() {
        return DATA;
    }

    /* =========================
       Helpers
       ========================= */

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getenv("COMPUTERNAME");
        }
    }

    private static List<String> enumerateNetworkInterfaces() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                list.add(ni.getName());
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static boolean detectContainer() {
        return new java.io.File("/.dockerenv").exists()
                || System.getenv("KUBERNETES_SERVICE_HOST") != null;
    }

    private static boolean detectVirtualization() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("vm") || os.contains("virtual");
    }

    private static boolean detectNonInteractive() {
        return System.console() == null;
    }

    private static String detectCIType() {
        Map<String, String> env = System.getenv();
        if (env.containsKey("GITHUB_ACTIONS")) return "GitHub Actions";
        if (env.containsKey("GITLAB_CI")) return "GitLab CI";
        if (env.containsKey("JENKINS_URL")) return "Jenkins";
        if (env.containsKey("TF_BUILD")) return "Azure DevOps";
        if (env.containsKey("CIRCLECI")) return "CircleCI";
        if (env.containsKey("BITBUCKET_BUILD_NUMBER")) return "Bitbucket Pipelines";
        if (env.keySet().stream().anyMatch(k -> k.startsWith("bamboo_"))) return "Bamboo";
        return "None";
    }

    private static String detectPrimaryInterface() {
        try {
            return NetworkInterface.getNetworkInterfaces().nextElement().getName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String detectMachineClass() {
        int cores = Runtime.getRuntime().availableProcessors();
        long mem = ((OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean())
                .getTotalMemorySize();
        return (cores >= 8 && mem >= 16L * 1024 * 1024 * 1024)
                ? "Server"
                : "Desktop";
    }

    private static String detectCloudProvider() {
        Map<String, String> env = System.getenv();
        if (env.containsKey("AWS_EXECUTION_ENV")) return "AWS";
        if (env.containsKey("AZURE_HTTP_USER_AGENT")) return "Azure";
        if (env.containsKey("GOOGLE_CLOUD_PROJECT")) return "GCP";
        return "Unknown";
    }

    private static String detectContainerOrchestrator() {
        if (System.getenv("KUBERNETES_SERVICE_HOST") != null) return "Kubernetes";
        return detectContainer() ? "Standalone Container" : "None";
    }

    private static List<String> detectFileSystemTypes() {
        List<String> types = new ArrayList<>();
        try {
            for (FileStore fs : FileSystems.getDefault().getFileStores()) {
                types.add(fs.type());
            }
        } catch (Exception ignored) {}
        return types;
    }

    private static String detectOSFamily() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "Windows";
        if (os.contains("mac")) return "macOS";
        if (os.contains("linux")) return "Linux";
        return "Other";
    }
}
