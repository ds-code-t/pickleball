package tools.dscode.dolt.install;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Pinned Dolt 2.7.3 asset URLs and SHA-256 digests.
 */
public final class DoltReleaseCatalog {
    public static final String VERSION = "2.7.3";
    private static final String RESOURCE = "META-INF/pickleball/dolt-release.properties";

    public record Asset(String osArch, String fileName, String url, String sha256) {
    }

    private DoltReleaseCatalog() {
    }

    public static Asset forCurrentPlatform() {
        return require(DoltPlatform.osArch());
    }

    public static Asset require(String osArch) {
        Properties properties = load();
        String fileName = properties.getProperty("asset." + osArch);
        String url = properties.getProperty("url." + osArch);
        String sha256 = properties.getProperty("sha256." + osArch);
        if (fileName == null || url == null || sha256 == null || sha256.isBlank()) {
            throw new IllegalStateException("No pinned Dolt " + VERSION + " asset for " + osArch);
        }
        return new Asset(osArch, fileName, url, sha256.trim().toLowerCase());
    }

    public static Properties load() {
        Properties properties = new Properties();
        try (InputStream input = DoltReleaseCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + RESOURCE, failure);
        }
        String version = properties.getProperty("version");
        if (!VERSION.equals(version)) {
            throw new IllegalStateException("dolt-release.properties version was " + version + ", expected " + VERSION);
        }
        return properties;
    }
}
