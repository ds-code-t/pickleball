package tools.dscode.common.domoperations;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A click waits only while a loading mask covers that click, or a loading modal
 * or backdrop is the ancestor of whatever is at that point. A busy bar somewhere
 * else does not hold the click. Each page check is bounded so a stuck script
 * cannot stall the caller.
 */
public final class MaskQuiet {
    private MaskQuiet() {
    }

    public record Wait(long waitedMs, boolean maskCleared) {
    }

    /** {@code this} or {@code arguments[0]} is the click target. Returns true while a covering mask is showing. */
    public static final String COVERS = """
            var target = (typeof arguments !== 'undefined' && arguments.length > 0 && arguments[0] && arguments[0].nodeType === 1)
                ? arguments[0] : this;
            if (!target || !target.getBoundingClientRect) return false;
            var rect = target.getBoundingClientRect();
            if (rect.width < 1 || rect.height < 1) return false;
            var x = rect.left + rect.width / 2;
            var y = rect.top + rect.height / 2;
            function tokens(value) {
              var raw = String(value || '').trim();
              if (!raw) return [];
              return raw.split(/\\s+/);
            }
            function hasToken(value, token) {
              var parts = tokens(value);
              for (var i = 0; i < parts.length; i++) if (parts[i] === token) return true;
              return false;
            }
            function shown(el) {
              if (!el || el.nodeType !== 1) return false;
              var style = getComputedStyle(el);
              if (style.display === 'none' || style.visibility === 'hidden' || el.hidden) return false;
              var box = el.getBoundingClientRect();
              return box.width >= 1 && box.height >= 1;
            }
            function isMask(el) {
              if (!el || el.nodeType !== 1) return false;
              var role = (el.getAttribute('role') || '').toLowerCase();
              if (el.getAttribute('aria-busy') === 'true') return true;
              if (role === 'progressbar') return true;
              if (el.getAttribute('data-loading') === 'true') return true;
              if (el.getAttribute('data-state') === 'loading') return true;
              if (role === 'status' && el.getAttribute('aria-busy') === 'true') return true;
              if (hasToken(el.getAttribute('data-testid'), 'loading') || hasToken(el.getAttribute('data-testid'), 'spinner')) return true;
              if ((el.getAttribute('data-loader') || '') === 'spinner') return true;
              var classText = String(el.getAttribute('class') || '').toLowerCase();
              if (classText.indexOf('spinner') >= 0 || classText.indexOf('loading') >= 0) return true;
              var classParts = tokens(classText);
              for (var c = 0; c < classParts.length; c++) {
                if (classParts[c] === 'mask' || classParts[c] === 'busy' || classParts[c] === 'modal-mask') return true;
              }
              var tag = el.tagName;
              if (tag === 'METER') return el.getAttribute('aria-busy') === 'true';
              if (tag === 'PROGRESS') {
                if (el.getAttribute('aria-busy') === 'true' || !el.hasAttribute('value')) return true;
                var value = Number(el.value);
                var max = el.hasAttribute('max') ? Number(el.max) : 1;
                return value < max;
              }
              return false;
            }
            function isModal(el) {
              var role = (el.getAttribute('role') || '').toLowerCase();
              if (role === 'dialog' || role === 'alertdialog') return true;
              if (el.getAttribute('aria-modal') === 'true') return true;
              var parts = tokens(String(el.getAttribute('class') || '').toLowerCase());
              for (var i = 0; i < parts.length; i++) {
                if (parts[i] === 'modal' || parts[i] === 'backdrop' || parts[i] === 'modal-backdrop') return true;
              }
              return false;
            }
            function containsPoint(el) {
              var box = el.getBoundingClientRect();
              return x >= box.left && x <= box.right && y >= box.top && y <= box.bottom;
            }
            function pierce(root, out, seen) {
              if (!root || !root.elementsFromPoint || seen.has(root)) return;
              seen.add(root);
              var list = root.elementsFromPoint(x, y);
              for (var i = 0; i < list.length; i++) {
                var el = list[i];
                if (!el || seen.has(el)) continue;
                seen.add(el);
                out.push(el);
                if (el.shadowRoot) pierce(el.shadowRoot, out, seen);
              }
            }
            var stack = [];
            try { pierce(document, stack, new Set()); } catch (ignored) { return false; }
            for (var i = 0; i < stack.length; i++) {
              var hit = stack[i];
              if (hit === target) break;
              if (target.contains && target.contains(hit)) continue;
              if (isMask(hit) && shown(hit)) return true;
            }
            var top = stack.length ? stack[0] : null;
            var guard = 0;
            while (top && top.nodeType === 1 && guard++ < 40) {
              if (top !== target && isMask(top) && shown(top) && isModal(top) && containsPoint(top)) return true;
              top = top.parentElement || (top.getRootNode && top.getRootNode().host) || null;
            }
            return false;
            """;

    /** Waits until the covering mask is gone, or until {@code timeoutMs} ends. A miss is not a wait. */
    public static Wait await(WebDriver driver, WebElement element, long timeoutMs) {
        if (!(driver instanceof JavascriptExecutor js) || element == null) return new Wait(0, false);
        long start = System.nanoTime();
        if (!covers(driver, js, element)) return new Wait(0, false);
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        while (true) {
            if (System.nanoTime() >= deadline) return new Wait(elapsed(start), false);
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Wait(elapsed(start), false);
            }
            if (!covers(driver, js, element)) return new Wait(Math.max(1, elapsed(start)), true);
        }
    }

    /** Fails when a covering mask is still up after the wait. A miss is not a failure. */
    public static void requireClear(WebDriver driver, WebElement element, long timeoutMs) {
        failIfStuck(await(driver, element, timeoutMs));
    }

    static void failIfStuck(Wait wait) {
        if (wait != null && wait.waitedMs() > 0 && !wait.maskCleared()) {
            throw new IllegalStateException(
                    "error: loading mask still covers the click. fix: wait until the mask clears, then click again.");
        }
    }

    private static boolean covers(WebDriver driver, JavascriptExecutor js, WebElement element) {
        Duration previous = null;
        try {
            previous = driver.manage().timeouts().getScriptTimeout();
        } catch (RuntimeException ignored) {
            previous = null;
        }
        try {
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // The script call still has to return on its own.
        }
        try {
            Object raw = js.executeScript(COVERS, element);
            return Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw));
        } catch (RuntimeException failure) {
            return false;
        } finally {
            if (previous != null) {
                try {
                    driver.manage().timeouts().scriptTimeout(previous);
                } catch (RuntimeException ignored) {
                    // The five-second bound stays.
                }
            }
        }
    }

    private static long elapsed(long start) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }
}
