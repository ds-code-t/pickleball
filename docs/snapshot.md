# Snapshot and pre-map

The DOM engine scores labels, caps oversized boxes, and detects nested sections before it builds a page snapshot. The page-level composite snapshot artifact is not produced yet. What follows is the behaviour that is implemented now.

Logging uses `dev.domengine.log.EngineLog`. The entities are `snapshot`, `premap`, `affinity`, and `desktop-shot` (`DOMENGINE_LOG` or `COMPOSITE_LOG`). A protocol or tool fallback logs one WARN per run and reason through `SnapshotWarnings.once(reason)`.

## Label affinity

`dev.domengine.affinity.AffinityScorer` is a pure scorer. `score(label, interactable, relations)` returns an `AffinityScore` of `total`, `parts`, and `reasons`. `scoreValue` returns `total`.

The relations argument is `List<?>` until `dev.domengine.query.RelationTerm` exists. A map or a record with `relation`, `negated`, `anchor`, and `weight` is read. G3 does not declare `RelationTerm`.

Explicit evidence short-circuits to 1.0 and records the part `explicit`:

- a `label` whose `for` equals the control's `id`
- the control's `aria-labelledby` contains the label's `id`
- a `label` that wraps the control
- a `legend` for a control in that `fieldset`
- a desktop `labeled-by` / `label-for` handle captured on the node (`uia-runtime-id`, `ax-path`, or `atspi-path`)

Otherwise the score is a weighted sum in [0, 1]:

| Part | Weight | Rule |
|---|---|---|
| `proximity` | 0.30 | `exp(-d/d0)` in visual space when `hasVisual` is set, otherwise document CSS pixels. `d` is the gap between edges. `d0` is `max(48px, 3 × label height)`. |
| `alignment` | 0.20 | 1.0 when the label is left of the control on the same row (vertical centre overlap at least 50%). 0.9 when the label is directly above and the left edges are within 16 px. 1.0 when the label is right of a checkbox or radio. 0.2 when the label is below. 0.1 when there is no geometry and the label follows the control in document order. |
| `role` | 0.15 | 1.0 when a label-like node (`label`, `legend`, `h1`–`h6`, `dt`, `th`, text, or desktop `text` / `static`) fits a control that wants a label (textbox, combobox, checkbox, radio, slider, spinbutton, listbox, switch, button). 0.2 when a button or link already has its own name. |
| `text` | 0.10 | The larger of token Jaccard and normalised Levenshtein, against the control's name, text, `id`, `name`, `placeholder`, `title`, and `aria-describedby` text. |
| `section` | 0.15 | 1.0 for the same innermost section, 0.5 for the same parent section, 0 for different top-level sections. |

A satisfied positive relation adds `0.10 × weight` (default weight 1). A satisfied negated relation subtracts `0.50 × weight` and records `negated <relation> satisfied`. The total is clamped to [0, 1]. `parts` holds each contribution, including `relation` when a term changes the score, and those contributions sum to `total` when the total is not clamped.

`AffinityWeights.defaults()` is `proximity=0.30, alignment=0.20, role=0.15, text=0.10, section=0.15`. Scoring re-normalises the weights so they sum to 1. Override them with `DOMENGINE_AFFINITY_WEIGHTS` or, when a caller passes a parsed value, the same `key=value` list. An unknown key is a usage error (`usage: unknown affinity weight: ...`). The root RunVar `pkb_premap_affinity_weights` is the name reserved for that list; the engine flag is `--affinity-weights`.

Binding keeps a label when `total` is at least 0.35. Assignment is greedy and one-to-one. Scores within 0.02 are one band and document order breaks that tie. A `legend`, a `group`, and a `th` in the same column may bind to more than one control. Unbound controls are the unlabeled list.

## Boxes, caps and one match

`BoxCaps` decides whether a node may be a target. It does not remove the node from the tree or from a section.

