package tools.dscode.browser;

import java.util.List;
import java.util.Map;

/**
 * The only browser wire G3 producers and G4 popup code call.
 * The scripted library path is {@link SeleniumAdapter}.
 * Direct sockets ({@code CdpAdapter}, {@code BidiAdapter}) and {@code PlaywrightAdapter}
 * implement this same interface. Callers do not use Selenium's reflective
 * {@code executeCdpCommand}.
 */
public interface BrowserFacade {
    /**
     * {@code selenium}, {@code playwright}, {@code cdp}, or {@code bidi}.
     */
    String protocol();

    /**
     * One protocol method. {@code params} may be null.
     * The return value is the protocol result (map, list, scalar, or null).
     */
    Object send(String method, Map<String, ?> params);

    /**
     * Evaluate {@code fn} in an isolated world named {@code worldName}.
     * {@code fn} is a function declaration or an expression. {@code args} are its arguments.
     */
    Object evaluateIsolated(String worldName, String fn, Object... args);

    /** Install {@code script} so later documents run it before page scripts. */
    void addPreload(String script);

    /** Register {@code name}. Events are pushed to {@code listener}. There is no poll. */
    void addBinding(String name, BindingListener listener);

    /** PNG bytes. {@code options} may be null. */
    byte[] screenshot(Map<String, ?> options);

    /** Frame or browsing-context tree. The first node is the root. */
    FrameNode frameTree();

    /**
     * Library locator. {@code using} is {@code css}, {@code xpath}, {@code id},
     * {@code name}, {@code link text}, or {@code tag name}.
     * Returned handles are valid for {@link #click(String)} and {@link #type(String, String)}
     * on this same adapter.
     */
    List<String> find(String using, String value);

    void click(String handle);

    void type(String handle, String text);
}
