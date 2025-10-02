package tools.ds.modkit.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public final class CucumberTagUtils {
    private CucumberTagUtils() {}

    /** Return tag expressions (as strings) from either RuntimeOptions or CucumberConfiguration. */
    public static List<String> extractTags(Object runnerOptions) {
        if (runnerOptions == null) return List.of();

        // 1) RuntimeOptions#getTagExpressions(): List<io.cucumber.tagexpressions.Expression>
        Object tagExprList = tryInvoke(runnerOptions, "getTagExpressions");
        if (tagExprList instanceof Collection<?> col) {
            return toStrings(col);
        }

        // 2) CucumberConfiguration#tagFilter(): Optional<Expression>
        Object opt = tryInvoke(runnerOptions, "tagFilter");
        Object expr = unwrapOptional(opt);
        if (expr != null) {
            return List.of(String.valueOf(expr));
        }

        // 3) CucumberConfiguration has a ConfigurationParameters field; read "cucumber.filter.tags"
        Object cfgParams = tryField(runnerOptions, "configurationParameters");
        if (cfgParams != null) {
            // Optional<String> get(String key)
            Object optStr = tryInvoke(cfgParams, "get", "cucumber.filter.tags");
            Object val = unwrapOptional(optStr);
            if (val instanceof String s && !s.isBlank()) {
                // Could be comma-delimited; normalize into individual entries.
                return splitCommaTrim(s);
            }
        }

        // 4) Last ditch: look for a generic "getFilters"/"getTags"/"tags" pattern
        for (String m : List.of("getFilters", "getTags", "tags")) {
            Object v = tryInvoke(runnerOptions, m);
            if (v instanceof Collection<?> c) return toStrings(c);
            if (v instanceof String s && !s.isBlank()) return splitCommaTrim(s);
        }

        return List.of();
    }

    /* ---------------- small reflection helpers ---------------- */

    private static Object tryInvoke(Object target, String name, Object... args) {
        try {
            Class<?> c = target.getClass();
            Method best = null;
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (!m.getName().equals(name)) continue;
                    if (m.getParameterCount() == (args == null ? 0 : args.length)) { best = m; break; }
                }
                if (best != null) break;
            }
            if (best == null) return null;
            if (!best.canAccess(target)) best.setAccessible(true);
            return best.invoke(target, args);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Object tryField(Object target, String name) {
        try {
            for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
                try {
                    Field f = k.getDeclaredField(name);
                    if (!f.canAccess(target)) f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {}
            }
            return null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Object unwrapOptional(Object maybeOpt) {
        if (maybeOpt == null) return null;
        try {
            Class<?> c = maybeOpt.getClass();
            if (!"java.util.Optional".equals(c.getName())) return null;
            Method isPresent = c.getMethod("isPresent");
            Method get = c.getMethod("get");
            return (Boolean)isPresent.invoke(maybeOpt) ? get.invoke(maybeOpt) : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static List<String> toStrings(Collection<?> c) {
        List<String> out = new ArrayList<>(c.size());
        for (Object o : c) if (o != null) out.add(String.valueOf(o));
        return out;
    }

    private static List<String> splitCommaTrim(String s) {
        String[] parts = s.split(",");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
