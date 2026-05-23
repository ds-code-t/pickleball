package tools.dscode.testengine;

public final class EngineFilterBootstrap {

    public static final String INCLUDE_ENGINES_KEY = "junit.platform.discovery.includeEngines";
    public static final String OPT_OUT_KEY = "pickleball.disableEngineFilter";
    public static final String PICKLEBALL_ENGINE_ID = DynamicSuiteEngine.ENGINE_ID;

    private static volatile boolean applied = false;

    private EngineFilterBootstrap() {
    }

    public static synchronized void ensureEngineFilterApplied(String origin) {
        if (applied) {
            return;
        }

        if (Boolean.parseBoolean(System.getProperty(OPT_OUT_KEY, "false"))) {
            System.err.println("[Pickleball] Engine filter disabled via -D" + OPT_OUT_KEY + "=true (origin: " + origin + ")");
            applied = true;
            return;
        }

        String existing = System.getProperty(INCLUDE_ENGINES_KEY);
        if (existing == null || existing.isBlank()) {
            System.setProperty(INCLUDE_ENGINES_KEY, PICKLEBALL_ENGINE_ID);
//            System.err.println("[Pickleball] Set " + INCLUDE_ENGINES_KEY + "="
//                    + PICKLEBALL_ENGINE_ID + " (origin: " + origin + ")");
        }
//        else {
//            System.err.println("[Pickleball] Honoring existing " + INCLUDE_ENGINES_KEY
//                    + " = " + existing + " (origin: " + origin + ")");
//        }

        applied = true;
    }
}