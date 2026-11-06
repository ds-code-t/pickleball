# Dom Composite asks

General-purpose engine requests. The engine has no knowledge of Pickleball.
Pickleball gates on `capabilities` and keeps the previous path when a flag is absent.

## Landed in 2.9.0

`query --within <node-id>`
Search inside that node. The id is `frame:backend`, or a backend id that occurs once.
Absolute and parenthesized XPath stay inside the node. A missing id is `not_found`.
Query only. Log entity `query`, line `within <id>`, off unless named.

`query --nesting deepest|outermost`
After the existing filters, including `displayed`. Deepest drops a match that contains another match.
Outermost drops a match that is contained by another. One match is unchanged.
Query only. Log entity `query`, line `nesting deepest` or `nesting outermost`, off unless named.

`eval --node-ids id,id`
One call, one value per id. A single id, including `--node-id`, keeps `backend_node_id` and `value`.
Two or more return `values`. The function is caller JavaScript. The engine does not interpret it.
`capabilities.query_flags` lists `within` and `nesting`. `capabilities.eval_nodes` is present when several ids are accepted.

## Landed in 2.10.0

`query --nth <n>`
0-based index after displayed, nesting, and any caller filter. `-1` is the last match.
Out of range returns an empty node list and count 0. Not an error.

`query --every <step>`
With `--nth <start>`, keep indexes `start, start+step, ...` (0-based). Without `--nth`, start is 0.
`step` below 1 is `invalid`.

`query --count-only`
After nth and every, return `count` and an empty `nodes` array. `count_only` is true.

Order on one query: match, then filters (displayed), then nesting, then nth/every, then count-only.
These flags are query only. `capabilities.query_flags` lists `nth`, `every`, and `count-only` when present.
Log entity `query`, off unless named. Lines: `nth <n> every <step>` (either side `-` when omitted) and `count-only`.

## Landed in 2.11.0

`query --geometry`
One `geometry` object on each hit in `nodes` and `composite` that has a stored border quad.
Five spaces: `document_css_px`, `visual_viewport_relative`, `layout_viewport_relative`,
`nearest_scrollport_relative`, `frame_document_relative`. Each space has `kind` (`quad`, or
`aabb` when the stored source is `cdp_snapshot_bounds`), four `corners`, and the axis-aligned
`x`, `y`, `width`, `height`. `bounds` is unchanged. Omitted, the field is absent. A node with
no quad omits the field. `--count-only` still returns no nodes.

`nearest_scrollport` names the element scrollport (`overflow` `auto`, `scroll`, or `overlay`;
not `html` or `body`) or the frame viewport. Phase 1 origin is that scrollport's border-box
corner, or the frame scroll mapped through the frame transform.

`closed_shadow` is true when the node sits in a closed shadow root. `closed_shadow_limit`
states that page-script hit-testing retargets to the host. The corners are CDP or stored quads.
The engine does not call `elementFromPoint` to defeat that limit.

A live query enriches the matched nodes and their ancestors inside that one command.
`capabilities.query_flags` lists `geometry`. HTTP and the Java, JavaScript, and Python bindings
forward the bool on `query` only. They do not compute boxes.

Log entity `geometry`, off unless named. A `query` line `geometry` is written when the flag is set.
Trace: `id=<frame>:<backend> kind=<quad|aabb> closed_shadow=<bool>`.
Info: `query nodes=<n> missing=<n>`, `query count-only`, or `query nodes=0`.

## Landed in 2.11.1

Same `query --geometry` object. On a hit that is itself an element scrollport
(`overflow` `auto`, `scroll`, or `overlay`; not `html` or `body`), and only when
`styles.extra_json` stored `client_width` and `client_height`, the object also has
`scrollport.client` in the same five spaces. `scrollport.content` is present only when
`scroll_width` and `scroll_height` were stored. Neither box is invented from the border quad.
Derived boxes are `kind` `aabb`.

`nearest_scrollport.origin` is `client-box` when that client size is stored, otherwise
`border-box`. Relative corners subtract the client origin. A descendant does not receive a
`scrollport` object unless that descendant is itself a scrollport.

The live probe writes `scroll_left`, `scroll_top`, `client_left`, `client_top`,
`client_width`, `client_height`, `scroll_width`, and `scroll_height` into the existing
`extra_json` column. A capture that never wrote those keys omits the content box.

Log entity `geometry`. Info, when a box is present: `id=<frame>:<backend> scrollport=client`
or `scrollport=client+content`.

## Landed in 2.11.2

Same `query --geometry` object. `expanded` and `expanded_source` are present when the hit is a control:
`aria-expanded`, an accessibility `expanded` property (`ax`), or a `details` / `summary` pair (`details`).
Other nodes omit both.

`revealed` is that region in the same five spaces, and only when the control is open and a stored quad exists.
`aria-controls` names the element. An open `details` uses element children other than `summary`.
A collapsed control does not get a predicted box. A missing quad omits `revealed`.

`corners` samples one CSS pixel in from each corner of the border box, and of `scrollport.client`,
`scrollport.content`, and `revealed` when those boxes are present. `hit` is true when the `hit_grid`
cell's top node is this node or a descendant, false when the cell names something else, and null when
the sample is not known. `reason` is then `closed_shadow_hit`, `cross_origin`, or `no_hit_grid`.
A null hit is not a hit. The engine does not call page-script `elementFromPoint`.

Log entity `geometry`. Info, when the node is a control or a corner sample is known:
`id=<frame>:<backend> expanded=<true|false|none> revealed=<yes|no> corners=<n>hit/<n>miss/<n>unknown`.


