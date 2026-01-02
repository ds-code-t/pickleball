package tools.dscode.common.util;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class FileUploadUtil {

    private FileUploadUtil() {}

    public static void upload(WebDriver driver, WebElement fileInput, String fileRef) {
        enableRemoteIfNeeded(driver);

        Path file = resolve(fileRef);

        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Upload file not found: " + file);
        }

        fileInput.sendKeys(file.toString());
    }

    private static Path resolve(String fileRef) {
        Path path = Paths.get(fileRef);

        if (path.isAbsolute()) {
            return path.normalize();
        }

        // filename only → resources/files/<name>
        if (path.getParent() == null && !fileRef.contains("/") && !fileRef.contains("\\")) {
            return Paths.get("resources", "files", fileRef).toAbsolutePath().normalize();
        }

        // relative path → resources/<path>
        return Paths.get("resources").resolve(path).toAbsolutePath().normalize();
    }

    private static void enableRemoteIfNeeded(WebDriver driver) {
        if (driver instanceof RemoteWebDriver rwd) {
            rwd.setFileDetector(new LocalFileDetector());
        }
    }
}
