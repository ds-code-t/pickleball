package tools.dscode.launcher;

import com.microsoft.playwright.impl.driver.Driver;
import tools.dscode.browser.PlaywrightConnection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Node that ships inside Playwright's driver-bundle, found by the driver's own resolution
 * ({@code Driver.ensureDriverInstalled(env, installBrowsers=false)}). Pickleball never needs a user Node install.
 * Runtime routes do not run npm. The workbench UI packager runs npm at build time with this same binary.
 */
final class PlaywrightNode {
    private PlaywrightNode() {
    }

    static Optional<Path> locate() {
        try {
            Driver driver = Driver.ensureDriverInstalled(PlaywrightConnection.DRIVER_ENV, false);
            return Optional.of(driver.driverDir());
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    /** The driver-bundle {@code node} binary, or null when the driver is not installed. */
    static Path nodeBinary() {
        Optional<Path> driver = locate();
        if (driver.isEmpty()) return null;
        List<String> command = command(driver.get(), List.of());
        if (command.isEmpty() || command.get(0) == null || command.get(0).isBlank()) return null;
        Path node = Path.of(command.get(0));
        return Files.isRegularFile(node) ? node : null;
    }

    /** {@code <node> <driver>/package/cli.js <args>} from the driver's own process builder. */
    static List<String> command(Path driverDir, List<String> args) {
        Driver driver = Driver.ensureDriverInstalled(PlaywrightConnection.DRIVER_ENV, false);
        List<String> command = new ArrayList<>(driver.createProcessBuilder().command());
        command.addAll(args);
        return command;
    }
}
