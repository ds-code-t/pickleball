# Changes from the original one-page sample

1. Replaced the single-page-only server with a safe classpath static-file server.
2. Replaced the original `index.html` playground with a navigation dashboard.
3. Added six focused playground pages plus shared CSS and JavaScript.
4. Split the original dynamic-step feature into eight purpose-specific feature files.
5. Added nested and block conditional examples.
6. Added `RUN SCENARIOS` component coverage.
7. Added keyboard-expression and browser-dialog coverage.
8. Added YAML, JSON, CSV, text, and on-demand resource mapping examples.
9. Added project-specific element categories in the test runner.
10. Expanded `URL.yaml` so every page has a named URL.
11. Added `@all`, regression, smoke, execution-type, functional-area, and focused capability tags to every scenario.
12. Added Maven profiles that act as single-command entry points for each tagged functional area.
13. Made `@all` the default Maven test selection while preserving `-Dpkb_tags` overrides.
14. Added `TAGGING.md` and expanded the README with profile commands and tag-expression examples.
