# Snapshot and pre-map

`domengine premap --file page.html` writes a composite snapshot (`schema` `pickleball.composite-snapshot`, `version` 2, `transport` `fixture`). The builder always runs the pre-map. `domengine desktop snapshot` writes the same artifact for a desktop session. `domengine snapshot --session ID` (or `--cdp URL --i-accept-local-attach`) writes it for Chrome, Chromium, and Edge. The browser JSON adds `ok: true` and `document`, the pierced tree from `DomNode.toJson` (that object still contains `shadow_root`). Early 3.x still accepts a version 1 document that has no canonical tree. Producers emit version 2. `capabilities.snapshot_schema` stays 1.

Logging uses `dev.domengine.log.EngineLog`. The entities are `snapshot`, `premap`, `affinity`, and `desktop-shot` (`DOMENGINE_LOG` or `COMPOSITE_LOG`). A protocol or tool fallback logs one WARN per run and reason through `SnapshotWarnings.once(reason)`.

## Schema

The machine-readable schema is `dom-engine/src/main/resources/dev/domengine/snapshot/composite-snapshot.schema.json` (JSON Schema draft 2020-12). `capabilities` reports `snapshot_schema` 1 and `premap` true.

| Field | Contents |
|---|---|
| `schema`, `version`, `id` | `pickleball.composite-snapshot`, version 1, snapshot id |
| `session_id`, `doc_version` | Session id. Offline `doc_version` is `offline:` plus the first 12 hex digits of the SHA-256 of the HTML |
| `platform` | `chrome`, `chromium`, `edge`, `firefox`, `windows-uia`, `macos-ax`, or `linux-atspi`. Offline HTML uses `chromium` unless `--platform` is set |
| `transport` | `cdp`, `bidi`, `selenium-fallback`, `uia`, `ax`, `atspi`, or `fixture`. Offline premap uses `fixture` |
| `captured_at`, `duration_ms` | ISO-8601 time with a numeric offset. Capture duration in milliseconds |
| `viewport` | `css_width`, `css_height`, `device_pixel_ratio`, `scroll_x`, `scroll_y`, `visual` (`offset_x`, `offset_y`, `scale`), `screen` (`x`, `y`, `width`, `height`) |
| `screenshot` | `format` `png`, `width_px`, `height_px`, `image_scale`, `clip`, `sha256`, and `path` or `base64` |
| `source` | `kind` (`html` or `a11y-xml`), `text` or `path`, `bytes` |
| `frames[]` | `id`, `parent_id`, `url`, `frame_path`, `offset` |
| `nodes[]` | Flat list. See below |
| `premap` | Sections, labels, caps, indexes, viewport map, colours, and image features |
| `warnings[]` | Strings raised during capture |

Each `nodes[]` entry has `handle`, `parent`, `tag`, `role`, `name`, `text` (at most 200 characters), `value`, `attributes`, `states`, `frame_id`, `frame_path`, `shadow_path`, `xpath`, `path`, `boxes`, `in_viewport`, `interactable`, `capped`, `cap_reason`, `section_path`, and `paint`.

`xpath` is absolute and index-qualified inside one frame document, for example `/html[1]/body[1]/form[1]/input[2]`. Inside a shadow tree it is the path from the shadow root, and `shadow_path` is the host XPath chain joined by ` >> `. Browser `path` is the `backend_node_id`. Desktop `path` is `uia-runtime-id`, `ax-path`, or `atspi-path` when the capture wrote one. A version 2 node also carries `identity`. An absolute path whose every step is `tag[n]` is answered by `XPathIndex`, and a miss is zero hits. Other XPath 1.0 expressions evaluate on the canonical tree. An invalid expression is an error.

Coordinate spaces are separate. Offline HTML fills `document_css_px` from `data-x`, `data-y`, `data-w`, and `data-h`. `layout_viewport`, `visual_viewport`, and `frame_document` stay null on that path. They are not copies of `document_css_px`. The CDP producer fills all four from one `DOMSnapshot.captureSnapshot` and `Page.getLayoutMetrics`. A desktop snapshot leaves `document_css_px` null and fills `screen_px` from the accessibility box.

