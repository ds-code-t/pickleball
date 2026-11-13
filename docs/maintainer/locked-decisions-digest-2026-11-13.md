# Locked decisions digest — 2026-11-13

Decisions Dan locked between Nov 10 and Nov 13, 2026 for pickleball 3.0.0, captured from the pickleball bot conversation.
Each one gets three investigation questions. Answers are filled in by the analysis sweep (`docs/maintainer/3.0.0-locked-decisions-sweep.md`); `docs/maintainer/3.0.0-locked-decisions-prior-audit.md` holds an earlier partial audit.
Branch docs (AGENTS.md, docs/, roadmaps) remain the source of truth for current architecture.

- **1. Browser facade**: A single browser facade over Selenium, Playwright and raw CDP, with wide supported version ranges.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **2. Libraries default, direct protocols for agents**: Scripted tests use the libraries (Selenium/Playwright) by default. Direct protocols (CDP, BiDi, others) are the agent and narrow-gap path, and every fallback logs a warning.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **3. Java-owned agent control**: Java owns direct protocol control for agents, behind a separate agent-only boundary.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **4. BiDi for Firefox**: WebDriver BiDi is the Firefox protocol path (Firefox has removed CDP).
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **5. MCP single entry point**: The MCP server is the single agent entry point and exposes predefined, parameterized commands.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **6. Control engine**: The control engine (renamed from "dumb engine") unifies browsers and the Windows, macOS and Linux desktops.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **7. Layered architecture**: Separate layers: syntax, dictionaries and protocol adapters.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **8. Unified Boolean query**: One query language with capital AND/OR and parentheses.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **9. XPath as a clause type**: XPath is one clause type among image, color, CSS, accessibility, text and relatives. The old XPath syntax is deprecated but still works, and the dictionary emits the combined form with XPath as the fallback.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **10. Universal syntax**: The same syntax and verbs work across browsers and desktops, with only the platform differences that are actually required.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **11. Relative locators**: Elements can be located relative to other elements (near, above, below, inside, after, and so on).
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **12. Scored label affinity**: Labels bind to interactables through a scored affinity rather than a single fixed rule.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **13. Negative relations**: Queries can say what an element is NOT related to (for example, not inside a section, or not near a label), and this feeds the scoring.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **14. Bounding boxes and the one-match rule**: Bounding boxes with size caps, and a rule that a query resolves to exactly one match or reports why.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **15. Section detection**: Pages and windows are split into detected sections, with their nesting.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **16. Universal composite snapshot**: The screenshot is combined with page source/structure, DOM or element structure, XPaths or paths and viewport mapping on ALL platforms: Chrome, Chromium, Firefox, Edge, Windows UI Automation, macOS Accessibility and Linux AT-SPI.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **17. Pre-mapping as a core principle**: The snapshot pre-renders and pre-maps as much as possible (labels to interactables, sections and nesting, bounding boxes, viewport coordinates, XPath indexes, image and color features) so runtime queries are light and fast. This is a primary goal, not an optional optimization.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **18. Dalton portable artifact**: Dalton is a portable artifact with hydrate/extract, GitHub info, logs, binary references and sync guards, and it is central to pickleball.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **19. Rust to Java**: All Rust is replaced with Java and integrated, keeping every capability (query, geometry, observe, actions, CLI, MCP).
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **20. Cucumber optional**: Cucumber becomes optional, with unified feature paths.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **21. Workbench**: The Workbench GUI can edit everything: element categories, configuration and data files, scenarios and steps, and Java or JS/TS step definitions.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?

- **22. Dynamic control and parity harness**: Dynamic control, with a parity harness and a default switch so engines are compared before a default changes.
  - In code? Is there code for it on 3.0.0? (file:line evidence)
  - Practical? Is it feasible and sensible as designed, or does the design need adjusting?
  - Implemented? Fully, partially, missing or reversed; tested? Do docs agree?
