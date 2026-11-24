package tools.dscode.launcher;

import com.microsoft.playwright.impl.driver.Driver;
import tools.dscode.browser.PlaywrightConnection;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Node that ships inside Playwright's driver-bundle, found by the driver's own resolution
 * ({@code Driver.ensureDriverInstalled(env, installBrowsers=false)}). Pickleball never needs a user Node install and
 * never runs npm. Used only for routes that exist only in the bundled Playwright CLI (the extension attach).
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

    /** {@code <node> <driver>/package/cli.js <args>} from the driver's own process builder. */
    static List<String> command(Path driverDir, List<String> args) {
        Driver driver = Driver.ensureDriverInstalled(PlaywrightConnection.DRIVER_ENV, false);
        List<String> command = new ArrayList<>(driver.createProcessBuilder().command());
        command.addAll(args);
        return command;
    }
}
