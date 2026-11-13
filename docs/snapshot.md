# Snapshot and pre-map

`domengine premap --file page.html` writes a composite snapshot (`schema` `pickleball.composite-snapshot`, `version` 1, `transport` `fixture`). The builder always runs the pre-map. `domengine desktop snapshot` writes the same artifact for a desktop session. `CompositeSnapshots.browser` is not in this build: it waits on engine-defects Phase 5.

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

`xpath` is absolute and index-qualified inside one frame document, for example `/html[1]/body[1]/form[1]/input[2]`. Inside a shadow tree it is the path from the shadow root, and `shadow_path` is the host XPath chain joined by ` >> `. `path` is the `backend_node_id`. The offline query engine rejects numeric index predicates, so `XPathIndex.resolve` is the resolver that honors them. The engine still finds the node when those indexes are removed.

Coordinate spaces are separate. Offline HTML fills `document_css_px` from `data-x`, `data-y`, `data-w`, and `data-h`. `layout_viewport`, `visual_viewport`, and `frame_document` stay null until a live browser producer has those measurements. They are not copies of `document_css_px`. A desktop snapshot leaves `document_css_px` null and fills `screen_px` from the accessibility box.

`image_px = (visual_viewport box) × image_scale`. `image_scale` is screenshot width divided by `viewport.css_width` (1 when there is no screenshot). When a screenshot is present and `visual_viewport` is null, offline HTML uses `document_css_px` and logs one warning, `snapshot fallback image_px used document_css_px`. Desktop snapshots are in screen space: `image_px` is the `screen_px` box minus the window origin, times `image_scale`, and that warning is not logged. The window origin is `viewport.screen`.

Offline viewport size is the `html` or `body` box when that element has one, otherwise 1280 by 800. A desktop window with a box is the viewport when the tree has no `html` or `body` box. `--out-dir` writes `snapshot.json` and, when a screenshot was captured or `--screenshot` is set, `screenshot.png`. Without `--out-dir` the screenshot is inlined as `base64` and `path` names the file that was read.

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

## Pre-map (core)

Every `CompositeSnapshotBuilder.build` runs `PreMapBuilder`. There is no flag to skip it. The pre-map is the thing a later query is supposed to read, so the expensive work happens once at capture.

`PreMapLookup.installed()` and `PreMapLookup.PASS_THROUGH` still return an empty `current` and an empty `candidates`. `current` stays empty until runtime lookups. `forTree` caches a built pre-map by tree identity, and `candidates` on that lookup reads the indexes. An empty document has nothing to index, so `candidates` stays empty there. That is the pass-through default the existing test locks. `store` keeps a session map for a later `current`.

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

## Desktop producers

`DesktopSnapshotProducer` builds one composite snapshot from a `DesktopTree` session. `domengine desktop snapshot --id ID [--screenshot file.png] [--out-dir DIR]` is the CLI. `DesktopTree.VERBS` includes `snapshot`. `doc_version` is `desktop:` plus the session sequence. `attach` starts at 0. `walk`, `click`, and `type` bump the sequence. A fixture does not recapture the tree. A live session recaptures on `walk`.

A fixture session has no display. It requires `--screenshot file.png` and otherwise fails with `error: desktop fixture snapshot needs --screenshot`. A live session captures the window rectangle (the root box, or the union of node boxes) when `--screenshot` is omitted. The source is `a11y-xml`: the projected tree serialised as `<node role name x y w h>`. `path` is `uia-runtime-id`, `ax-path`, or `atspi-path` when the capture wrote one, otherwise the numeric id. `xpath` is the index-qualified path over that tree.

Platform and transport:

| Host | `platform` | Live `transport` | Tree |
|---|---|---|---|
| Windows | `windows-uia` | `uia` | `WindowsUia.capture`. States `disabled`, `focused`, `offscreen`, `checked` (Toggle On), `expanded`. `LabeledBy` is `labeled-by` plus the label runtime id |
| macOS | `macos-ax` | `ax` | `MacAccessibility.capture`. States from AXEnabled, AXFocused, AXValue on checkboxes and radios, AXExpanded, and AXOffScreen. `AXTitleUIElement` is resolved to a sibling `ax-path` and stored as `labeled-by` |
| Linux | `linux-atspi` | `atspi` | `AtSpi.capture`. `GetState` bits: enabled absent is `disabled`, checked, expanded, focused, and showing+visible both clear is `offscreen` (`displayed` false, reported as `hidden` because the schema state list has no `offscreen`). `GetRelationSet` types label-for and labelled-by |

A fixture's platform follows `os.name` and its transport is `fixture`.

Screenshot tools, in order:

- Windows: PowerShell `System.Drawing.CopyFromScreen` after `SetProcessDPIAware`, so pixels match UIA physical coordinates.
- macOS: `screencapture -x -R x,y,w,h -t png -`. `image_scale` is image pixels divided by AX points (2.0 on Retina). A black or empty PNG, or a `could not` / `permission` error, is `error: screen recording permission denied` with a `fix:` line. It does not fall through to Robot.
- Linux Wayland (`XDG_SESSION_TYPE=wayland` or `WAYLAND_DISPLAY`): `grim -g "x,y wxh" -`.
- Linux X11: ImageMagick `import -window root -crop WxH+X+Y png:-`, then `xwd -root -silent` parsed in Java.

`image_scale` is also the ratio of returned pixels to the requested width when the tool command names that width.

When the native tool is missing and a display exists (`DISPLAY`, `WAYLAND_DISPLAY`, or Windows or macOS), the fallback is `java.awt.Robot` in a child JVM (`DesktopScreenshot robot x y width height`). That logs one WARN per run and reason: `desktop-shot fallback robot reason=native-tool-missing`. With no display and no tool the error is `error: desktop screenshot failed` and the `fix:` names grim, ImageMagick `import`, or `xwd`. A snapshot is not returned without a screenshot. Every capture subprocess has a 10 second timeout and its process tree is killed on timeout.

The Windows live UIA test is `LiveDesktopSnapshotTest` (the capture script names `UIAutomationClient`, `LabeledBy`, and `IsOffscreen`). The workflow step that runs it on `windows-latest` waits on Phase 8, which waits on engine-defects Phase 7. macOS has no CI runner. On this Linux host the live AT-SPI test skips with `no AT-SPI bus` because `DBUS_SESSION_BUS_ADDRESS` is unset and there is no session socket, and `DISPLAY` is unset.
