//package tools.dscode.coredefinitions;
//
//import io.cucumber.datatable.DataTable;
//import io.cucumber.java.en.Given;
//import org.openqa.selenium.chrome.ChromeDriver;
//import org.openqa.selenium.chrome.ChromeOptions;
//
//import java.util.List;
//import java.util.Map;
//import java.util.Objects;
//
//public class BrowserSteps {
//
//    // Thread-safe driver storage
//    private static final ThreadLocal<ChromeDriver> THREAD_LOCAL_DRIVER = new ThreadLocal<>();
//
//    /** Step definition that builds and registers a ChromeDriver for this thread. */
//    @Given("I launch Chrome with options:")
//    public void i_launch_chrome_with_options(DataTable table) {
//        ChromeOptions options = new ChromeOptions();
//
//        // Common safe defaults
//        options.addArguments("--start-maximized");
//        options.addArguments("--disable-notifications");
//        options.addArguments("--disable-infobars");
//        options.addArguments("--disable-extensions");
//        options.addArguments("--remote-allow-origins=*");
//
//        // Parse the DataTable
//        List<Map<String, String>> rows = table.asMaps(String.class, String.class);
//        for (Map<String, String> row : rows) {
//            String arg = Objects.toString(row.get("argument"), "").trim();
//            String opt = Objects.toString(row.get("option"), "").trim();
//            if (!arg.isEmpty()) {
//                System.out.println("Adding argument: " + arg);
//                options.addArguments(arg);
//            }
//
//            if (!opt.isEmpty()) {
//                System.out.println("Adding experimental option: " + opt);
//                options.setExperimentalOption(opt, true);
//            }
//        }
//
//        // Keep the browser open after test run
//        options.setExperimentalOption("detach", true);
//
//        ChromeDriver driver = new ChromeDriver(options);
//        THREAD_LOCAL_DRIVER.set(driver);
//
//        System.out.println("✅ ChromeDriver launched and stored in ThreadLocal.");
//    }
//
//    /** Retrieve the current thread's ChromeDriver. */
//    public static ChromeDriver getDriver() {
//        return THREAD_LOCAL_DRIVER.get();
//    }
//
//    /** Optional helper to clean up the current driver. */
//    public static void quitDriver() {
//        ChromeDriver driver = THREAD_LOCAL_DRIVER.get();
//        if (driver != null) {
//            driver.quit();
//            THREAD_LOCAL_DRIVER.remove();
//            System.out.println("🧹 ChromeDriver quit and removed from ThreadLocal.");
//        }
//    }
//}
