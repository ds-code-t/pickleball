package tools.dscode.common.composite;

import org.openqa.selenium.By;

import java.util.Locale;

/**
 * The engine query object: one pipeline or expression string, plus the
 * exposure, limit, and explain flags the composite CLI already accepts.
 * Workbench, MCP, and the control bridge only forward this object.
 */
public record EngineQuery(
        String text,
        String exposure,
        Integer limit,
        boolean explain,
        boolean promoteVisual,
        boolean geometry,
        boolean interact
) {
    public EngineQuery {
        text = text == null ? "" : text.trim();
        if (exposure != null) {
            exposure = exposure.trim().toLowerCase(Locale.ROOT);
            if (exposure.isEmpty()) exposure = null;
            else if (!"human".equals(exposure) && !"lower".equals(exposure)) {
                throw new IllegalArgumentException("exposure is human or lower");
            }
        }
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit is below 1");
        }
    }

    public static EngineQuery of(String text) {
        return new EngineQuery(text, null, null, false, false, false, false);
    }

    public EngineQuery exposure(String value) {
        return new EngineQuery(text, value, limit, explain, promoteVisual, geometry, interact);
    }

    public EngineQuery limit(Integer value) {
        return new EngineQuery(text, exposure, value, explain, promoteVisual, geometry, interact);
    }

    public EngineQuery explain(boolean value) {
        return new EngineQuery(text, exposure, limit, value, promoteVisual, geometry, interact);
    }

    public EngineQuery promoteVisual(boolean value) {
        return new EngineQuery(text, exposure, limit, explain, value, geometry, interact);
    }

    public EngineQuery geometry(boolean value) {
        return new EngineQuery(text, exposure, limit, explain, promoteVisual, value, interact);
    }

    public EngineQuery interact(boolean value) {
        return new EngineQuery(text, exposure, limit, explain, promoteVisual, geometry, value);
    }

    /** Controller fields. A blank query is category+text, not an engine selector. */
    public static EngineQuery controller(
            String text,
            String exposure,
            Integer limit,
            Boolean explain,
            Boolean promoteVisual
    ) {
        if (text == null || text.isBlank()) return null;
        EngineQuery built = of(text);
        if (exposure != null && !exposure.isBlank()) built = built.exposure(exposure);
        if (limit != null) built = built.limit(limit);
        if (Boolean.TRUE.equals(explain)) built = built.explain(true);
        if (Boolean.TRUE.equals(promoteVisual)) built = built.promoteVisual(true);
        return built;
    }

    /** Selenium can run a single css= or xpath= stage. Anything else needs the engine. */
    public By seleniumFallback() {
        if (text.contains(">>") || text.contains(" AND ") || text.contains(" OR ") || text.startsWith("NOT ")) {
            return null;
        }
        if (text.startsWith("css=")) return By.cssSelector(text.substring(4));
        if (text.startsWith("xpath=")) return By.xpath(text.substring(6));
        if (text.startsWith("//") || text.startsWith("(//") || text.startsWith("(/")) return By.xpath(text);
        if (!text.isEmpty() && !text.contains("=")) return By.cssSelector(text);
        return null;
    }
}