`image_px = (visual_viewport box) × image_scale`. `image_scale` is screenshot width divided by `viewport.css_width` (1 when there is no screenshot). When a screenshot is present and `visual_viewport` is null, offline HTML uses `document_css_px` and logs one warning, `snapshot fallback image_px used document_css_px`. Desktop snapshots are in screen space: `image_px` is the `screen_px` box minus the window origin, times `image_scale`, and that warning is not logged. The window origin is `viewport.screen`.

Offline viewport size is the `html` or `body` box when that element has one, otherwise 1280 by 800. A desktop window with a box is the viewport when the tree has no `html` or `body` box. `--out-dir` writes `snapshot.json` and, when a screenshot was captured or `--screenshot` is set, `screenshot.png`. Without `--out-dir` the screenshot is inlined as `base64` and `path` names the file that was read.

## Version 2

Version 1 stays `$id` `urn:pickleball:composite-snapshot:1` and schema `dom-engine/src/main/resources/dev/domengine/snapshot/composite-snapshot.schema.json`. Version 2 is `$id` `urn:pickleball:composite-snapshot:2`, schema `composite-snapshot-v2.schema.json`, family name `pickleball.composite-snapshot`, and `version` 2. Version 2 adds `canonical`. `canonical.kind` `html` is browser HTML. `canonical.kind` `html-ish` is the desktop synthesis and requires `canonical.side`. `html` with `side` is an error. A version 1 document does not carry `canonical` or `identity`.

Browser canonical HTML is one document per frame. It is serialized from the captured DOM tree (CDP DOM, or `page-walk.js` on the facade), not from the browser's `outerHTML`. That is one pierced tree, including closed shadow roots and cross-origin frames, with the captured attribute order, and the serializer does not change the live page. `outerHTML` stays on `source.text`. An open or closed shadow root is `<template data-pkb-shadow="open|closed">`. An iframe stays in the parent and carries `data-pkb-frame`. The iframe document is a separate canonical frame. `CanonicalProjection` links that attribute and overlays live boxes, roles, names, and states onto the parsed tree, because raw HTML does not carry accessibility geometry.

Desktop canonical HTML is synthesized from the UIA, AX, or AT-SPI tree. The role-to-tag map is `NativeDesktop.mapRole`. A capture root whose projected role is `desktop` is not emitted. Its children are the body. UIA `AutomationId` becomes `id` only when it matches `^[A-Za-z][A-Za-z0-9_:-]*$`, and it is always `data-uia-automation-id` for backend `uia`. UIA `ClassName` becomes `class` and `data-uia-class` for that backend only. Help text becomes `title`. Geometry is `data-x`, `data-y`, `data-w`, and `data-h`. `canonical.side.a11y_xml` is the same string as `source.text`. The native path stays on that side data. FlaUI.WebDriver is not added. An `input` or `img` that has children is written with an end tag, and the parser keeps those children. A void tag with no matching end tag stays empty, so the next sibling is not swallowed. A stray end tag does not stop the parse. Void end-tag names are collected once per parse, so a page of bare void tags does not rescan the document. The same parser re-reads browser canonical HTML.

`nodes[]` and `premap` are a projection of the canonical tree. They are not a second stored tree. Two builds of the same canonical HTML produce the same `nodes[]` and `premap`.

### Replay identity

Each version 2 `nodes[]` entry may carry `identity.chain`. The steps are tried in order. A missing source is omitted. Replay stops at the first hit and reports `matched` (`stable`, `native-path`, `fingerprint`, `xpath`, or `none`), the locator, the zero-based chain index, `handle` (the node id), and `backend_node_id` when the node has one. `none` uses locator `""`, index `-1`, and a null `handle`. A step that now matches more than one node is not a hit. `query --identity` and `desktop query --identity` replay a chain array, an identity object `{chain}`, or a v2 node `{identity:{chain}}`, as inline JSON or a file, and print that result. A malformed value is exit 1, code `invalid`, message `invalid identity chain`. It does not run the selector. `DomCompositeAdapter.replayIdentity` forwards to `IdentityChain.replay` and logs `INFO identity replay matched=<kind>` only when a chain is present. No production path calls it. Query, act, stale refresh, and recorded Gherkin replay do not re-resolve with the chain. The forwarder is kept for a future recorder.

