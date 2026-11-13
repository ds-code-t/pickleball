# Grok Heavy — 3.0.0 locked-decisions analysis sweep (PHASE 1 ONLY)

Repo: https://github.com/ds-code-t/pickleball, branch `3.0.0` (tip 960bad6b or later).
Owner: Dan Shell. Coordinator: the pickleball bot.

## Hard rules
- Do NOT write a roadmap, a design doc or a Build prompt. This is analysis only.
- Never commit or push to master, 2.2.0 or 2.1.19. No tags, releases or force-push.
- Don't change product code, tests or AGENTS.md. The ONLY file you may push is your findings file:
  `docs/maintainer/3.0.0-locked-decisions-sweep.md` on branch 3.0.0. If you can't push, paste the full findings in chat.
- Never push placeholder or empty files. If you push, the file must contain the full table.
- Base every verdict on code you actually read: file path plus line or symbol. No guessing.

## Read first, in order (the project knowledge rule: branch docs are the source of truth)
1. `AGENTS.md`
2. `README.md`, the version files (`gradle.properties` / build version, `docs/release-notes.md` or the changelog)
3. `docs/` (configuration.md, getting-started.md, dynamic-steps.md, direct-control-handoff.md, dalton docs, maintainer docs)
4. All `docs/roadmaps/3.0.0-*.md`
5. Prior audit for comparison (verify it, don't trust it): `docs/maintainer/3.0.0-locked-decisions-prior-audit.md`

## Task
For EACH locked decision below (agreed Nov 10–13, 2026), give:
verdict (implemented / partial / missing / reversed) | one-line evidence (file:line or symbol) | do the docs contradict it (yes/no + where) | gap notes.

Locked decisions:
1. Single browser facade over Selenium, Playwright and raw CDP, with wide version ranges.
2. Libraries are the default for scripted tests; a direct-protocol path (CDP / BiDi / others) is for agents and narrow gaps; every fallback logs a warning.
3. Java owns agent protocol control (a separate agent-only boundary).
4. WebDriver BiDi as the Firefox protocol path.
5. MCP server as the single agent entry point, with predefined parameterized commands.
6. Control engine (renamed from "dumb engine") unifying browsers and Windows/macOS/Linux desktops.
7. Layered architecture: syntax layer, dictionaries layer, protocol adapters layer.
8. Unified Boolean query: capital AND/OR, parentheses.
9. XPath as one clause type among image / color / CSS / accessibility / text / relatives; old XPath syntax deprecated but still working. Dictionary emits the combined form, with XPath as the fallback.
10. Scored label affinity, including negative relations.
11. Bounding boxes with size caps and the one-match rule.
12. Section detection.
13. Universal composite snapshot (screenshot + page source/structure + XPaths/paths + viewport mapping) on ALL platforms: Chrome, Chromium, Firefox, Edge, Windows UIA, macOS AX, Linux AT-SPI.
14. Pre-mapping as a CORE principle: the snapshot pre-maps labels to interactables, sections and nesting, bounding boxes, viewport coordinates, XPath indexes, and image and color features, so runtime queries are light.
15. Dalton as a portable artifact: hydrate/extract, GitHub info, logs, binary references, sync guards; Dalton central rather than opt-in.
16. Rust-to-Java replacement and integration (no Rust left, including overrides, vendored clients and native-binary lookups).
17. Cucumber optional, with unified feature paths.
18. Workbench (edits everything, as decided).
19. Dynamic control / parity harness / default switch.

Also list every stale or contradicting doc line you find (path:line + what is wrong).

## Deliverable
A compact table covering all 19 rows, then a gaps list, then a stale-docs list. Push it as `docs/maintainer/3.0.0-locked-decisions-sweep.md` on 3.0.0 (one commit) AND paste the same content in chat. End with the commit SHA and the file size in bytes.
