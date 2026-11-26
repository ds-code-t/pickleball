package tools.dscode.control.protocol;

/**
 * How Workbench MCP reaches a verb. {@code direct} posts the verb and flag map
 * to the live worker. {@code cli} is the debug shell-out. Anything else, including
 * a blank value, is {@code direct}.
 */
public final class McpTransport {
    public static final String PROPERTY = "pkb_mcp_transport";
    public static final String ENV = "PKB_MCP_TRANSPORT";
    public static final String DIRECT = "direct";
    public static final String CLI = "cli";

    private McpTransport() {
    }

    /** True unless the property or env is exactly {@code cli}, ignoring case. */
    public static boolean direct() {
        return !cli();
    }

    public static boolean cli() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) raw = System.getenv(ENV);
        return raw != null && CLI.equalsIgnoreCase(raw.trim());
    }

    public static String name() {
        return cli() ? CLI : DIRECT;
    }
}
