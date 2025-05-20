package io.pickleball.drivers;

import io.pickleball.cacheandstate.MethodCache;
import io.pickleball.cacheandstate.Registerable;
import org.openqa.selenium.*;
import org.openqa.selenium.bidi.BiDi;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.chromium.ChromiumNetworkConditions;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.federatedcredentialmanagement.FederatedCredentialManagementDialog;
import org.openqa.selenium.firefox.FirefoxCommandContext;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxDriverService;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.html5.LocalStorage;
import org.openqa.selenium.html5.Location;
import org.openqa.selenium.html5.SessionStorage;
import org.openqa.selenium.interactions.Sequence;
import org.openqa.selenium.logging.EventType;
import org.openqa.selenium.print.PrintOptions;
import org.openqa.selenium.remote.*;
import org.openqa.selenium.remote.http.ClientConfig;
import org.openqa.selenium.remote.service.DriverService;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.safari.SafariDriverService;
import org.openqa.selenium.safari.SafariOptions;
import org.openqa.selenium.virtualauthenticator.VirtualAuthenticator;
import org.openqa.selenium.virtualauthenticator.VirtualAuthenticatorOptions;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

public class UniversalWebDriver extends RemoteWebDriver implements Registerable {




    public final String name;

    @Override
    public String getRegisterableName() {
        return name;
    }

    public RemoteWebDriver getInternalDriver() {
        return internalDriver;
    }

    protected RemoteWebDriver internalDriver;
    protected Class runTimeClass;


//    private static ThreadLocal<Map<String, UniversalWebDriver>> instances = ThreadLocal.withInitial(HashMap::new);


//    public static UniversalWebDriver getUniversalWebDriver(String instanceName) {
//        return instances.get().computeIfAbsent(instanceName, k -> {
//            try {
//                return createUniversalWebDriver();
//            } catch (Exception e) {
//                throw new RuntimeException("Failed to create instance", e);
//            }
//        });
//    }
//


    public static UniversalWebDriver createUniversalWebDriver(Object... args) {
        if (args == null || args.length == 0) {
            return createChromeDriver();
        }

        return switch (args[0].getClass().getSimpleName()) {
            case "ChromeDriverService" -> switch (args.length) {
                case 1 -> createChromeDriver((ChromeDriverService) args[0]);
                case 2 -> createChromeDriver((ChromeDriverService) args[0], (ChromeOptions) args[1]);
                case 3 ->
                        createChromeDriver((ChromeDriverService) args[0], (ChromeOptions) args[1], (ClientConfig) args[2]);
                default -> throw new IllegalArgumentException("Invalid ChromeDriver arguments");
            };
            case "ChromeOptions" -> createChromeDriver((ChromeOptions) args[0]);
            case "EdgeDriverService" -> switch (args.length) {
                case 1 -> createEdgeDriver((EdgeDriverService) args[0]);
                case 2 -> createEdgeDriver((EdgeDriverService) args[0], (EdgeOptions) args[1]);
                case 3 -> createEdgeDriver((EdgeDriverService) args[0], (EdgeOptions) args[1], (ClientConfig) args[2]);
                default -> throw new IllegalArgumentException("Invalid EdgeDriver arguments");
            };
            case "EdgeOptions" -> createEdgeDriver((EdgeOptions) args[0]);
            case "FirefoxDriverService" -> switch (args.length) {
                case 1 -> createFirefoxDriver((FirefoxDriverService) args[0]);
                case 2 -> createFirefoxDriver((FirefoxDriverService) args[0], (FirefoxOptions) args[1]);
                case 3 ->
                        createFirefoxDriver((FirefoxDriverService) args[0], (FirefoxOptions) args[1], (ClientConfig) args[2]);
                default -> throw new IllegalArgumentException("Invalid FirefoxDriver arguments");
            };
            case "FirefoxOptions" -> createFirefoxDriver((FirefoxOptions) args[0]);
            case "SafariDriverService" -> switch (args.length) {
                case 1 -> createSafariDriver((SafariDriverService) args[0]);
                case 2 -> createSafariDriver((DriverService) args[0], (SafariOptions) args[1]);
                case 3 -> createSafariDriver((DriverService) args[0], (SafariOptions) args[1], (ClientConfig) args[2]);
                default -> throw new IllegalArgumentException("Invalid SafariDriver arguments");
            };
            case "SafariOptions" -> createSafariDriver((SafariOptions) args[0]);
            default -> throw new IllegalArgumentException("No matching WebDriver constructor found");
        };
    }