| Order | `kind` | Locator |
|---|---|---|
| 1 | `stable` | The first unique value among `id=<id>`, `data-testid=<value>`, `data-uia-automation-id=<value>` when that value was not copied to `id`, then `role=<role>[name="<name>"][section="<section>"]`. The name is the accessible name. A blank or non-unique value is skipped. Only one stable step is recorded. |
| 2 | `native-path` | `uia-runtime-id`, `ax-path`, or `atspi-path` when the value is not all digits. A bare numeric id and a browser `backend_node_id` are not chain steps. |
| 3 | `fingerprint` | `dhash=<unsigned>;text=<text>;rel=<x>,<y>,<w>,<h>`. dHash uses `TemplateMatch.prefilter` at 10 bits. Text is exact after trim. Each relative component must be within 0.05. A missing part is omitted. Missing dHash matches on text plus relative bounds. |
| 4 | `xpath` | `xpath=<path>;shadow=<shadow>;frame=<frame>`, resolved by `XPathIndex.resolve`. |

### Human and full-access masks

The masks are applied before DSL, XPath, and CSS evaluation. They are cached once per root object and document version, at most 32 entries, keyed by the root object rather than its identity hash. A blank document version adds a stamp of element count and node id and geometry, so an in-place edit is recomputed. `exposure=human` keeps a node that is seen, scroll-reachable, or expand-reachable. Seen means the box intersects a known viewport, the node is displayed, and no later opaque box covers it when `data-paint-order` was stored. Scroll-reachable and expand-reachable are computed from stored boxes, scrollport sizes, and collapsed regions. The engine does not scroll or expand. A collapsed control with no stored region and no box is not reachable. `exposure=full` and `exposure=full-access` keep every node. A query with no exposure stays unfiltered so fixture queries keep their structural results. Real-use callers pass `exposure=human`. An action lookup sets `mask=off`, so a click or type still finds a target outside the human view. Human mode governs mouse and keyboard input only.

### CSS

`css=` evaluates on the canonical tree after the view mask. The in-repo matcher accepts the `+` and `~` combinators, the `~=` and `|=` attribute operators, and these Level 3 pseudo-classes: `:nth-child`, `:nth-last-child`, `:nth-of-type`, `:nth-last-of-type` (`an+b`, `odd`, `even`), `:first-child`, `:last-child`, `:only-child`, `:first-of-type`, `:last-of-type`, `:only-of-type`, `:not` of one compound, `:checked`, `:disabled`, `:enabled`, `:empty`, and `:root`. Sibling counts use element siblings. `:disabled` and `:enabled` are HTML form controls (`button`, `input`, `select`, `textarea`, `optgroup`, `option`, `fieldset`), including a disabled `fieldset` except its first `legend`. `:not` rejects a combinator, a comma, and a nested `:not`. An unsupported pseudo-class or a pseudo-element (`::before`, `:before`, `:after`, `:first-line`, `:first-letter`) is an error, not zero hits. `css-simple` still returns an empty index answer when the selector contains `:`, so those selectors stay on the matcher. No new CSS library.

### OCR

`image=ocr` and `image=ocr:<text>` run `OcrHook` for tag `img` or `svg`, role `img`, `image`, `graphics-symbol`, `graphics-document`, or `icon`, an `i` or `span` with an icon-font class, and a desktop icon role. A `use` inside an `svg` is not a second target. The cache key is the SHA-256 hex of the crop width, height, and pixels. One process cache is shared by both views. A hit returns the stored text and does not open the runtime. An element outside the human mask is not recognized and is not a cache miss. A full-access call can fill the cache. A later human call for the same hash uses that entry when the element is in the mask. `image=ocr` keeps non-blank text. `image=ocr:<text>` keeps an exact match. A query with no eligible element does not call the runtime.

