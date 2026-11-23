# Minimal Prompt Examples

After installing this bundle, use a write-capable coding agent and ask primarily for the desired behavior.

## Typical prompts

> Add support for resolving template references in nested XML request bodies.

> Allow template references in XML attributes while preserving existing text-node behavior.

> Make stale Selenium elements relocate before `getText()` and add regression coverage.

> Preserve Jackson text-node values as unquoted strings during parsing-map resolution.

> Add a dynamic step for selecting an option by visible text.

> Change component service-call scenarios so mapped values preserve JSON number and boolean types.

> Drive a live browser and talk to a person: `start --url-key=URL.app --browser=chrome --headed --panel`, then `chat-turn --until=human`, `act --query=Save --pick=1`, `ui-verify`, and `doctor`. `execute-steps --file=steps.json` runs those verbs on the live worker (`doctor` stays in this process; `stopped` is true only with `--stop-on-fail`). `capture-steps` writes `{op,args}` for that file; an empty capture is count 0. `capture-scenario` reads the same network buffer as `observe`. A live `magnifier` scales the copied target around its center so the label stays in the oval, or says target not captured. `discover` and `confirm` do not say PASSED when failures are greater than 0, and the summary counts are this launch only. `isolate` seeds a missing run id. A live page uses `start` instead of a worker restart. screenshot --out is closed before the reply. Discover, confirm, and the first isolate take as long as the run and the workspace copy. No faster copy is claimed. Use `call` when there is no IDE MCP client. Read `items` once. Pass a short id (`s1.h42`) to every target. `--fields` keeps only those keys and `--budget` caps the JSON. Resume with `--cursor`; each channel is separate. To record a reusable scenario, `record --start`, let the person click and type, then `record --editor` to rewrite, reorder, nest, add, or delete steps, `record --script`, `record --save --name=checkout`, and `record --run --name=checkout --param=name=value`. `record --popout --panel=editor` or `--panel=chat` opens that panel in its own window; `record --popin` puts it back. The consumer example is `maven-consumer-project/src/test/resources/features/recorded-collaboration.feature`. The page engine is Chrome DevTools; do not pass `--engine=selenium`. `observe --request` and `--response` filter traffic. `selftest ui --component=feedback` checks sending, delivered, and read without Chrome. Unattended recipes and the human-participation script are `docs/collaborative-self-test.md`.

The agent should retrieve project setup, affected documentation, tests, consumer examples, and validation requirements from the repository.

## Add useful acceptance detail when needed

The repository can supply project background, but it cannot infer an unstated product decision. Include concise acceptance detail when multiple new behaviors are plausible:

> Add XML attribute template resolution. Resolve the entire attribute as its native mapped value only when the attribute contains a single reference; otherwise interpolate as text.

> Add stale-element retry to `getText()`. Retry exactly once and only for `StaleElementReferenceException`.

## Useful control phrases

> Preserve backward compatibility.

> This is intentionally a breaking change; update migration documentation.

> Do not change the public DSL.

> Implement this only in the Maven consumer example; do not change framework behavior.

> Analyze the impact and tests, but do not edit files.

You should not need to restate what Pickleball is, how the consumer project works, which Java version is used, or that documentation and executable examples must be updated.
