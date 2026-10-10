@all @regression @data-elements @area-c @area-data-elements
Feature: Area C data element kinds

  @area-data-table
  Scenario: Data Table singular and Data Tables plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Cucumber sources.records>" Data Table as "oneTable"
    And , in the "<oneTable>" Data Table, save first Data Row as "oneTableRow"
    Then , verify "<oneTableRow.id>" equals "r1"
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Rows as "rowsOfTables"
    Then , verify "<rowsOfTables[3].id>" equals "r4"
    When , for every "<data:Data element native fixtures.Cucumber sources.records>" Data Table:
    : * , save "seen" as "pluralTables"
    Then , verify "<pluralTables>" equals "seen"

  @area-data-row
  Scenario: Data Row singular and Data Rows plural
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data Row as "oneRow"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Rows as "manyRows"
    Then , verify "<oneRow.id>" equals "r1"
    And , verify "<manyRows[3].id>" equals "r4"

  @area-data-column
  Scenario: Data Column singular and Data Columns plural
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data Column as "oneColumn"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Columns as "manyColumns"
    Then , verify "<oneColumn.id>" equals "status"
    And , verify "<manyColumns[1].r1>" equals "pending"

  @area-data-list
  Scenario: Data List singular and Data Lists plural
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data List as "oneList"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Lists as "manyLists"
    Then , verify "<oneList[0]>" equals "id"
    And , verify "<manyLists[1][0]>" equals "r1"

  @area-data-column-list
  Scenario: Data Column List singular and Data Column Lists plural
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data Column List as "oneColumnList"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Column Lists as "manyColumnLists"
    Then , verify "<oneColumnList[0]>" equals "id"
    And , verify "<manyColumnLists[1][1]>" equals "ready"

  @area-data-cell
  Scenario: Data Cell singular and Data Cells plural
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data Cell as "oneCell"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Cells as "manyCells"
    Then , verify "<oneCell>" equals "id"
    And , verify "<manyCells[5]>" equals "r1"

  @area-data-entry
  Scenario: Data Entry singular and Data Entries plural
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data Entry as "oneEntry"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save value of Data Entries with key equaling "id" as "manyIds"
    And , save "<oneEntry>" JSON String as "oneEntryJson"
    Then , verify "<oneEntryJson>" contains '"Data Value":"r1"'
    And , verify "<manyIds[3]>" equals "r4"

  @area-data-header
  Scenario: Data Header singular and Data Headers plural
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data Header as "oneHeader"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Headers as "manyHeaders"
    Then , verify "<oneHeader>" equals "id"
    And , verify "<manyHeaders[4]>" equals "captureKey"

  @area-data-value
  Scenario: Data Value singular and Data Values plural
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data Value as "oneValue"
    And , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Values as "manyValues"
    Then , verify "<oneValue>" equals "r1"
    And , verify "<manyValues[19]>" equals "row4Seen"

  @area-data-map
  Scenario: Map singular and Maps plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "mapsJson"
    And , save "<mapsJson[]>" Map as "oneMap"
    And , save "<mapsJson[]>" Maps as "manyMaps"
    Then , verify "<oneMap.id>" equals "one"
    And , verify "<manyMaps[2].status>" equals "complete"

  @area-data-java-list
  Scenario: List singular and Lists plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.listCollection>" JSON Data as "listsJson"
    And , save "<listsJson[]>" List as "oneJavaList"
    And , save "<listsJson[]>" Lists as "manyJavaLists"
    Then , verify "<oneJavaList[0]>" equals "alpha"
    And , verify "<manyJavaLists[3][0]>" equals "delta"

  @area-data-multimap
  Scenario: Multimap singular and Multimaps plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.multimapSource>" JSON Data as "multimapJson"
    And , save "<multimapJson>" Multimap as "oneMultimap"
    And , save "<multimapJson>" Multimaps as "manyMultimaps"
    And , save first of "<oneMultimap>" Multimap as "multimapFirst"
    Then , verify "<multimapFirst>" equals "ready"
    And , verify "<manyMultimaps[0]>" contains "pending"

  @area-data-object
  Scenario: Data Object singular and Data Objects plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Data Object as "oneObject"
    And , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "objectArray"
    And , save "<objectArray[]>" Data Objects as "manyObjects"
    Then , verify "<oneObject.name>" equals "Ada"
    And , verify "<manyObjects[1].code>" equals "two"

  @area-json-data
  Scenario: JSON Data reads a document
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" JSON Data as "jsonData"
    Then , verify "<jsonData.name>" equals "Ada"
    And , verify "<jsonData.address.city>" equals "Phoenix"

  @area-yaml-data
  Scenario: YAML Data reads a document
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.yamlDocument>" YAML Data as "yamlData"
    Then , verify "<yamlData.name>" equals "Grace"
    And , verify "<yamlData.address.city>" equals "Tempe"

  @area-xml-data
  Scenario: XML Data reads a document
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.xmlDocument>" XML Data as "xmlData"
    Then , verify "<xmlData.name>" equals "Lin"
    And , verify "<xmlData.score>" equals "31"

  @area-data-string
  Scenario: Data String singular and Data Strings plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Data String as "oneDataString"
    And , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "objectArray"
    And , save "<objectArray[]>" Data Strings as "manyDataStrings"
    Then , verify "<oneDataString>" contains '"name": "Ada"'
    And , verify "<manyDataStrings[1]>" contains '"code":"two"'

  @area-json-string
  Scenario: JSON String singular and JSON Strings plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" JSON Data as "sourceJson"
    And , save "<sourceJson>" JSON String as "oneJsonString"
    And , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "objectArray"
    And , save "<objectArray[]>" JSON Strings as "manyJsonStrings"
    Then , verify "<oneJsonString>" contains '"name":"Ada"'
    And , verify "<manyJsonStrings[0]>" contains '"id":"one"'

  @area-yaml-string
  Scenario: YAML String singular and YAML Strings plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" JSON Data as "sourceJson"
    And , save "<sourceJson>" YAML String as "oneYamlString"
    And , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "objectArray"
    And , save "<objectArray[]>" YAML Strings as "manyYamlStrings"
    Then , verify "<oneYamlString>" contains "Ada"
    And , verify "<manyYamlStrings[0]>" contains "one"

  @area-xml-string
  Scenario: XML String singular and XML Strings plural
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" JSON Data as "sourceJson"
    And , save "<sourceJson>" XML String as "oneXmlString"
    And , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "objectArray"
    And , save "<objectArray[]>" XML Strings as "manyXmlStrings"
    Then , verify "~[~oneXmlString~]~" contains "<name>Ada</name>"
    And , verify "~[~manyXmlStrings[0]~]~" contains "<id>one</id>"

  @area-doc-string
  Scenario: Doc String saves the whole value
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.jsonDocument>" Doc String as "nativeDocString"
    And , save "<nativeDocString>" Structured Data as "docStringData"
    Then , verify "<docStringData.name>" equals "Ada"
    And , verify "<docStringData.address.city>" equals "Phoenix"

  @area-data-position
  Scenario: first and last select one candidate
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.listCollection>" JSON Data as "listsJson"
    And , save first "<listsJson[]>" List as "firstList"
    And , save last "<listsJson[]>" List as "lastList"
    Then , verify "<firstList[0]>" equals "alpha"
    And , verify "<lastList[0]>" equals "delta"

  @area-data-every-any
  Scenario: every and any hit and any misses without failing
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.listCollection>" JSON Data as "listsJson"
    And , for every "<listsJson[]>" List:
    : * , save "<value[0][0]>" as "everyLast"
    Then , verify "<everyLast>" equals "delta"
    When , for any "<listsJson[]>" List with first equaling "alpha":
    : * , save "<value[0][0]>" as "anyHit"
    Then , verify "<anyHit>" equals "alpha"
    When , save "unchanged" as "anyMiss"
    And , for any "<listsJson[]>" List with first equaling "missing":
    : * , save "changed" as "anyMiss"
    Then , verify "<anyMiss>" equals "unchanged"

  @area-data-every-third
  Scenario: every 3rd selects the third list
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.listCollection>" JSON Data as "listsJson"
    And , for every 3rd "<listsJson[]>" List:
    : * , save "<value[0][0]>" as "everyThird"
    Then , verify "<everyThird>" equals "gamma"

  @area-data-inline-terminal
  Scenario: an inline terminal save returns the first row
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save Data Row as "inlineRow"
    Then , verify "<inlineRow.id>" equals "r1"

  @area-data-block-iteration
  Scenario: a block iteration visits every row
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, for every Data Row:
    : * , save "<id>" as "blockLastId"
    Then , verify "<blockLastId>" equals "r4"

  @area-data-nesting
  Scenario: data element nesting covers one two and three levels
    Given CLEAR SAVED VALUES
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, save first Data Value as "nest1"
    Then , verify "<nest1>" equals "r1"
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, in the first Data Row, save first Data Value as "nest2"
    Then , verify "<nest2>" equals "r1"
    When , in the "<data:Data element native fixtures.Cucumber sources.records>" Data Table, in the first Data Row, in the first Data Entry, save last Data Value as "nest3"
    Then , verify "<nest3>" equals "r1"

  @area-data-size-count
  Scenario: size and count are the same member count
    Given CLEAR SAVED VALUES
    When , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "mapsJson"
    And , save size of "<mapsJson[]>" Maps as "mapSizes"
    And , save count of "<mapsJson[]>" Maps as "mapCounts"
    Then , verify "<mapSizes[0]>" equals 2
    And , verify "<mapCounts[0]>" equals 2
    And , verify "<mapSizes[2]>" equals 2
    And , verify "<mapCounts[2]>" equals 2