Runtime selection is the `pkb_ocr_runtime` property, then env `PKB_OCR_RUNTIME`. A blank property falls through to the environment. A set property does not fall through. The value `onnx` selects `OnnxOcrRuntime`. That slot does not load a native library, does not download a model, and always returns `ok` false, error `OCR runtime not installed: the Microsoft-signed ONNX Runtime and model are not bundled`, code `ocr_runtime_missing`. Any other value is a file path. Unset, blank, a missing file, or a directory returns `OCR runtime not installed`, code `ocr_runtime_missing`. An existing file whose first line is `pkb-ocr 1` is `StubOcrRuntime`, the stub/test lookup. It maps a crop hash to the rest of that line and does not execute the file. A hash with no line stores an empty string. Any other existing file returns `OCR runtime cannot be invoked`, code `ocr_runtime_unsupported`. Failures are not cached. Cache and the human-mask gate stay in `OcrHook`. `ocr-views` is a comma list of `human` and `full` (default both). The active view is `human` when `exposure=human` and the mask is on, otherwise `full`. A view that is not listed returns zero hits, `image.skipped` `ocr-views`, and does not read the runtime. The shared hash cache stays and is reused when that view is listed again.

## Label affinity

`dev.domengine.affinity.AffinityScorer` is a pure scorer. `score(label, interactable, relations)` returns an `AffinityScore` of `total`, `parts`, and `reasons`. `scoreValue` returns `total`.

The relations argument is `List<dev.domengine.query.RelationTerm>`. Each term is read through `relation()`, `negated()`, `anchor()`, and `weight()`. The relation name is the enum constant (`INSIDE`, `LEFT_OF`), lower-cased, with `-` mapped to `_`.

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

## Pre-map (core)

Every `CompositeSnapshotBuilder.build` runs `PreMapBuilder`. There is no flag to skip it. The pre-map is the thing a later query is supposed to read, so the expensive work happens once at capture.

`PreMapLookup.forTree` caches a file query by tree identity, and `candidates` on that lookup reads the indexes. An empty document has nothing to index, so `candidates` stays empty there. `store` keeps a map for a caller that already built one. It does not satisfy `current`. `installed()` is the live session lookup, not `PASS_THROUGH`. With no live session it still answers nothing. A live browser or desktop session is served by that lookup.

## Runtime lookups

A session query reads `doc_version`, then `PreMapLookup.current(sessionId, docVersion)`.

- Browser version is `<loaderId>:<mutationCount>`. When the query does not pass `--doc-version`, one isolated-world evaluate in `pkb-premap` reads the count (`Page.getFrameTree` supplies the loader id). Desktop version is `desktop:<sequence>`. `click`, `type`, and `walk` bump that sequence.
- A cache hit returns the pre-map for that session and version. It does not read the DOM again. `--explain` shows `premap: hit`. The entity `premap` logs `hit nodes=<n> ms=0`.
- A miss rebuilds through the producer path. The query already holds the document, so a browser rebuild uses that tree and does not call `DOM.getDocument` again (a second call would invalidate the node ids the query returns). Layout is one `DOMSnapshot.captureSnapshot` plus `Page.getLayoutMetrics`. With no held tree the rebuild falls back to `LiveTree.read`. A desktop rebuilds from the tree already captured for that sequence. Replacing or closing a desktop session drops that session's cached maps, and the cache key includes the tree identity, so a new tree does not reuse another tree's handles. The screenshot is taken only when the query has an `image` or `look` clause, and that capture stays with the cached version. `--explain` shows `premap: rebuilt <ms>`. The entity `premap` logs `rebuild nodes=<n> ms=<t>`.
- G2's hook asks `candidates` for each searchable stage. A session map, or `--rank affinity`, ranks relatives with `AffinityScorer`. `--strict` resolves through `OneMatch`, and an ambiguous match lists the candidates and reasons.

What is filled now:

