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

