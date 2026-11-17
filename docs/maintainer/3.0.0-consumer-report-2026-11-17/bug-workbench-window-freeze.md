# Bug: Workbench window freezes for about 4 minutes on open (large project)

**Product:** Pickleball Workbench 3.0.0 (`tools.dscode:pickleball-workbench:3.0.0`), Pickleball 3.0.0.
**Project:** `charlesschwab/myq-release-testing`, about 1,700 features and 10,900 scenarios.
**Platform:** Windows 11, Zulu JDK 21.0.13, 16 cores, 31 GB RAM.
**Severity:** High. The window doesn't respond to clicks or redraw until the load ends.

## Steps to reproduce
1. In a project with about 10,000 scenarios, run `PickleballWorkbenchLauncher open-window --run-id=<any run>`.
2. Try to click any tab.

**Expected:** The window responds within a few seconds and fills the scenario picker in the background.
**Actual:** The window shows its title but doesn't respond for about 4 minutes. Reproduced twice (pid 27232 and pid 8900).

## Measurements (pid 8900)
| Time after open | UI thread (`AWT-EventQueue-0`) CPU | Memory |
|---|---|---|
| 16 s | 6 s | 2.57 GB |
| 95 s | 73 s | 2.71 GB |
| about 3.5 min | 171 s, then idle | 2.86 GB |

The background load thread (`pickleball-workbench-load`) used only 9.6 s of CPU. So the scan of features and glue isn't the problem, and no SQLite work runs on the UI thread.

## Root cause
Found in `pickleball-workbench-3.0.0-sources.jar`. When the background load finishes, the success callback runs on the Swing UI thread (`WorkbenchFrame.java:2426` calls `applyCatalog`, which calls `picker.setCatalog(...)` at `WorkbenchFrame.java:1026`).

1. **One event per row.** `FeaturePickerPanel.refreshScenarios()` (line 311) calls `scenarioModel.addElement(scenario)` (line 319) once per scenario on the live list model, about 10,900 times. Each call fires `intervalAdded`. `setCatalog` does the same for features (line 150), and `syncFeatureSelection` calls `addSelectionInterval(i, i)` once per selected feature (line 161).
2. **No fixed row size.** Neither `scenarioList` nor `featureList` sets `setFixedCellHeight` or `setPrototypeCellValue`, so Swing measures every row through the renderer on every layout.
3. **A new component on every render.** Both renderers (lines 67 and 77) build a new `JLabel` on every call, and the scenario renderer also joins the tag text.
4. **A full-window relayout.** `publishWorkStatus()` calls `revalidate()` on the frame (`WorkbenchFrame.java:2459`). The thread dump caught the UI thread at this line, with the lists already full.

Items 1 to 3 together make the work grow with the square of the row count, all on the UI thread.

## Suggested fix
```java
// FeaturePickerPanel.refreshScenarios(): build once, assign once
DefaultListModel<ConsumerFeatureCatalog.ScenarioEntry> next = new DefaultListModel<>();
next.addAll(catalog.visibleScenarios());
scenarioList.setModel(next);
scenarioModel = next;

// constructor: fixed row height, so Swing does not measure each row
scenarioList.setFixedCellHeight(20);   // or setPrototypeCellValue(sample)
featureList.setFixedCellHeight(20);

// syncFeatureSelection(): one selection call
featureList.setSelectedIndices(selectedIndexes);
```
- Reuse one `JLabel` in each renderer (extend `DefaultListCellRenderer`), or cache the tag text on `ScenarioEntry`.
- Optional: show the first 500 matches and page the rest, since this list is only for picking one scenario.

## Related defect
Closing the window doesn't end its JVM. After the user closed the window opened at 12:17, pid 48636 kept running with no window. The next `open-window` started a second Workbench JVM (pid 27232) instead of reusing or replacing it. Fix: exit the JVM when the last window closes, or have `open-window` find and reuse the running process.

## Evidence files
- `workbench-ui-threads.txt`: a dump of pid 27232 during the freeze, with `AWT-EventQueue-0` RUNNABLE at `WorkbenchFrame.publishWorkStatus(WorkbenchFrame.java:2459)` called from `LongWork.lambda$start$0(LongWork.java:57)`.
- `workbench-ui-threads-2.txt`: a dump of pid 8900 after the load, with both threads idle.
