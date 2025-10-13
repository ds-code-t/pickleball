package io.cucumber.core.internal.util;

public final class ReflectiveAgentInvoker {

    private static final String INIT_ONCE_CLASS = "tools.dscode.agent.InitOnce";
    private static final String INIT_METHOD = "InitAgent";

    private static volatile boolean logged = false;

    private ReflectiveAgentInvoker() { }

    /**
     * Attempts to call tools.dscode.agent.InitOnce.InitAgent() via reflection.
     * If the class or method are missing, it fails silently (logging only once).
     */
    public static void tryInitAgent() {
        try {
            Class<?> clazz = Class.forName(INIT_ONCE_CLASS, true, Thread.currentThread().getContextClassLoader());
            clazz.getMethod(INIT_METHOD).invoke(null);
        } catch (ClassNotFoundException e) {
            logOnce("InitOnce not found (agent module probably not present).");
        } catch (NoSuchMethodException e) {
            logOnce("InitAgent() method missing on InitOnce.");
        } catch (Throwable t) {
            logOnce("Error invoking InitAgent(): " + t);
        }
    }

    private static void logOnce(String msg) {
        if (!logged) {
            logged = true;
            System.err.println("[ReflectiveAgentInvoker] " + msg);
        }
    }
}
