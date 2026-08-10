# Pickleball Maven Consumer Example

This project intentionally keeps its local documentation minimal. Pickleball's version-matched human and AI guidance is packaged in the Maven dependency.

Materialize or refresh it with:

```powershell
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=tools.dscode.common.reporting.diagnostic.DiagnosticCli" "-Dexec.classpathScope=test" "-Dexec.args=export-guidance .pickleball"
```

A successful export writes `.pickleball/GUIDANCE-MANIFEST.json` with the exporting Pickleball version and managed-file list, refreshes current guidance, and removes obsolete files from prior managed exports. The exporter also best-effort ensures `.pickleball` is ignored by Git; this example already checks in `/.pickleball/` in `.gitignore`.

Then read `.pickleball/AGENT-GUIDE.md` for AI instructions or `.pickleball/docs/consumer-project.md` for the human-readable consumer guide. If export fails, do not assume an existing `.pickleball` directory is current.

Run the test suite with `mvn test` or the included Maven wrapper.
