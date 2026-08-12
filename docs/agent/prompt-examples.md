# Minimal Prompt Examples

After installing this bundle, use a write-capable coding agent and ask primarily for the desired behavior.

## Typical prompts

> Add support for resolving template references in nested XML request bodies.

> Allow template references in XML attributes while preserving existing text-node behavior.

> Make stale Selenium elements relocate before `getText()` and add regression coverage.

> Preserve Jackson text-node values as unquoted strings during parsing-map resolution.

> Add a dynamic step for selecting an option by visible text.

> Change component service-call scenarios so mapped values preserve JSON number and boolean types.

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
