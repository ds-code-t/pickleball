package tools.dscode.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.bidi.BiDi;
import org.openqa.selenium.bidi.Command;
import org.openqa.selenium.bidi.Connection;
import org.openqa.selenium.bidi.Event;
import org.openqa.selenium.bidi.HasBiDi;
import org.openqa.selenium.bidi.network.BaseParameters;
import org.openqa.selenium.bidi.network.FetchTimingInfo;
import org.openqa.selenium.bidi.network.RequestData;
import org.openqa.selenium.bidi.network.ResponseData;
import org.openqa.selenium.bidi.network.ResponseDetails;
import org.openqa.selenium.bidi.script.EvaluateResult;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.WebSocket;
import tools.dscode.common.agentcontrol.AgentDriverHooks;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bidi script} and {@code bidi intercept} on the driver
 * {@link AgentDriverHooks#ready} registered. No browser is downloaded.
 */
class SeleniumBidiLiveSessionTest {
    @AfterEach
    void reset() {
        System.clearProperty("pkb_selenium_bidi");
        System.clearProperty("pkb_protocol");
        SeleniumBidiCommands.resetForTests();
        clearRegisteredDriver();
    }

    /** Drops the stub from the session registry. Page cleanup needs a live browser this test does not start. */
    private static void clearRegisteredDriver() {
        try {
            Method clear = Class.forName("tools.dscode.common.agentcontrol.PageSurfaces").getDeclaredMethod("clearDriver");
            clear.setAccessible(true);
            clear.invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void liveSessionEvaluatesScriptAndReplacesTheResponseBody() {
        System.setProperty("pkb_selenium_bidi", "on");
        RecordingBiDi bidi = new RecordingBiDi();
        AgentDriverHooks.ready(new SeleniumSessionDriver(bidi));

        Capture script = run("bidi", "script", "--source=return 1");
        assertEquals(0, script.code, script.err);
        assertTrue(bidi.methods.contains("script.evaluate"), bidi.methods.toString());
        assertTrue(bidi.params.stream().anyMatch(text -> text.contains("return 1")), bidi.params.toString());

        Capture intercept = run("bidi", "intercept", "--url=http://127.0.0.1/", "--body=hello");
        assertEquals(0, intercept.code, intercept.err);
        assertTrue(bidi.methods.contains("network.addIntercept"), bidi.methods.toString());
        assertTrue(bidi.methods.contains("network.provideResponse"), bidi.methods.toString());
        assertTrue(bidi.params.stream().anyMatch(text -> text.contains("hello") && text.contains("req-1")),
                bidi.params.toString());
    }

    @Test
    void playwrightSessionRefusesIntercept() {
        System.setProperty("pkb_selenium_bidi", "on");
        System.setProperty("pkb_protocol", "playwright");
        AgentDriverHooks.ready(new PlaywrightBoundDriver());

        Capture intercept = run("bidi", "intercept", "--url=http://127.0.0.1/", "--body=hello");
        assertEquals(2, intercept.code, intercept.err);
        assertTrue(intercept.err.contains("error:"));
        assertTrue(intercept.err.contains("Playwright"));
        assertTrue(intercept.err.contains("fix:"));
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SeleniumBidiCommands.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }

    /** Records the BiDi commands Selenium's script and network modules send. */
    static final class RecordingBiDi extends BiDi {
        final List<String> methods = new ArrayList<>();
        final List<String> params = new ArrayList<>();
        private final EvaluateResult result = (EvaluateResult) Proxy.newProxyInstance(
                EvaluateResult.class.getClassLoader(),
                new Class<?>[]{EvaluateResult.class},
                (proxy, method, args) -> {
                    if ("getRealmId".equals(method.getName())) return "realm";
                    if (method.getReturnType().isEnum()) {
                        Object[] constants = method.getReturnType().getEnumConstants();
                        return constants.length == 0 ? null : constants[0];
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                });

        RecordingBiDi() {
            super(new Connection(new QuietClient(), "ws://127.0.0.1/session"));
        }

        @Override
        public <X> X send(Command<X> command) {
            methods.add(command.getMethod());
            params.add(String.valueOf(command.getParams()));
            if ("network.addIntercept".equals(command.getMethod())) return typed("intercept-1");
            if ("script.evaluate".equals(command.getMethod())) return typed(result);
            return null;
        }

        @Override
        public <X> String addListener(Event<X> event, Consumer<X> handler) {
            if (handler != null && "network.responseStarted".equals(event.getMethod())) {
                @SuppressWarnings("unchecked")
                Consumer<ResponseDetails> typed = (Consumer<ResponseDetails>) handler;
                typed.accept(responseStarted());
            }
            return "sub-1";
        }

        @SuppressWarnings("unchecked")
        private static <X> X typed(Object value) {
            return (X) value;
        }

        private static ResponseDetails responseStarted() {
            try {
                FetchTimingInfo timings = (FetchTimingInfo) declared(FetchTimingInfo.class,
                        double.class, double.class, double.class, double.class, double.class,
                        double.class, double.class, double.class, double.class, double.class,
                        double.class, double.class, double.class)
                        .newInstance(0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d);
                RequestData request = new RequestData("req-1", "http://127.0.0.1/", "GET",
                        List.of(), List.of(), 0L, 0L, "document", null, timings);
                BaseParameters base = (BaseParameters) declared(BaseParameters.class,
                        String.class, boolean.class, String.class, long.class, RequestData.class, long.class, List.class)
                        .newInstance("ctx-1", Boolean.TRUE, null, 0L, request, 0L, List.of("intercept-1"));
                ResponseData response = (ResponseData) declared(ResponseData.class,
                        String.class, String.class, int.class, String.class, boolean.class, List.class,
                        String.class, long.class, Long.class, Long.class, Long.class,
                        org.openqa.selenium.bidi.network.AuthChallenge.class)
                        .newInstance("http://127.0.0.1/", "http/1.1", 200, "OK", Boolean.FALSE, List.of(),
                                "text/plain", 0L, 0L, 0L, null, null);
                return (ResponseDetails) declared(ResponseDetails.class, BaseParameters.class, ResponseData.class)
                        .newInstance(base, response);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        private static Constructor<?> declared(Class<?> type, Class<?>... parameters) throws NoSuchMethodException {
            Constructor<?> constructor = type.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor;
        }
    }

    private static final class QuietClient implements HttpClient {
        @Override
        public WebSocket openSocket(HttpRequest request, WebSocket.Listener listener) {
            return new WebSocket() {
                @Override
                public WebSocket send(org.openqa.selenium.remote.http.Message message) {
                    return this;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public HttpResponse execute(HttpRequest request) {
            return new HttpResponse().setStatus(200);
        }
    }

    private static class WindowDriver implements WebDriver {
        @Override
        public void get(String url) {
        }

        @Override
        public String getCurrentUrl() {
            return "http://127.0.0.1/";
        }

        @Override
        public String getTitle() {
            return "";
        }

        @Override
        public List<WebElement> findElements(By by) {
            return List.of();
        }

        @Override
        public WebElement findElement(By by) {
            throw new UnsupportedOperationException("find");
        }

        @Override
        public String getPageSource() {
            return "";
        }

        @Override
        public void close() {
        }

        @Override
        public void quit() {
        }

        @Override
        public Set<String> getWindowHandles() {
            return Set.of("ctx-1");
        }

        @Override
        public String getWindowHandle() {
            return "ctx-1";
        }

        @Override
        public TargetLocator switchTo() {
            return face(TargetLocator.class);
        }

        @Override
        public Navigation navigate() {
            return face(Navigation.class);
        }

        @Override
        public Options manage() {
            return face(Options.class);
        }

        private static <T> T face(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                Class<?> returns = method.getReturnType();
                if (returns == boolean.class) return false;
                if (returns == int.class) return 0;
                if (returns == long.class) return 0L;
                return null;
            }));
        }
    }

    private static final class SeleniumSessionDriver extends WindowDriver implements HasBiDi {
        private final BiDi bidi;

        private SeleniumSessionDriver(BiDi bidi) {
            this.bidi = bidi;
        }

        @Override
        public BiDi getBiDi() {
            return bidi;
        }

        @Override
        public Optional<BiDi> maybeGetBiDi() {
            return Optional.of(bidi);
        }

        @Override
        public org.openqa.selenium.bidi.Handle getHandle() {
            return bidi.asHandle();
        }
    }

    /** A registered driver that is not a Selenium BiDi session. */
    private static final class PlaywrightBoundDriver extends WindowDriver {
    }
}