    public UniversalWebDriver(RemoteWebDriver remoteWebDriver) {
        this.internalDriver = remoteWebDriver;
        this.runTimeClass = remoteWebDriver.getClass();
        this.name = runTimeClass.getSimpleName().replaceAll("Driver", "");
        register();
    }


    private Object invokeMethod(String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            // Use MethodCache to get the Method object, passing the runtime class
            Method method = MethodCache.getMethod(runTimeClass, methodName, paramTypes);
            // Invoke the method on internalBase with the provided arguments
            return method.invoke(internalDriver, args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Exception in " + methodName, e.getCause());
        } catch (Exception e) {
            throw new RuntimeException("Error invoking " + methodName, e);
        }
    }


    @Override
    public Capabilities getCapabilities() {
        return (Capabilities) invokeMethod("getCapabilities", new Class<?>[0]);
    }

    @Override
    public ScriptKey pin(String script) {
        return internalDriver.pin(script);
    }

    @Override
    public Set<ScriptKey> getPinnedScripts() {
        return internalDriver.getPinnedScripts();
    }

    @Override
    public void unpin(ScriptKey key) {
        internalDriver.unpin(key);
    }

    @Override
    public Object executeScript(ScriptKey key, Object... args) {
        return internalDriver.executeScript(key, args);
    }

    @Override
    public void setFileDetector(FileDetector detector) {
        invokeMethod("setFileDetector", new Class<?>[]{FileDetector.class}, detector);
    }

    //    @Override
    public <X> void onLogEvent(EventType<X> kind) {
        ((ChromiumDriver) internalDriver).onLogEvent(kind);
    }

    //    @Override
    public void register(Predicate<URI> whenThisMatches, Supplier<Credentials> useTheseCredentials) {
        ((ChromiumDriver) internalDriver).register(whenThisMatches, useTheseCredentials);
    }

    //    @Override
    public LocalStorage getLocalStorage() {
        return (LocalStorage) invokeMethod("getLocalStorage", new Class<?>[0]);
    }

    //    @Override
    public SessionStorage getSessionStorage() {
        return (SessionStorage) invokeMethod("getSessionStorage", new Class<?>[0]);
    }

    //    @Override
    public Location location() {
        return ((ChromiumDriver) internalDriver).location();
    }

    //    @Override
    public void setLocation(Location location) {
        ((ChromiumDriver) internalDriver).setLocation(location);
    }

    //    @Override
    public void launchApp(String id) {
        ((ChromiumDriver) internalDriver).launchApp(id);
    }

    //    @Override
    public Map<String, Object> executeCdpCommand(String commandName, Map<String, Object> parameters) {
        return ((ChromiumDriver) internalDriver).executeCdpCommand(commandName, parameters);
    }

    //    @Override
    public Optional<DevTools> maybeGetDevTools() {
        return ((ChromiumDriver) internalDriver).maybeGetDevTools();
    }

    //    @Override
    public Optional<BiDi> maybeGetBiDi() {
        return (Optional<BiDi>) invokeMethod("maybeGetBiDi", new Class<?>[0]);
    }

    //    @Override
    public List<Map<String, String>> getCastSinks() {
        return ((ChromiumDriver) internalDriver).getCastSinks();
    }

    //    @Override
    public String getCastIssueMessage() {
        return ((ChromiumDriver) internalDriver).getCastIssueMessage();
    }

    //    @Override
    public void selectCastSink(String deviceName) {
        ((ChromiumDriver) internalDriver).selectCastSink(deviceName);
    }

    //    @Override
    public void startDesktopMirroring(String deviceName) {
        ((ChromiumDriver) internalDriver).startDesktopMirroring(deviceName);
    }

    //    @Override
    public void startTabMirroring(String deviceName) {
        ((ChromiumDriver) internalDriver).startTabMirroring(deviceName);
    }