Defaults are `maxAreaRatio` 0.60 of the viewport, `maxWidthRatio` 0.98, and `maxHeightRatio` 0.98. Absolute caps are off (`0`). Flags `--box-max-area`, `--box-max-width`, and `--box-max-height` take a ratio (`0.6`) or pixels (`900px`). Env `DOMENGINE_BOX_CAPS` uses `area=`, `width=`, and `height=` with the same values. The root RunVars are `pkb_premap_box_max_area`, `pkb_premap_box_max_width`, and `pkb_premap_box_max_height`.

`html`, `body`, a document node, a desktop `desktop` node, and a `window` whose parent is missing or is the desktop are never targets. A node whose role the query asked for is exempt from caps: `dialog`, `alertdialog`, `form`, `region`, `main`, `navigation`, `table`, `grid`, and desktop `pane`, `window`, and `group`. A section clause exempts every candidate. A capped node records `capped` and `cap_reason`, for example `area 0.92 > 0.60`.

`OneMatch.resolve(candidates, policy)` is the only enforcement point for a verb that needs one node.

1. Drop targets that are always excluded, then capped targets that are not exempt, then hidden nodes (`hidden` or not displayed), then satisfied negated relations when `policy.negativesFilter` is true, then non-interactables when `policy.actVerb` is true.
2. Return the only remaining candidate.
3. When every remaining candidate has a score and the leader leads the second by at least `margin` (default 0.15, flag `--one-match-margin`, RunVar `pkb_premap_one_match_margin`), return the leader. The explain text is `chosen by score margin 0.23`.
4. Otherwise throw `OneMatchException`. The message starts with `ambiguous: N candidates` or `not found`, so existing `code` mapping still sees `ambiguous` or `not found`. Each candidate carries `handle`, `role`, `name`, `text` (at most 40 characters), `box`, `score`, and `reason` (`kept`, `rejected: box-cap area 0.92>0.60`, `rejected: hidden`, `rejected: not-inside dialog satisfied`, `tie: 0.81 vs 0.80 < margin 0.15`).

Desktop `click` and `type` use an act policy (negated relations filter, interactable only). Desktop `resolve` does not. The desktop CLI error JSON adds `candidates` next to `error`. `--role button` is accepted on desktop verbs when no pipeline is set. List queries are unchanged.

## Sections

`SectionDetector.detect(root, platform)` returns nested `Section` records: `id`, `kind`, `title`, `handle`, `box`, `depth`, `parentId`, `childIds`, and `memberCount`. Every visited node gets `sectionPath`, root id through innermost id, joined by `/`.

The root section always exists. On a browser document its kind is `document`. On a desktop tree it is the root's own kind, or `window` when the root has none.

Browser kinds are `landmark` (`header` / banner, `nav`, `main`, `aside` / complementary, `footer` / contentinfo, `search`, and `section` or `region` with an accessible name), `form`, `fieldset`, `dialog` (including `alertdialog` and `aria-modal`), `table` (`grid` / `treegrid`), `tabpanel`, `details`, `iframe`, and `heading`. A heading section runs from an `h1`–`h6` or `role=heading` to the next heading of the same or higher level in that container. Deeper headings nest inside it.

Desktop kinds, from the projected role, are `window`, `dialog`, `pane`, `group`, `tab`, `tabpanel`, `toolbar`, `menu`, `menubar`, `list`, `table`, `tree`, and `form`.

Titles are chosen in this order: `aria-labelledby` text, `aria-label`, the first heading inside (not used as the title of a heading section), then `legend`, `caption`, or `summary` for those kinds, then the accessible name. Shadow trees and iframe documents nest inside the section that contains the host or the iframe.

`PreMap` is the document model those results will be stored in. `PreMapLookup.PASS_THROUGH` and `PreMapLookup.installed()` return an empty `current` and an empty `candidates`, so a caller evaluates the clause live. `store` and `forTree` keep a map, and `forTree` caches an offline tree by identity. `candidates` on that lookup stays empty until the indexes are filled.
