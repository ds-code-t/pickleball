package tools.dscode.common.composite;

/**
 * Dictionary layer. One deprecation warning per run and XPath-only definition.
 * The definition still resolves; the XPath remains the fallback.
 */
public final class XPathDefinitions {
    private XPathDefinitions() {
    }

    public static void deprecatedOnce(String definition, String xpath) {
        String name = definition == null || definition.isBlank() ? "element" : definition.trim();
        CompositeLog.fallbackOnce(
                "xpath-definition:" + name,
                "element definition " + name + " is XPath-only (deprecated); it still runs as the XPath fallback",
                "add a combined clause, for example category(\"" + name + "\").combined(\"role=button\")",
                "java"
        );
    }
}