    //    @Override
    public void stopCasting(String deviceName) {
        ((ChromiumDriver) internalDriver).stopCasting(deviceName);
    }

    //    @Override
    public void setPermission(String name, String value) {
        ((ChromiumDriver) internalDriver).setPermission(name, value);
    }

    //    @Override
    public ChromiumNetworkConditions getNetworkConditions() {
        return ((ChromiumDriver) internalDriver).getNetworkConditions();
    }

    //    @Override
    public void setNetworkConditions(ChromiumNetworkConditions networkConditions) {
        ((ChromiumDriver) internalDriver).setNetworkConditions(networkConditions);
    }

    //    @Override
    public void deleteNetworkConditions() {
        ((ChromiumDriver) internalDriver).deleteNetworkConditions();
    }

    @Override
    public void quit() {
        invokeMethod("quit", new Class<?>[0]);
    }
//    ChromiumDriver end


    //    ChromeDriver start
    public static UniversalWebDriver createChromeDriver() {
        return new UniversalWebDriver(new ChromeDriver());
    }

    public static UniversalWebDriver createChromeDriver(ChromeDriverService service) {
        return new UniversalWebDriver(new ChromeDriver(service));
    }

    public static UniversalWebDriver createChromeDriver(ChromeOptions options) {
        return new UniversalWebDriver(new ChromeDriver(options));
    }

    public static UniversalWebDriver createChromeDriver(ChromeDriverService service, ChromeOptions options) {
        return new UniversalWebDriver(new ChromeDriver(service, options));
    }

    public static UniversalWebDriver createChromeDriver(ChromeDriverService service, ChromeOptions options, ClientConfig clientConfig) {
        return new UniversalWebDriver(new ChromeDriver(service, options, clientConfig));
    }


    //    EdgeDriver start
    public static UniversalWebDriver createEdgeDriver() {
        return new UniversalWebDriver(new EdgeDriver());
    }

    public static UniversalWebDriver createEdgeDriver(EdgeOptions options) {
        return new UniversalWebDriver(new EdgeDriver(options));
    }

    public static UniversalWebDriver createEdgeDriver(EdgeDriverService service) {
        return new UniversalWebDriver(new EdgeDriver(service));
    }

    public static UniversalWebDriver createEdgeDriver(EdgeDriverService service, EdgeOptions options) {
        return new UniversalWebDriver(new EdgeDriver(service, options));
    }

    public static UniversalWebDriver createEdgeDriver(EdgeDriverService service, EdgeOptions options, ClientConfig clientConfig) {
        return new UniversalWebDriver(new EdgeDriver(service, options, clientConfig));
    }


    //    FirefoxDriver start
    public static UniversalWebDriver createFirefoxDriver() {
        return new UniversalWebDriver(new FirefoxDriver());
    }

    public static UniversalWebDriver createFirefoxDriver(FirefoxOptions options) {
        return new UniversalWebDriver(new FirefoxDriver(options));
    }

    public static UniversalWebDriver createFirefoxDriver(FirefoxDriverService service) {
        return new UniversalWebDriver(new FirefoxDriver(service));
    }

    public static UniversalWebDriver createFirefoxDriver(FirefoxDriverService service, FirefoxOptions options) {
        return new UniversalWebDriver(new FirefoxDriver(service, options));
    }

    public static UniversalWebDriver createFirefoxDriver(FirefoxDriverService service, FirefoxOptions options, ClientConfig clientConfig) {
        return new UniversalWebDriver(new FirefoxDriver(service, options, clientConfig));
    }

    //    @Override
    public String installExtension(Path path) {
        return ((FirefoxDriver) internalDriver).installExtension(path);
    }

    //    @Override
    public String installExtension(Path path, Boolean temporary) {
        return ((FirefoxDriver) internalDriver).installExtension(path, temporary);
    }

    //    @Override
    public void uninstallExtension(String extensionId) {
        ((FirefoxDriver) internalDriver).uninstallExtension(extensionId);
    }

