package tools.dscode.dolt.install;

/**
 * Operating system and architecture key used to pick a pinned Dolt asset.
 */
public final class DoltPlatform {
    private DoltPlatform() {
    }

    public static String osArch() {
        return osArch(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static String osArch(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase();
        String arch = osArch == null ? "" : osArch.toLowerCase();
        String osKey;
        if (os.contains("win")) osKey = "windows";
        else if (os.contains("mac") || os.contains("darwin")) osKey = "darwin";
        else osKey = "linux";
        String archKey = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "amd64";
        return osKey + "-" + archKey;
    }

    public static String binaryName() {
        return binaryName(System.getProperty("os.name", ""));
    }

    public static String binaryName(String osName) {
        String os = osName == null ? "" : osName.toLowerCase();
        return os.contains("win") ? "dolt.exe" : "dolt";
    }

    public static boolean windows(String osName) {
        return osName != null && osName.toLowerCase().contains("win");
    }
}
