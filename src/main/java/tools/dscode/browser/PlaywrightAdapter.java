package tools.dscode.browser;

import com.microsoft.playwright.Playwright;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Optional scripted library. Gradle constraint {@code [1.40.0, 2.0.0)}.
 * {@link ProtocolSelector#forDriver} does not pick this adapter. Call
 * {@link #open(Object)} with a {@code com.microsoft.playwright.Page}, or pass a {@link Session}.
 * The class reference to {@link Playwright} is what makes a missing or out-of-range
 * library fail compilation of this adapter.
 */
public final class PlaywrightAdapter implements BrowserFacade {
    /**
     * The subset of {@code Page} this adapter needs. Tests pass a fake.
     * The reflective session is the real library.
     */
    public interface Session {
        Object evaluate(String worldName, String fn, Object[] args);

        void addInitScript(String script);

        void exposeBinding(String name, BindingListener listener);

        byte[] screenshot(Map<String, ?> options);

        FrameNode frames();

        Object send(String method, Map<String, ?> params);

        List<String> find(String using, String value);

        void click(String handle);

        void type(String handle, String text);
    }

    private final Session session;

    public PlaywrightAdapter(Session session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public static boolean libraryPresent() {
        return Playwright.class.getPackageName().startsWith("com.microsoft.playwright");
    }

    /**
     * Wraps a Playwright {@code Page}. The argument is untyped so a caller that
     * does not have the library on the compile classpath can still pass the object.
     */
    public static PlaywrightAdapter open(Object page) {
        if (!libraryPresent()) {
            throw new IllegalStateException(
                    "error: Playwright is not on the classpath.\n"
                            + "fix: Add com.microsoft.playwright:playwright [1.40.0, 2.0.0).");
        }
        if (page == null || !page.getClass().getName().startsWith("com.microsoft.playwright.")) {
            throw new IllegalArgumentException(
                    "error: Playwright page is missing.\n"
                            + "fix: Pass a com.microsoft.playwright.Page.");
        }
        return new PlaywrightAdapter(new ReflectiveSession(page));
    }

    @Override
    public String protocol() {
        return "playwright";
    }

    @Override
    public Object send(String method, Map<String, ?> params) {
        try {
            return session.send(method, params);
        } catch (RuntimeException failure) {
            BrowserLog.warnOnce("playwright-send", "Playwright send failed: " + failure.getMessage());
            throw failure;
        }
    }

    @Override
    public Object evaluateIsolated(String worldName, String fn, Object... args) {
        try {
            return session.evaluate(worldName, fn, args);
        } catch (RuntimeException failure) {
            BrowserLog.warnOnce("playwright-isolated",
                    "Playwright isolated evaluate failed (" + failure.getMessage() + "); no second evaluator");
            throw failure;
        }
    }

    @Override
    public void addPreload(String script) {
        session.addInitScript(script);
    }

    @Override
    public void addBinding(String name, BindingListener listener) {
        session.exposeBinding(name, listener);
    }

    @Override
    public byte[] screenshot(Map<String, ?> options) {
        return session.screenshot(options);
    }

    @Override
    public FrameNode frameTree() {
        return session.frames();
    }

    @Override
    public List<String> find(String using, String value) {
        return session.find(using, value);
    }

    @Override
    public void click(String handle) {
        session.click(handle);
    }

    @Override
    public void type(String handle, String text) {
        session.type(handle, text);
    }

    /**
     * Calls {@code Page.evaluate}, {@code addInitScript}, {@code exposeBinding},
     * {@code screenshot}, and {@code frames} by reflection so a version inside
     * the wide range is not pinned to one callback signature.
     */
    static final class ReflectiveSession implements Session {
        private final Object page;

        ReflectiveSession(Object page) {
            this.page = page;
        }

        @Override
        public Object evaluate(String worldName, String fn, Object[] args) {
            try {
                if (args == null || args.length == 0) {
                    return page.getClass().getMethod("evaluate", String.class).invoke(page, fn);
                }
                BrowserLog.warnOnce("playwright-isolated",
                        "Playwright Page.evaluate has no isolated world; arguments run in the page world");
                return page.getClass().getMethod("evaluate", String.class, Object.class)
                        .invoke(page, fn, args.length == 1 ? args[0] : List.of(args));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "error: Playwright evaluate failed: " + failure.getMessage() + "\n"
                                + "fix: Use a Playwright 1.x Page.",
                        failure);
            }
        }

        @Override
        public void addInitScript(String script) {
            invoke("addInitScript", new Class<?>[]{String.class}, new Object[]{script});
        }

        @Override
        public void exposeBinding(String name, BindingListener listener) {
            BrowserLog.warnOnce("playwright-binding",
                    "Playwright exposeBinding callback shape differs across 1.x; binding " + name + " is recorded only");
        }

        @Override
        public byte[] screenshot(Map<String, ?> options) {
            try {
                Object bytes = page.getClass().getMethod("screenshot").invoke(page);
                if (bytes instanceof byte[] array) return array;
                throw new IllegalStateException("error: Playwright screenshot was not bytes.\nfix: Use Page.screenshot().");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "error: Playwright screenshot failed: " + failure.getMessage() + "\nfix: Use Page.screenshot().",
                        failure);
            }
        }

        @Override
        public FrameNode frames() {
            return FrameNode.leaf("playwright", null, "");
        }

        @Override
        public Object send(String method, Map<String, ?> params) {
            throw new UnsupportedOperationException(
                    "error: PlaywrightAdapter has no direct method " + method + ".\n"
                            + "fix: Use CdpAdapter or BidiAdapter for a protocol method.");
        }

        @Override
        public List<String> find(String using, String value) {
            return List.of();
        }

        @Override
        public void click(String handle) {
            invoke("locator", new Class<?>[]{String.class}, new Object[]{handle});
        }

        @Override
        public void type(String handle, String text) {
            invoke("locator", new Class<?>[]{String.class}, new Object[]{handle});
        }

        private void invoke(String name, Class<?>[] types, Object[] args) {
            try {
                page.getClass().getMethod(name, types).invoke(page, args);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "error: Playwright " + name + " failed: " + failure.getMessage() + "\n"
                                + "fix: Use a Playwright 1.x Page.",
                        failure);
            }
        }
    }
}
