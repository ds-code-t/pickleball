# data_test resource fixture

Place the `data_test` directory directly under your Java test resources folder, for example:

```text
src/test/resources/data_test
```

Example calls and expected meanings:

| Call | What it should test |
|---|---|
| `FileAndDataParsing.buildJsonFromPath("data_test/A/B/C.D.E[0].F")` | Loads `data_test/A/B/C.yaml`, wraps it as `C`, then queries `C.D.E[0].F`. |
| `FileAndDataParsing.buildJsonFromPath("data_test/A/B.C")` | Loads `data_test/A/B.yaml`, wraps it as `B`, then queries `B.C`. |
| `FileAndDataParsing.buildJsonFromPath("data_test/Simple.details.kind")` | Loads root-level `Simple.json`, suffix ignored. |
| `FileAndDataParsing.buildJsonFromPath("data_test/Root.child.list[1]")` | Loads root-level `Root.yaml`, suffix ignored. |
| `FileAndDataParsing.buildJsonFromPath("data_test/zoo.Animals.panda.diet")` | Loads the `zoo` directory, then queries into the aggregated directory JSON. |
| `FileAndDataParsing.buildJsonFromPath("data_test/library/books/Favorites.items[1].title")` | Loads a nested JSON file and queries an array item. |
| `FileAndDataParsing.buildJsonFromPath("data_test/ambiguous/Item.value")` | Tests file-over-directory and deterministic same-base-name handling. |
| `FileAndDataParsing.buildJsonFromPath("data_test/case/mixedcase.message")` | Tests case-insensitive fallback for the boundary resource. |
| `FileAndDataParsing.buildJsonFromPath("data_test/formats/settings.app.name")` | Tests `.properties` parsing. |
| `FileAndDataParsing.buildJsonFromPath("data_test/formats/labels.ui.title")` | Tests INI parsing; depending on your NodeMap tokenizer, `ui.title` may be interpreted as nested or dotted key. |
| `FileAndDataParsing.buildJsonFromPath("data_test/formats/plain")` | Tests `.txt` as a JSON text node. |
| `FileAndDataParsing.buildJsonFromPath("data_test/formats/table[0].name")` | Tests CSV parsing with headers. |
| `FileAndDataParsing.buildJsonFromPath("data_test/formats/sample.title")` | Tests XML parsing. |
| `FileAndDataParsing.buildJsonFromPath("data_test/deep/level1/level2/level3/Leaf.branch.twig.leaf")` | Tests deeper directory nesting. |

Notes:

- The boundary segment is repeated in the lookup path and query path.
- File suffixes are intentionally omitted in the calls.
- The `ambiguous` fixture contains `Item.json`, `Item.yaml`, and an `Item/` directory to test the priority rule: exact-case file first, exact-case directory second, case-insensitive file third, case-insensitive directory fourth.
