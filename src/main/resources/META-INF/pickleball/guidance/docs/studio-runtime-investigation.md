# Studio Runtime Investigation and Control

Phase 3H completes the planned Phase 3 live investigation/control surface on top of the existing private Studio runtime bridge. It is additive to protocol version `1` and preserves the same architecture: Pickleball Core owns live execution, Studio owns the external control plane, MCP/Swing are adapters, and AI policy stays in the AI client.

The focused Maven-consumer acceptance tag is `@phase3h`.

## Capabilities

Phase 3H adds three runtime capabilities:

- Pickleball-native element inspection;
- direct execution/evidence for existing Pickleball service-call definitions;
- temporary semantic breakpoints.

All live-state commands execute on the selected scenario thread through the existing `ControlBridgeCoordinator` command queue. They therefore work during an explicit pause or breakpoint pause and preserve scenario thread-local state.

Exploratory failures use the existing `SUCCESS`, `FAILED`, and `UNAVAILABLE` result model. A `FAILED` investigation does not by itself change the enclosing scenario's hard/soft failure state.

## Pickleball-native element inspection

`ElementControl.inspect(...)` resolves through the current `ExecutionDictionary`, using the same category, text/value, operation, and consumer-registered custom categories used by normal Pickleball element syntax.

The bridge endpoint is:

```text
POST /v1/browser/elements
```

Studio MCP exposes:

```text
runtime_element_inspect
```

Inputs are:

- optional `scenarioId`;
- required Pickleball element `category`;
- optional text/value;
- optional Pickleball operation (`DEFAULT`, equals, contains, starts/ends with, matches, comparison operations, etc.);
- optional `maxElements` (default 20, maximum 100);
- optional command timeout.

This is deliberately **not** a raw CSS/XPath API. Studio does not get a second selector language. The resolved XPath may be returned as debugging evidence, but callers provide Pickleball vocabulary rather than implementation selectors.

The active scenario must already own a browser. Inspection calls `BrowserSteps.getCurrentDriverIfPresent()` and returns `UNAVAILABLE` rather than creating/registering a WebDriver.

Each returned element evidence snapshot includes:

- tag name;
- text and current value;
- displayed/enabled/selected state;
- x/y position and width/height;
- a bounded deterministic attribute map;
- bounded `outerHTML` with truncation metadata.

The result also reports the total match count and whether per-element evidence was truncated by the requested match limit. Text, attribute values, and HTML are bounded before crossing the JVM boundary.

## Direct Pickleball service calls

`ServiceCallControl.execute(selector)` executes the same reusable service-call component selected by normal `CALL:` syntax. It invokes the existing `ServiceCallSteps.inlineCall(...)` path on the selected scenario thread rather than building a second HTTP client or service DSL. A scoped control execution preserves the outer scenario cursor/failure/log state while the nested service-call scenario runs with its normal Pickleball mapping and execution semantics. Normal Pickleball behavior therefore continues to own:

- call/resource discovery;
- component/selector matching;
- mapping and template resolution;
- REQUEST and CONFIGURATION processing;
- REST/SOAP execution;
- RESPONSE mapping;
- retry-friendly detached failure handling.

The bridge endpoint is:

```text
POST /v1/services/call
```

Studio MCP exposes:

```text
runtime_service_call
```

The selector is the existing `CALL:` selector without the literal `CALL:` prefix, for example `%health-full-url`.

The result carries bounded copies of the completed call's `REQUEST`, `CONFIGURATION`, and `RESPONSE` values plus the response status code when available. Each JSON evidence section is limited to 256 KiB of compact UTF-8 representation and reports its original byte count plus `truncated` status.

No automatic rollback is implied. Service calls may have real external effects just as the same Pickleball call would during ordinary execution.

## Semantic breakpoints

Phase 1 already supplies stable semantic `ControlHook` boundaries. Phase 3H turns those hooks into temporary externally manageable breakpoints without adding a bytecode debugger.

Bridge endpoints are:

```text
GET  /v1/breakpoints
POST /v1/breakpoints/add
POST /v1/breakpoints/remove
POST /v1/breakpoints/clear
```

Studio MCP exposes:

```text
runtime_breakpoint_add
runtime_breakpoints
runtime_breakpoint_remove
runtime_breakpoints_clear
```

A breakpoint can filter by any combination of:

- scenario id;
- exact `ControlHook` name;
- literal substring of hook signature;
- literal substring of current step text;
- literal substring of current phrase text.

At least one filter is required. Multiple filters combine with AND. Optional one-shot breakpoints delete themselves immediately after the first matching hit.

A match requests a pause on the same scenario lane used by ordinary runtime pause. The pause always has a finite lease (default 120 seconds, maximum 3600 seconds), and ordinary `runtime_resume` resumes it. While the scenario is paused, the same lane continues servicing queued element, service, mapping, browser, detached-step, and other runtime commands.

A live runtime retains at most 100 breakpoints. Breakpoint metadata is intentionally small: id, filters, lease, one-shot flag, hit count, last-hit timestamp, and last scenario id. Breakpoints exist only inside the live consumer bridge and disappear when that runtime exits.

Useful hooks include `BEFORE_STEP`, `BEFORE_PHRASE`, mapping hooks, `BEFORE_DOM_ACCESS`, `BEFORE_BROWSER_INTERACTION`, `BEFORE_DRIVER_COMMAND`, and `BEFORE_SERVICE_CALL`. Breakpoints remain semantic Pickleball interception points rather than arbitrary Java source breakpoints.

## Desktop Runtime Control

The Runtime Control dialog adds an **Investigation** tab with three focused panels:

- **Elements** — category/text/operation/max evidence and Pickleball-native inspection;
- **Service Calls** — execute one existing service-call selector and inspect evidence;
- **Breakpoints** — add/list/remove/clear semantic breakpoints.

These controls delegate through `StudioDesktopSession` to `RuntimeBridgeService`, exactly like MCP. Swing does not issue bridge HTTP directly or retain a separate breakpoint store.

## MCP contract

Phase 3G ended with 36 tools. Phase 3H adds six tools and brings the Studio MCP surface to **42 tools**. The new tools are deterministic capability/evidence operations; they do not decide what an AI should inspect, retry, edit, or consider fixed.

A typical investigation can now be:

```text
runtime_start
→ runtime_scenarios / runtime_events
→ runtime_pause or runtime_breakpoint_add
→ runtime_element_inspect / runtime_browser_page / runtime_browser_screenshot
→ runtime_service_call
→ runtime_mapping_get / runtime_mapping_put / runtime_mapping_snapshot
→ runtime_execute_step
→ retry as needed
→ runtime_resume
```

That is still one continuous Pickleball execution context rather than separate "scenario" and "manual automation" modes.

## Validation

Focused Maven consumer validation is selected with:

```text
@phase3h
```

The `control-bridge.feature` acceptance flow covers the new capabilities together with the existing bridge surface because Phase 3H changes the central scenario-thread control coordinator. The focused scenario validates element resolution, a real existing service-call definition, a one-shot semantic breakpoint that actually pauses execution, and the established mapping/browser/event/detached-step behavior most likely to be affected indirectly.

After focused validation, run the normal framework, Studio, consumer, agent-contract, generated-index, and consumer-guidance validation required by root `AGENTS.md`.

## Phase boundary

Phase 3H completes the planned Phase 3 runtime investigation/control capability set. It does not add persistent runtime sessions, remote/non-loopback control, arbitrary JVM object serialization, a browser-devtools selector API, or autonomous AI behavior.

The next major phase is Phase 4: make the completed Studio capability set efficient and visible for human/AI collaboration while preserving the same shared services and runtime boundary.