- `sections`, `labels`, `unlabeled`, and `boxes` (the caps and the rejected count).
- `indexes`:
  - `by_role`, `by_tag`, `by_id`, `by_class`, `by_xpath` (the index-qualified path)
  - `by_role_name`, key `role` + tab + name. The name is the accessible name, or the bound label text when the control has none
  - `by_text_token` (lowercase tokens). An exact string is stored under a prefix that a token lookup does not use
  - `by_section_title` (the section title, and `#` plus the section id, mapping to every element whose `section_path` contains that section)
  - `interactables`, `in_viewport`
- `viewport_map`: handle to the `image_px` box when a screenshot is present, otherwise the visual or document box.
- `colours`: `fg`, `bg`, and WCAG `contrast`. Colour walks ancestors. A painted background stops at the first non-transparent one. Missing colours are black on white.
- `image`: a 16 by 16 luminance `grid` of the screenshot, and `nodes` for each interactable and label with `dhash`, `mean`, `stddev`, and `mean_rgb`.

`PreMapLookup.candidates(kind, value)` returns an empty `Optional` when the index cannot answer, and a list (possibly empty) when it can. Kinds:

| Kind | Value | Answer |
|---|---|---|
| `role`, `tag`, `class` | case-insensitive name | handles |
| `id`, `xpath-abs` | exact id or absolute xpath | handles |
| `role-name` | `role` + tab + name | handles |
| `text` | one token | handles whose text contains that token |
| `text-exact` | the whole string | handles whose text or accessible name equals it |
| `css-simple` | one compound selector (`button.primary`, `input#email`, `.tool`) | intersection of the tag, id, and class indexes. A combinator, comma, pseudo, or attribute selector is an empty `Optional` |
| `label` | a label handle or the label's exact text | the bound control |
| `section` | a section id or title | elements inside that section |
| `image` | `dhash` or `dhash@mean` | interactables kept by `TemplateMatch.prefilter` |

`TemplateMatch.Features` is the 64-bit dHash plus luminance mean and standard deviation. `prefilter` keeps a candidate when the dHash is within 10 bits and the mean is within 18 levels. `score` and `crop` are unchanged. A runtime query is a lookup because those indexes already name the handles. Anything the index cannot answer is still evaluated live.

The build logs `premap build nodes=<n> ms=<t>`. The budget is 250 ms for 2,000 nodes. The log is the check. The test does not assert the time.

## Browser producers (CDP)

`CdpSnapshotProducer` is the Chrome, Chromium, and Edge producer. `CompositeSnapshots.browser(session, options)` calls it. A null session fails with `error: browser snapshot needs a page session` and `fix: Attach with --session or --cdp before snapshot.`

`Browser.getVersion` sets `platform`. A product string that contains `Edg/` is `edge`. One that contains `Chromium/` is `chromium`. Chrome, including Chrome for Testing, is `chrome`. `transport` is `cdp`.

The call list is one shot per document, not one shot per node:

1. `DOM.getDocument` with `pierce` and `Page.getFrameTree` (`LiveTree.read`). A same-origin iframe arrives as `contentDocument`. A cross-origin iframe is grafted from its child target.
2. One `DOMSnapshot.captureSnapshot` (`computedStyles`, `includeDOMRects`) joined to nodes by `backendNodeId`, plus one `Page.getLayoutMetrics`.
3. One `Accessibility.getFullAXTree` per frame, for `role`, `name`, and the states `disabled`, `checked`, `selected`, `expanded`, `focused`, `readonly`, and `required`.
4. One `Page.captureScreenshot` (`format` `png`, `fromSurface` true). `--full-page` adds `captureBeyondViewport`. The default clip is the layout viewport.
5. One `DOM.getOuterHTML` per frame document. The texts are joined with a `<!-- frame:id -->` marker. `source.kind` is `html`. `canonical.frames[].html` is `CanonicalHtml` of the pierced CDP tree, not this `outerHTML`.
6. `doc_version` is `<loaderId>:<mutationCount>`. The counter lives in the isolated world `pkb-premap`. The producer installs it with `Page.addScriptToEvaluateOnNewDocument`, opens the world with `Page.createIsolatedWorld`, and reads it with one `Runtime.evaluate`. A failure keeps `<loaderId>:0` and logs one warning.

