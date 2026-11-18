# P2-24. Tracking and Store tabs show no data

- The Store holds 19 timeline rows (`store timeline --latest --json` on the full test classpath).
- The Tracking tab showed nothing. `StoreCliForwarder.java:71-80` runs `<java.home>/bin/java -jar <pickleball-3.0.0.jar> store <args> --project=<project>`. The jar is thin, so that command prints `error: Store is enabled but org.xerial:sqlite-jdbc is not on the classpath.`
- `TrackingPanel.java:223,250` shows that only as `status.setText("error: ...")`; the table stays empty. The Store tab uses the same forwarder.
- Same cause as the broken `java -jar ... domengine` example (missing Jackson).
- **Missing libraries (found by trial):** `store` needs `org.xerial:sqlite-jdbc:3.47.2.0`; `domengine` needs `jackson-databind:2.20.0`, `jackson-core:2.20.0`, `jackson-annotations:2.20`. With only those four beside the jar, `store timeline`, `store status`, `domengine version/capabilities/session list` all work. The manifest has `Main-Class: tools.dscode.launcher.PickleballWorkbenchLauncher` and no `Class-Path`.
- **Fix:** run the forwarder with the consumer test classpath the Workbench already resolves (`java -cp <cp> tools.dscode.launcher.PickleballWorkbenchLauncher store ...`), or make the jar's `store` and `domengine` entry points self-contained. Show forwarder errors in the table area.
