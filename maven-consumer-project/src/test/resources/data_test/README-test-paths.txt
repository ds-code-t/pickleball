FileAndDataParsing test resources

Extract the CONTENTS of this zip into your Java resources folder.

Example calls expected to work with the updated buildJsonFromPath:

Basic file lookup ignoring suffix:
- buildJsonFromPath("A.B.c")
- buildJsonFromPath("A/B/c")
- buildJsonFromPath("A/B/c.yaml")

Nested YAML data lookup:
- buildJsonFromPath("A/B/c.PropA.propB")
  Expected: "valueB"

Nested YAML array lookup:
- buildJsonFromPath("A/B/c.PropA.propC[1]")
  Expected: "one"

Directory lookup:
- buildJsonFromPath("A/B")
  Expected: object containing c, duplicateName, DUPLICATENAME, table, plain, xmlFile, etc.

Forced directory lookup using trailing slash:
- buildJsonFromPath("SlashOnly/dirChoice/item/")
  Expected: directory contents, including nested.txt as key nested

Ambiguous file-vs-directory lookup:
- buildJsonFromPath("SlashOnly/dirChoice/item")
  Expected with recommended implementation: contents of item.yaml
- buildJsonFromPath("SlashOnly/dirChoice/item.nested.value")
  Expected: "itemFileValue"
- buildJsonFromPath("SlashOnly/dirChoice/item/")
  Expected: directory contents, not item.yaml

Case-insensitive file lookup:
- buildJsonFromPath("A/B/mixedCase/casefile")
- buildJsonFromPath("A.B.mixedCase.CASEFILE.Top.Inner")
  Expected: "FoundByCaseInsensitiveFileName"

Exact match wins before case-insensitive:
- buildJsonFromPath("A/B/duplicateName")
  Expected: duplicateName.yaml
- buildJsonFromPath("A/B/duplicatename")
  Since no exact base-name match exists for lowercase duplicatename, case-insensitive lookup may select the first stable sorted candidate.

JSON nested lookup:
- buildJsonFromPath("Alpha/Beta/Gamma/items.items[1].name")
  Expected: "banana"

YML nested lookup:
- buildJsonFromPath("Alpha/Beta/Gamma/settings.app.flags.mode")
  Expected: "demo"

CSV lookup:
- buildJsonFromPath("CsvTests/people")
  Expected: array of row objects
- buildJsonFromPath("CsvTests/people[1].name")
  Expected: "Flydash"

XML lookup:
- buildJsonFromPath("XmlTests/pet")
  Expected: parsed XML tree
- buildJsonFromPath("XmlTests/pet.name")
  Expected may depend on Jackson XML tree shape, usually "Brownie"

TXT lookup:
- buildJsonFromPath("TxtTests/message")
  Expected: text node "Hello from a nested TXT resource."