`LiveTree.readLayout` does not call `CSS.getComputedStyleForNode`, `DOM.getBoxModel`, `Accessibility.getPartialAXTree`, or `DOM.resolveNode`. Those stay on the per-node `enrich` path used by actions.

Box spaces, from the snapshot bounds (frame-document coordinates) and the layout metrics:

| Space | Value |
|---|---|
| `frame_document` | The snapshot bounds, unchanged |
| `document_css_px` | `frame_document` plus the enclosing iframe's document position. The main frame origin is `0,0` |
| `layout_viewport` | `document_css_px` minus `cssLayoutViewport` `pageX` / `pageY` |
| `visual_viewport` | (`layout_viewport` minus the visual viewport `offsetX` / `offsetY`) times `scale` |

`display: none`, `visibility: hidden` or `collapse`, and `opacity: 0` from that same style row set `displayed` false, which the builder reports as the state `hidden`. `frames[]` uses the CDP frame ids. A child frame's `frame_path` is the iframe's index-qualified XPath and its `offset` is the iframe's document position. A node's `frame_path` is that same chain (` >> ` between nested iframes). `document.evaluate` of a node's `xpath` in that frame returns the same element.

`--out-dir DIR` writes `screenshot.png` and `snapshot.json`. Without it the screenshot is inlined as `base64`.

`DomCompositeAdapter.compositeSnapshot(options)` forwards the options to the joined engine when `capabilities.snapshot_schema` is listed. A missing capability throws `error: snapshot_schema is absent`.

## Firefox and root producers

`SnapshotProducers.page(driver, options)` is the root page snapshot. It tries three producers, in order:

1. The joined engine, when `CompositeRuntime.browserJoined()` is true and `capabilities.snapshot_schema` is listed. That is `Client.snapshot`, the same CDP producer as `domengine snapshot`.
2. `FacadeSnapshotProducer`. `ProtocolSelector.forDriver` returns a `BidiAdapter` for Firefox and a `CdpAdapter` for Chromium when the engine is not joined. The facade walks every browsing context from `frameTree()`, reads `page-walk.js` in the `pkb-premap` world, takes one screenshot through the adapter, and builds with `CompositeSnapshotBuilder` in this process. `doc_version` is `<context id>:<mutation count>`. The counter is installed with `addPreload` and read with `evaluateIsolated`. Firefox's `platform` is `firefox` and its `transport` is `bidi`. A Chromium facade keeps `transport` `cdp`.
3. The Selenium fallback. `transport` is `selenium-fallback`. The same walk script runs through `JavascriptExecutor`, and the screenshot is `TakesScreenshot`. There is no `executeCdpCommand` on this path.

`page-walk.js` returns, for the document it runs in, the tag, attributes, role and name hints, a computed-style subset, `getBoundingClientRect`, `getClientRects`, scroll offsets, `visualViewport`, row states, an index-qualified XPath, and `outerHTML`. Canonical HTML is written from the walked tree, not from that `outerHTML` string. The builder still assigns the XPath index. `frame_document` is the rect plus that frame's scroll. `document_css_px` adds the enclosing iframe's document position. `layout_viewport` subtracts the top document scroll. `visual_viewport` subtracts the visual offset and multiplies by scale. Those four are not copies of each other.

A missing direct adapter logs one line, `WARN snapshot fallback selenium reason=no-adapter`. An adapter that throws logs `WARN snapshot fallback selenium reason=adapter-error:<message>`, once per run and reason, even when `pkb_composite_log` is off. The artifact is still returned. `CompositeSnapshotProducer` per-element snapshots are unchanged.

## Desktop producers

`DesktopSnapshotProducer` builds one composite snapshot from a `DesktopTree` session. `domengine desktop snapshot --id ID [--screenshot file.png] [--out-dir DIR]` is the CLI. `DesktopTree.VERBS` includes `snapshot`. `doc_version` is `desktop:` plus the session sequence. `attach` starts at 0. `walk`, `click`, and `type` bump the sequence. A fixture does not recapture the tree. A live session recaptures on `walk`.

