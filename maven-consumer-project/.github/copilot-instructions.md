Before any Pickleball work, run `mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"` and, after a successful export, follow `.pickleball/AGENT-GUIDE.md`.

To find which of several scenarios fail, run one diagnostic `mvn test` with `pkb_runvars` (parallel is OK when the project supports it). That discovery run is not a skip of Workbench.

To isolate or debug a known failure, start headless Workbench MCP (`mcp .`) if `workbench_*` tools are not already in this session. Do not skip Workbench because MCP is disconnected, and do not start the GUI.
