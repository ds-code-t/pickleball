package tools.dscode.parallelutilities;

import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Stagger {

    private Stagger() {}

    private static final Object LOCK = new Object();
    private static final List<Entry> ACTIVE = new ArrayList<>();

    private enum Global {
        INSTANCE
    }

    public static <R> R call(Callable<R> work) {
        return call(Global.INSTANCE, alwaysAgainst(), work);
    }

    public static void run(CheckedRunnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    public static <T, R> R call(
            T value,
            BiPredicate<T, T> against,
            Callable<R> work
    ) {
        Objects.requireNonNull(against, "against");
        Objects.requireNonNull(work, "work");

        Entry self = new Entry(groupFor(value), value);

        try {
            synchronized (LOCK) {
                while (hasConflict(self, value, against)) {
                    waitOnLock();
                }

                ACTIVE.add(self);
            }

            return work.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StaggerException("Staggered call was interrupted", e);
        } catch (Exception e) {
            throw new StaggerException("Staggered call failed", e);
        } finally {
            synchronized (LOCK) {
                ACTIVE.remove(self);
                LOCK.notifyAll();
            }
        }
    }

    public static <T> void run(
            T value,
            BiPredicate<T, T> against,
            CheckedRunnable work
    ) {
        call(value, against, () -> {
            work.run();
            return null;
        });
    }

    public static <R> R callMatching(
            String text,
            Pattern pattern,
            Callable<R> work
    ) {
        Set<String> keys = regexKeys(text, pattern);

        if (keys.isEmpty()) {
            return callDirect(work);
        }

        return call(
                keys,
                Stagger::overlaps,
                work
        );
    }

    public static void runMatching(
            String text,
            Pattern pattern,
            CheckedRunnable work
    ) {
        callMatching(text, pattern, () -> {
            work.run();
            return null;
        });
    }

    public static <R> R callUrlHost(
            String url,
            Callable<R> work
    ) {
        String key = registeredHostKey(url);

        if (key == null || key.isBlank()) {
            return callDirect(work);
        }

        return call(
                key,
                String::equals,
                work
        );
    }

    public static void runUrlHost(
            String url,
            CheckedRunnable work
    ) {
        callUrlHost(url, () -> {
            work.run();
            return null;
        });
    }

    public static <T, K> BiPredicate<T, T> sameBy(Function<T, K> keyExtractor) {
        Objects.requireNonNull(keyExtractor, "keyExtractor");

        return (a, b) -> Objects.equals(
                keyExtractor.apply(a),
                keyExtractor.apply(b)
        );
    }

    public static <T> BiPredicate<T, T> alwaysAgainst() {
        return (a, b) -> true;
    }

    @SuppressWarnings("unchecked")
    private static <T> boolean hasConflict(
            Entry self,
            T value,
            BiPredicate<T, T> against
    ) {
        for (Entry entry : ACTIVE) {
            if (!Objects.equals(entry.group, self.group)) {
                continue;
            }

            T other = (T) entry.value;

            if (against.test(value, other)) {
                return true;
            }
        }

        return false;
    }

    private static Set<String> regexKeys(String text, Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern");

        if (pattern.matcher("").groupCount() != 1) {
            throw new IllegalArgumentException("pattern must contain exactly one capture group");
        }

        Set<String> keys = new LinkedHashSet<>();

        if (text == null || text.isBlank()) {
            return keys;
        }

        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String key = matcher.group(1);

            if (key != null && !key.isBlank()) {
                keys.add(key.trim());
            }
        }

        return keys;
    }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        for (String value : a) {
            if (b.contains(value)) {
                return true;
            }
        }

        return false;
    }

    private static String registeredHostKey(String url) {
        String host = extractHost(url);

        if (host == null || host.isBlank()) {
            return null;
        }

        host = host.trim().toLowerCase();

        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }

        if (isIpAddress(host)) {
            return host;
        }

        host = IDN.toASCII(host);

        String[] parts = host.split("\\.");

        if (parts.length < 2) {
            return host;
        }

        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(url.trim());

            String host = uri.getHost();

            if (host != null) {
                return host;
            }

            URI fallback = URI.create("http://" + url.trim());
            return fallback.getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isIpAddress(String host) {
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")
                || host.contains(":");
    }

    private static <R> R callDirect(Callable<R> work) {
        Objects.requireNonNull(work, "work");

        try {
            return work.call();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StaggerException("Staggered call was interrupted", e);
        } catch (Exception e) {
            throw new StaggerException("Staggered call failed", e);
        }
    }

    private static void waitOnLock() throws InterruptedException {
        LOCK.wait();
    }

    private static Object groupFor(Object value) {
        return value == null ? NullValue.class : value.getClass();
    }

    private static final class NullValue {}

    private static final class Entry {
        private final Object group;
        private final Object value;

        private Entry(Object group, Object value) {
            this.group = group;
            this.value = value;
        }
    }

    public static class StaggerException extends RuntimeException {
        public StaggerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