A fixture session has no display. It requires `--screenshot file.png` and otherwise fails with `error: desktop fixture snapshot needs --screenshot`. A live session captures the window rectangle (the root box, or the union of node boxes) when `--screenshot` is omitted. The source is `a11y-xml`: the projected tree serialised as `<node role name x y w h>`. `path` is `uia-runtime-id`, `ax-path`, or `atspi-path` when the capture wrote one, otherwise the numeric id. `xpath` is the index-qualified path over that tree.

Platform and transport:

| Host | `platform` | Live `transport` | Tree |
|---|---|---|---|
| Windows | `windows-uia` | `uia` | `WindowsUia.capture`. States `disabled`, `focused`, `offscreen`, `checked` (Toggle On), `expanded`. `LabeledBy` is `labeled-by` plus the label runtime id |
| macOS | `macos-ax` | `ax` | `MacAccessibility.capture`. States from AXEnabled, AXFocused, AXValue on checkboxes and radios, AXExpanded, and AXOffScreen. `AXTitleUIElement` is resolved to a sibling `ax-path` and stored as `labeled-by`. The walk covers foreground apps (`background only` false), does not walk the items of a closed menu (a menu with no size), stops after 250 nodes (`walk-truncated`), and has a 60 second bound. Only a permission denial reports `AX unavailable`; any other osascript error reports `AX capture failed` |
| Linux | `linux-atspi` | `atspi` | `AtSpi.capture`. `GetState` bits: enabled absent is `disabled`, checked, expanded, focused, and showing+visible both clear is `offscreen` (`displayed` false, reported as `hidden` because the schema state list has no `offscreen`). `GetRelationSet` types label-for and labelled-by |

A fixture's platform follows `os.name` and its transport is `fixture`.

Screenshot tools, in order:

- Windows: PowerShell `System.Drawing.CopyFromScreen` after `SetProcessDPIAware`, so pixels match UIA physical coordinates.
- macOS: `screencapture -x -R x,y,w,h -t png <temp file>`, then the file is read and deleted (screencapture has no stdout mode). `image_scale` is image pixels divided by AX points (2.0 on Retina). A black or empty PNG, or a `could not` / `permission` error, is `error: screen recording permission denied` with a `fix:` line. It does not fall through to Robot.
- Linux Wayland (`XDG_SESSION_TYPE=wayland` or `WAYLAND_DISPLAY`): `grim -g "x,y wxh" -`.
- Linux X11: ImageMagick `import -window root -crop WxH+X+Y png:-`, then `xwd -root -silent` parsed in Java.

`image_scale` is also the ratio of returned pixels to the requested width when the tool command names that width.

When the native tool is missing and a display exists (`DISPLAY`, `WAYLAND_DISPLAY`, or Windows or macOS), the fallback is `java.awt.Robot` in a child JVM (`DesktopScreenshot robot x y width height`). That logs one WARN per run and reason: `desktop-shot fallback robot reason=native-tool-missing`. With no display and no tool the error is `error: desktop screenshot failed` and the `fix:` names grim, ImageMagick `import`, or `xwd`. A snapshot is not returned without a screenshot. Every capture subprocess has a 10 second timeout and its process tree is killed on timeout.

The Windows live UIA step in `.github/workflows/agent-contract.yml` runs `SnapshotCoverageTest.liveDesktopWhenThePlatformExists` on `windows-latest`. The Linux job installs `at-spi2-core`, `xvfb`, and `imagemagick` and runs the desktop live snapshot under `xvfb-run`, then runs the Edge and Firefox live snapshot tests. The `macos` job runs `MacAccessibilityScriptTest`, which compiles every generated AppleScript with `osacompile`, and the live desktop snapshot, and fails if either skips. `SnapshotCoverageTest` still emits a `macos-ax` fixture snapshot on every host. On a host with no display the live desktop test skips. A missing `microsoft-edge` or `firefox` binary skips that live test and does not fail the fixture matrix.
