# snap-editor

A Blockly-inspired snap/drag editor with a **topology-only relationship document**. Core is a plain nestable block. Packs register type-defs that **extend a parent type id** (not live JS class inheritance). A Pickleball pack owns Gherkin as a codec.

Blocks are **smooth rectangles** until they connect. Idle bricks have no puzzle notch/tab. After a successful snap, the joined edge shows a small mating cue. Nesting grows an **open-bottom L** (hat + left rail) around the child — never a closing C/U under the nested stack.

## Run

```bash
npm install
npm run dev
```

Open the printed local URL (Vite, default `http://localhost:5173`). Canvas-only embed: `http://localhost:5173/embed.html`.

The demo pack selector switches **Core (plain blocks)**, **Typed subclasses**, and **Pickleball**. Switching rebuilds registered types and the toolbox.

```bash
npm test      # core / snap / layout / phrase / gherkin tests
npm run build # typecheck + production bundle (`index.html` + `embed.html`)
```

Drag types from the toolbox. Hover near a compatible edge or a valid head's **center** to highlight the target and see a ghost of the snap pose — the dragged block does not jump until you drop. Nesting extends the parent on the left and top; the child's bottom stays open. Snap top/bottom to stack (head only), left/right to join horizontally. **+ else if** / **+ else** add extra branch rows. Select + Delete. ⌘Z / Ctrl+Z undo.

**Failed paths (rejected, no center highlight / no connect):**

- Nesting is a **center drop** only. Top/bottom edges stack; left/right edges join horizontally.
- Nesting is allowed only on the **leftmost head** of a horizontal chain. A 2nd or 3rd block in the row cannot accept a nest, even on center.
- Vertical stack (top/bottom) is allowed only on a **horizontal-chain head**. Trailing siblings cannot stack up or down.
- A block that **already has nested children** cannot sit in a trailing horizontal slot. An empty control / If/else can connect as a regular head or trailer.

## Cluster drag

The dragged unit is the cluster rooted at the grabbed block:

- nested descendants
- the horizontal chain to its **right**
- the vertical stack **underneath**

Descendants are not dropped behind: they stay attached in the document and move with the parent on screen.

## Document schema

Canonical serialize is an **encapsulated tree**. Pixel `x,y` is derived (session-cached in the editor, omitted from canonical JSON). Instance ids are editor-session only and may be regenerated on pack import.

```json
{
  "schemaVersion": 1,
  "roots": [
    {
      "typeId": "block",
      "id": "session-uuid",
      "nest": {
        "do": [{ "typeId": "block", "id": "child-uuid" }]
      },
      "e": { "typeId": "block", "id": "right-uuid" },
      "s": { "typeId": "block", "id": "below-uuid" }
    }
  ]
}
```

| Relationship | Where it lives |
| --- | --- |
| Nesting / containment | `nest[mouth] = [child trees…]` (stack heads; `s` continues the stack) |
| Stack next | `s` (south) |
| Horizontal join | `e` (east) |
| North / west | Implied by a parent’s south / east |
| Pack fields | `data` (keyword, text, condition, …) — never inner HTML/CSS/JS |

Six interfaces on a type-def: `n` `e` `s` `w` `nest-in` `nest-out`. Permissions are allow / deny / mandatory, matching `typeId`, instance id, and/or `permissionType`. Snap uses a dual predicate (dragged interface AND stationary interface). A unilateral `override` may skip the other side’s invite, never a veto. **Mandatory is validation, not snap.**

Derived chain/stack/nest metadata (horizontal/vertical first/last/index, nest depth, descendant counts, ancestors) is computed from topology.

## Library core vs packs

`src/core` knows rectangles, ports, mouths, snap, and `registerType()` — not Gherkin.

- **Type-defs** call `registerType(registry, { type, extends, ... })`. Child defs inherit look, ports, mouths, and controls.
- A **pack** (`EditorPack`) registers types plus optional `exportText` / `importText` / `example`.
- Host maps renderer/callbacks by `typeId`.