    //    @Override
    public <X> X getFullPageScreenshotAs(OutputType<X> outputType) throws WebDriverException {
        return ((FirefoxDriver) internalDriver).getFullPageScreenshotAs(outputType);
    }

    //    @Override
    public FirefoxCommandContext getContext() {
        return ((FirefoxDriver) internalDriver).getContext();
    }

    //    @Override
    public void setContext(FirefoxCommandContext commandContext) {
        ((FirefoxDriver) internalDriver).setContext(commandContext);
    }


    //    @Override
    public BiDi getBiDi() {
        return ((FirefoxDriver) internalDriver).getBiDi();
    }


    //safari start
//    public UniversalWebDriver() {
    public static UniversalWebDriver createSafariDriver() {
        return new UniversalWebDriver(new SafariDriver());
    }

    public static UniversalWebDriver createSafariDriver(SafariOptions safariOptions) {
        return new UniversalWebDriver(new SafariDriver(safariOptions));
    }

    public static UniversalWebDriver createSafariDriver(SafariDriverService safariService) {
        return new UniversalWebDriver(new SafariDriver(safariService));
    }

    public static UniversalWebDriver createSafariDriver(DriverService service, SafariOptions options) {
        return new UniversalWebDriver(new SafariDriver(service, options));
    }

    public static UniversalWebDriver createSafariDriver(DriverService service, SafariOptions options, ClientConfig clientConfig) {
        return new UniversalWebDriver(new SafariDriver(service, options, clientConfig));
    }

    //    @Override
    public void setPermissions(String permission, boolean value) {
        ((SafariDriver) internalDriver).setPermissions(permission, value);
    }

    //    @Override
    public Map<String, Boolean> getPermissions() {
        return ((SafariDriver) internalDriver).getPermissions();
    }

    //    @Override
    public void attachDebugger() {
        ((SafariDriver) internalDriver).attachDebugger();
    }




    // start of RemoteWebDriver method overrides

    @Override
    public SessionId getSessionId() {
        return internalDriver.getSessionId();
    }

    @Override
    protected void setSessionId(String opaqueKey) {
        invokeMethod("setSessionId", new Class<?>[]{String.class}, opaqueKey);
//        internalDriver.setSessionId(opaqueKey);
    }

    @Override
    protected void startSession(Capabilities capabilities) {
        invokeMethod("startSession", new Class<?>[]{Capabilities.class}, capabilities);
    }

    @Override
    public ErrorHandler getErrorHandler() {
        return (ErrorHandler) invokeMethod("getErrorHandler", new Class<?>[0]);
    }

    @Override
    public void setErrorHandler(ErrorHandler handler) {
        invokeMethod("setErrorHandler", new Class<?>[]{ErrorHandler.class}, handler);
    }

    @Override
    public CommandExecutor getCommandExecutor() {
        return (CommandExecutor) invokeMethod("getCommandExecutor", new Class<?>[0]);
    }

    @Override
    protected void setCommandExecutor(CommandExecutor executor) {
        invokeMethod("setCommandExecutor", new Class<?>[]{CommandExecutor.class}, executor);
    }

    @Override
    public void get(String url) {
        internalDriver.get(url);
    }

    @Override
    public String getTitle() {
        return internalDriver.getTitle();
    }

    @Override
    public String getCurrentUrl() {
        return internalDriver.getCurrentUrl();
    }

    @Override
    public <X> X getScreenshotAs(OutputType<X> outputType) throws WebDriverException {
        return internalDriver.getScreenshotAs(outputType);
    }

    @Override
    public Pdf print(PrintOptions printOptions) throws WebDriverException {
        return internalDriver.print(printOptions);
    }

    @Override
    public WebElement findElement(By locator) {
        return internalDriver.findElement(locator);
    }

    @Override
    public List<WebElement> findElements(By locator) {
        return internalDriver.findElements(locator);
    }

    @Override
    public List<WebElement> findElements(SearchContext context, BiFunction<String, Object, CommandPayload> findCommand, By locator) {
        return internalDriver.findElements(context, findCommand, locator);
    }

    @Override
    protected void setFoundBy(SearchContext context, WebElement element, String by, String using) {
        invokeMethod("setFoundBy", new Class<?>[]{SearchContext.class, WebElement.class, String.class, String.class}, context, element, by, using);
    }

