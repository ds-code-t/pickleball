# 3.0.0-agent-fast: planning inputs (2026-11-18)

Goal (Dan, 2026-11-18): fix every bug in the consumer field report and make Workbench and all Pickleball dynamic functionality fast, responsive and cheap for AI agents running in consumer projects, including agent-human collaboration through injected page UI.

## Inputs
- `consumer-field-report-2026-11-18.md`: external consumer report on 3.0.0 at f5ccc9f4 (B1-B16, P1-P14).
- `evaluation.md`: code-checked verdict for every bug/proposal, latency sources, current iframe/shadow/wait handling, browser vs desktop surface split, brainstorm and backlog.
- `browser-control-research.md`: Playwright MCP/CLI/Java, Chrome DevTools MCP, Workspaces, BiDi, raw CDP, Selenium compared; observers/listeners, iframes, shadow DOM, hard DOM areas; engine-tier vs agent-tier recommendations.

## Dan's design principles (2026-11-18)
1. Speed is a top theme: startup, every step, and injected-component collaboration with humans are currently slow. Measure it, set targets, prove gains.
2. Holistic, streamlined, well encapsulated. Avoid over-complexity from extra layers.
3. Two tiers. ENGINE tier: universal, stable, reliable functionality in the core engine that any script or scenario uses for DOM interaction, also as an automatic fallback when other methods fail. AGENT tier: finer-grained or experimental controls exposed to AI agents more directly (functions behind CLI/API/MCP), especially for collaborating with/directing humans and probing/testing the DOM while debugging.
4. One higher-level API/MCP surface for both browser/DOM and OS/platform desktop targets; different backends per target behind the same call.
5. Integrate the browser and desktop control engine with snapshots and the prebuilt calls/components wherever practical and useful.
6. More universal MutationObserver/other listener injection for responsiveness (push, not poll); robust iframes, shadow roots, and other difficult DOM areas.
7. Prebuilt components and prebuilt parameterizable functions that agents can call in one quick, cheap call.

## Orchestrator decisions on the evaluation's open questions (Dan delegated these)
- No-op consumer glue: flag `noop:true` in step replies and warn in the run report by default; an opt-in setting may turn it into a failure. Never silently SUCCESS without the flag.
- Extend `start` to really launch/attach a live browser (fixing B3) rather than adding a sibling verb; `live-start` may exist only as a documented alias if it costs nothing.
- Fix CLI-to-live-worker forwarding (B2/P3) first; a controller `call` verb (P2) follows on the same path, no second transport.
- Pause lease: hold the live scenario while any client (MCP or CLI controller) is attached via heartbeat; document the lease and its timeout.
- Agent verbs use the snapshot-backed engine when available, with the documented fallback chain; scenario default behavior stays unchanged unless a change is proven safe by tests.
- `navigate to:` accepts `file:`, `data:` and `about:blank` in addition to http(s).
- Phase 1 performance targets in `evaluation.md` are accepted as the starting targets.
- Desktop: the same target-agnostic API ships in this roadmap, backed by the existing desktop engine verbs; no separate desktop surface.
- Do not add Playwright, Chrome DevTools MCP, or any Node dependency; borrow designs only (ref-based compact snapshot, actionability checks, include-snapshot-on-action).