| Pack | Types |
| --- | --- |
| Core | `block` — one nestable brick, six interfaces |
| Typed subclasses | `statement`, `stack`, `control` extending `block` |
| Pickleball | `pkb.feature`, `pkb.rule`, `pkb.background`, `pkb.scenario`, `pkb.outline`, `pkb.examples`, `pkb.step`, `pkb.phrase`, `pkb.if`, plus round-trip `pkb.tags`, `pkb.comment`, `pkb.table`, `pkb.docstring` |

## Pickleball pack

[`src/packs/pickleball/`](src/packs/pickleball/) owns the grammar. Core only sees nest and side snaps.

- Feature nests Rule / Background / Scenario / Scenario Outline; Outline nests Examples tables.
- Tag lines (`@foo @bar`) and `#` comments round-trip. DataTables and DocStrings nest under the step (or Examples) they belong to.
- A normal (non-dynamic) step is one block.
- A dynamic step is keyword + first phrase starting with `,`, then a horizontal chain. Later phrases start with one of `, ; : . ! ?`.
- Same-line `: more text` on a dynamic line is a horizontal continuation (LineData split).
- A parent that ends with `:` or `?` plus a following line with leading colons is nest. Leading colons before the keyword are nest depth.
- Phrase split/join copies Pickleball 2.1.9 LineData: QuoteParser (`'`, `"`, `` ` ``, then `'''`) then BracketMasker (`()` `{}` `[]` `<>`). Comma always splits; other delimiters split only at end of string or if followed by whitespace. Unmatched quotes are not masked. Join is concatenation; each phrase keeps its leading delimiter.
- JS **never executes** Gherkin. Canonical JSON is the lossless graph. Instance ids are not required in Gherkin export.

```ts
import { SnapEditor } from "./src";
import { pickleballPack } from "./src/packs/pickleball";

const editor = new SnapEditor(host, { pack: pickleballPack });
editor.exportText();
editor.importText(gherkin);
```

## Host a JavaFX WebView (Workbench)

Do not embed the Swing Workbench in this repo. Host the editor page and read Gherkin through the bridge.

1. Load `embed.html` (canvas only) or `/` (demo chrome) in a JavaFX `WebView`. Dev: `http://localhost:5173/embed.html`. Production: the Vite `embed.html` build output.
2. The shipped embed already uses `pickleballPack`.
3. After `SUCCEEDED`, call `window.SnapEditorHost`:

| Method | Role |
| --- | --- |
| `getDocument()` / `setDocument(doc)` | Encapsulated JSON document |
| `getText()` / `setText(text)` | Pack codec (Gherkin when Pickleball is loaded) |
| `getGherkin()` / `setGherkin(text)` | Aliases installed by the embed / demo |
| `onChange(cb)` | Document changed |
| `onSelection(cb)` | Selected node id |

```java
webEngine.load(editorUrl);
webEngine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
  if (state == Worker.State.SUCCEEDED) {
    String gherkin = (String) webEngine.executeScript("SnapEditorHost.getGherkin()");
    JSObject win = (JSObject) webEngine.executeScript("window");
    win.setMember("javaApp", javaBridge);
    webEngine.executeScript(
      "SnapEditorHost.onChange(function(doc){ javaApp.onDocument(JSON.stringify(doc)); })"
    );
  }
});
```

## Layout & snap grammar

- **Stack:** hover-preview flush on top/bottom; commit on drop. Only a **horizontal-chain head** may snap vertically. The next block sits below the **full grown height** of the parent (nest included).
- **Nest:** hover the **center** of a valid head (glow + ghost). Stack grows from a flat rect into an L: hat and left rail around the child, child's bottom open. Drag out restores the rect. Only a horizontal-chain head can accept a nest.
- **Horizontal:** left/right hover-preview. Trailers stay single-row tall and align to the head's **hat**. Nodes with nested children cannot be trailers; empty control blocks can.
- **Join cues:** unconnected bricks are smooth. After a snap, the joined edge shows a notch/tab. Unsnap removes it.
- **One drag, one winner:** center nest vs edge snaps are exclusive. Edges win on the perimeter; nest wins in the center.

## Embed API

```ts
import { SnapEditor, installHostBridge, pickleballPack } from "./src/index";

const editor = new SnapEditor(host, { pack: pickleballPack, document: pickleballPack.example?.() });
installHostBridge(editor);
editor.getDocument();
editor.setDocument(doc);
editor.exportText();
editor.importText(gherkin);
```