    @Override
    public String getPageSource() {
        return internalDriver.getPageSource();
    }

    @Override
    public void close() {
        internalDriver.close();
    }

    @Override
    public Set<String> getWindowHandles() {
        return internalDriver.getWindowHandles();
    }

    @Override
    public String getWindowHandle() {
        return internalDriver.getWindowHandle();
    }

    @Override
    public Object executeScript(String script, Object... args) {
        return internalDriver.executeScript(script, args);
    }

    @Override
    public Object executeAsyncScript(String script, Object... args) {
        return internalDriver.executeAsyncScript(script, args);
    }

    @Override
    public TargetLocator switchTo() {
        return internalDriver.switchTo();
    }

    @Override
    public Navigation navigate() {
        return internalDriver.navigate();
    }

    @Override
    public Options manage() {
        return internalDriver.manage();
    }

    @Override
    public Script script() {
        return internalDriver.script();
    }

    @Override
    public Network network() {
        return internalDriver.network();
    }

    @Override
    protected JsonToWebElementConverter getElementConverter() {
        return (JsonToWebElementConverter) invokeMethod("getElementConverter", new Class<?>[0]);
    }

    @Override
    protected void setElementConverter(JsonToWebElementConverter converter) {
        invokeMethod("setElementConverter", new Class<?>[]{JsonToWebElementConverter.class}, converter);
    }

    @Override
    public void setLogLevel(Level level) {
        internalDriver.setLogLevel(level);
    }

    @Override
    protected Response execute(CommandPayload payload) {
        return (Response) invokeMethod("execute", new Class<?>[]{CommandPayload.class}, payload);
    }

    @Override
    protected Response execute(String driverCommand, Map<String, ?> parameters) {
        return (Response) invokeMethod("execute", new Class<?>[]{String.class, Map.class}, driverCommand,parameters);
    }

    @Override
    protected Response execute(String command) {
        return (Response) invokeMethod("execute", new Class<?>[]{String.class}, command);
    }

    @Override
    protected ExecuteMethod getExecuteMethod() {
        return (ExecuteMethod) invokeMethod("getExecuteMethod", new Class<?>[0]);
    }

    @Override
    public void perform(Collection<Sequence> actions) {
        internalDriver.perform(actions);
    }

    @Override
    public void resetInputState() {
        internalDriver.resetInputState();
    }

    @Override
    public VirtualAuthenticator addVirtualAuthenticator(VirtualAuthenticatorOptions options) {
        return internalDriver.addVirtualAuthenticator(options);
    }

    @Override
    public void removeVirtualAuthenticator(VirtualAuthenticator authenticator) {
        internalDriver.removeVirtualAuthenticator(authenticator);
    }

    @Override
    public List<String> getDownloadableFiles() {
        return internalDriver.getDownloadableFiles();
    }

    @Override
    public void downloadFile(String fileName, Path targetLocation) throws IOException {
        internalDriver.downloadFile(fileName, targetLocation);
    }

    @Override
    public void deleteDownloadableFiles() {
        internalDriver.deleteDownloadableFiles();
    }

    @Override
    public void setDelayEnabled(boolean enabled) {
        internalDriver.setDelayEnabled(enabled);
    }

    @Override
    public void resetCooldown() {
        internalDriver.resetCooldown();
    }

    @Override
    public FederatedCredentialManagementDialog getFederatedCredentialManagementDialog() {
        return internalDriver.getFederatedCredentialManagementDialog();
    }

    @Override
    protected void log(SessionId sessionId, String commandName, Object toLog, When when) {
        invokeMethod("log", new Class<?>[]{SessionId.class, String.class, Object.class, When.class}, sessionId, commandName, toLog, when);
    }

    @Override
    public FileDetector getFileDetector() {
        return internalDriver.getFileDetector();
    }

    @Override
    public String toString() {
        return internalDriver.toString();
    }

    @Override
    public void requireDownloadsEnabled(Capabilities capabilities) {
        internalDriver.requireDownloadsEnabled(capabilities);
    }


    ///// end of remote methods





}
