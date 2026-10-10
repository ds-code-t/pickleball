@all @regression @data @mapping @top-level-collection
Feature: A top-level index reads that name's collection

  Scenario: Scalar saves, table rows, and a flattened list stay addressable
    Given CLEAR SAVED VALUES
    # Scalars
    * , save "v1" as "a"
    * , save "v2" as "a"
    * , save "v3" as "a"
    * , verify "<a>" equals "v3"
    * , verify "<a #1>" equals "v1"
    * , verify "<a[0]>" equals "v1"
    * , verify "<a #2>" equals "v2"
    * , verify "<a[1]>" equals "v2"
    * , verify "<a #last>" equals "v3"
    # Table rows
    * ------
      | id | status |
      | r1 | ready  |
      | r2 | done   |
    * , in the Data Table, save the Data Rows as "o"
    * , verify "<o #2.status>" equals "done"
    * , verify "<o[1].status>" equals "done"
    * , verify "<o.status>" equals "done"
    # Mixed (tested, not documented)
    * , save "v1" as "m"
    * , in the Data Table, save the Data Rows as "m"
    * , save "v2" as "m"
    * , verify "<m #1>" equals "v1"
    * , verify "<m #2.id>" equals "r1"
    * , verify "<m #3.status>" equals "done"
    * , verify "<m>" equals "v2"
    # Out of range: <a #9> behaves like any missing reference (no crash).
    * , save "safe" as "outOfRange"
    * IF: <a #9> THEN: , save "hit" as "outOfRange"
    * , verify "<outOfRange>" equals "safe"
    # Empty list adds nothing. A list of lists flattens one level.
    * , save "sentinel" as "kept"
    * MAP "kept" OBJECT VALUE TO RUN MAP
      """json
      []
      """
    * , verify "<kept>" equals "sentinel"
    * MAP "y" OBJECT VALUE TO RUN MAP
      """json
      ["start", ["a", "b"], ["c", "d"], "end"]
      """
    * , verify "<y #2 #2>" equals "b"
    * , verify "<y>" equals "end"
    * , save "<a[]>" JSON String as "aWhole"
    * , verify "<aWhole>" equals "[\"v1\",\"v2\",\"v3\"]"
    # Explicit name[] appends the list as one item.
    * MAP "boxed[]" OBJECT VALUE TO RUN MAP
      """json
      ["whole"]
      """
    * MAP "boxed[]" OBJECT VALUE TO RUN MAP
      """json
      ["other"]
      """
    * , verify "<boxed[0][0]>" equals "whole"
    * , verify "<boxed[1][0]>" equals "other"
    # ~merge; of two lists concatenates into the collection.
    * MAP "merged" OBJECT VALUE TO RUN MAP
      """json
      [1, 2]
      """
    * MAP "merged~merge;" OBJECT VALUE TO RUN MAP
      """json
      [3, 4]
      """
    * , verify "<merged[0]>" equals "1"
    * , verify "<merged[1]>" equals "2"
    * , verify "<merged[2]>" equals "3"
    * , verify "<merged[3]>" equals "4"
    * , verify "<merged>" equals "4"
    # A non-top-level save of a list is not flattened.
    * , save "holder" as "parent.name"
    * MAP "parent.nested" OBJECT VALUE TO RUN MAP
      """json
      ["x", "y"]
      """
    * , verify "<parent.nested[0]>" equals "x"
    * , verify "<parent.nested[1]>" equals "y"
    * , verify "<parent.name>" equals "holder"
    # Range, filter, and an underscore singleton.
    * , save "<a #1-2>" JSON String as "aRange"
    * , verify "<aRange>" equals "[\"v1\",\"v2\"]"
    * , verify "<o[status=\"done\"].id>" equals "r2"
    * , save "one" as "_solo"
    * , save "two" as "_solo"
    * , verify "<_solo>" equals "two"
    # A Data Rows loop still reads the last saved row.
    * , in the "<o[]>" Data Table, for every Data Row:
    : * , save "<o.id>" as "oDuringLoop"
    : * , save "<id>" as "loopId"
    * , verify "<oDuringLoop>" equals "r2"
    * , verify "<loopId>" equals "r2"
    * , verify "<o>" contains "r2"
    # A nested path through a saved row.
    * MAP "orders" OBJECT VALUE TO RUN MAP
      """json
      [
        {"id": "r1", "items": [{"sku": "A"}]},
        {"id": "r2", "items": [{"sku": "B"}]}
      ]
      """
    * , verify "<orders #2.items #1.sku>" equals "B"
    * , verify "<orders>" contains "r2"
    And CLEAR SAVED VALUES

  Scenario: A component step keeps a flattened top-level save on the caller map
    Given CLEAR SAVED VALUES
    * RUN COMPONENT SCENARIO: Component saves a flattened row
      | pkb_componentpath           |
      | src/test/resources/features |
    * , verify "<componentScalar>" equals "from-component"
    * , verify "<componentRows #1.id>" equals "c1"
    * , verify "<componentRows.status>" equals "ready"
    And CLEAR SAVED VALUES

  Scenario: Component saves a flattened row
    * , save "from-component" as "componentScalar"
    * ------
      | id    | status |
      | c1    | ready  |
    * , in the Data Table, save the Data Rows as "componentRows"
