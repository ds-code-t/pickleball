# Workbench Player Context

Root `AGENTS.md` and `pickleball-workbench/AGENTS.md` remain authoritative for isolation, synchronization, MCP, and worker-lifecycle rules.

This file records the live-player behavior added on top of those unchanged boundaries.

## Player contract

`LiveScenarioPlayer` is presentation/buffer state only. Pickleball execution remains in the consumer worker.

The playhead is the user-visible needle. Clicking a scenario line instantly seeks it, like clicking a waveform. Global Play ignores the playhead and always starts from the first executable buffer step in a fresh interactive worker context.

The Live Scenario Editor is an in-place Gherkin document. Users can type at any line, including previously executed text. Stable line identities are preserved for same-index edits. The buffer is session-owned and is not written back to consumer `.feature` files.

The Step Editor exposes two distinct execution actions:

- **Step**: execute only the editor text in the current paused live context and leave automatic playback paused.
- **From Here**: restart into a fresh interactive scenario context, use the selected/playhead executable step as the first step of the run, and continue through the remaining buffer steps.

Fresh Play/From Here runs restart the worker so prior browser, Mapping, service, or other side effects do not masquerade as the beginning of a scenario. Protocol-mismatched synchronized state still triggers the existing one-time resynchronization retry.

Pause stops advancement after any current in-flight command. Stop stops automatic player advancement but does not imply runtime rewind and does not terminate the worker.

At end-of-buffer, automatic playback remains `WAITING_FOR_STEP`. Enter appends after the last executable step while waiting and resumes execution. Adding an executable line at the end of the document while waiting does the same. Ctrl+Enter and ordinary typing update lines in place regardless of whether they were executed in an earlier run.

The default loaded scenario is a Workbench-owned browser demo against `URL.home` and the consumer local test site. It is not a blank buffer and does not hard-code machine-specific paths.

The Workbench sends displayed Gherkin unchanged. Full `Given`/`When`/`Then`/`And`/`But`/`*` interpretation is implemented in worker-side `DynamicControl`; never move keyword stripping or Cucumber parsing into Workbench.

## Mapping editor contract

The primary Mapping tab has no get/put/resolve form. It is:

- one current-ParsingMap NodeMap dropdown;
- one JSON object editor for the selected NodeMap root.

Current NodeMaps are discovered worker-side through `MappingControl` using the reserved neutral references in `ControlProtocol`. Workbench sees only `ControlBridgeMappingSnapshot` data and must never import `ParsingMap`, `NodeMap`, or other Pickleball runtime classes.

Valid JSON edits are applied by constructing a replacement `ControlBridgeMappingSnapshot` with the original identity/type/class/data-source metadata and calling the existing `mappingRestore` service.

Do not weaken the existing rule that only exact ordinary `NodeMap` instances are restorable.

## Distribution invariant

This feature must not change the existing distribution graph:

```text
pickleball core/worker --------> pickleball-control-protocol
pickleball-workbench ----------> pickleball-control-protocol
published pickleball JAR ------> opaque completed Workbench JAR bytes
```

Pickleball may contain Workbench; Workbench must not contain Pickleball.
