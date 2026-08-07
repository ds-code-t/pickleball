@all @regression @data-elements @data-element-native @data-element-native-collections @newphases
Feature: Native Data Element Java collection projections

  Scenario: Query Maps with key value and return attributes
    Given CLEAR SAVED VALUES

    When , save "<data:Data element native fixtures.Structured sources.mapCollection>" JSON Data as "mapsJson"

    When , save "<mapsJson>" Maps as "allMaps"
    Then , verify "<allMaps[0].id>" equals "one"
    And , verify "<allMaps[1].code>" equals "two"
    And , verify "<allMaps[2].status>" equals "complete"

    # Default Map comparison is against keys.
    When , save "<mapsJson>" Map equaling "id" as "mapByKey"
    Then , verify "<mapByKey.id>" equals "one"
    And , verify "<mapByKey.status>" equals "ready"

    # Explicit comparison attribute changes filtering but still returns the Map.
    When , save "<mapsJson>" Map with value equaling "pending" as "mapByValue"
    Then , verify "<mapByValue.code>" equals "two"
    And , verify "<mapByValue.status>" equals "pending"

    When , save key of "<mapsJson>" Map equaling "id" as "mapKeys"
    And , save values of "<mapsJson>" Map equaling "id" as "mapValues"
    And , save size of "<mapsJson>" Map equaling "id" as "mapSize"
    And , save count of "<mapsJson>" Map equaling "id" as "mapCount"
    And , save first of "<mapsJson>" Map equaling "id" as "mapFirst"
    And , save last of "<mapsJson>" Map equaling "id" as "mapLast"
    And , save type of "<mapsJson>" Map equaling "id" as "mapType"
    And , save string of "<mapsJson>" Map equaling "id" as "mapString"

    Then , verify "<mapKeys[0]>" equals "id"
    And , verify "<mapKeys[1]>" equals "status"
    And , verify "<mapValues[0]>" equals "one"
    And , verify "<mapValues[1]>" equals "ready"
    And , verify "<mapSize>" equals 2
    And , verify "<mapCount>" equals 2
    And , verify "<mapFirst>" equals "one"
    And , verify "<mapLast>" equals "ready"
    And , verify "<mapType>" equals "Map"
    And , verify "<mapString>" equals "{id=one, status=ready}"

    # Iteration candidates expose a phrase-local DataContextNodeMap.
    When , for every "<mapsJson>" Map:
    : * , save "<status>" as "lastMapStatus"
    Then , verify "<lastMapStatus>" equals "complete"

    When , save "unchanged" as "optionalMapSentinel"
    And , for any "<mapsJson>" Map equaling "does-not-exist":
    : * , save "changed" as "optionalMapSentinel"
    Then , verify "<optionalMapSentinel>" equals "unchanged"

  Scenario: Query Lists with operation-specific comparison and positional syntax
    Given CLEAR SAVED VALUES

    When , save "<data:Data element native fixtures.Structured sources.listCollection>" JSON Data as "listsJson"

    When , save "<listsJson>" Lists as "allLists"
    Then , verify "<allLists[0][0]>" equals "alpha"
    And , verify "<allLists[1][1]>" equals "tail"
    And , verify "<allLists[3][0]>" equals "delta"

    # equals/starts-with use the first member, ends-with uses the last,
    # and contains searches members.
    When , save "<listsJson>" List equaling "alpha" as "alphaList"
    And , save "<listsJson>" List ending with "tail" as "tailList"
    And , save "<listsJson>" List containing "middle" as "middleList"

    Then , verify "<alphaList[0]>" equals "alpha"
    And , verify "<alphaList[2]>" equals "omega"
    And , verify "<tailList[0]>" equals "beta"
    And , verify "<middleList[1]>" equals "middle"

    When , save first of "<listsJson>" List containing "middle" as "listFirst"
    And , save last of "<listsJson>" List containing "middle" as "listLast"
    And , save size of "<listsJson>" List with size equaling 3 as "listSize"
    And , save count of "<listsJson>" List with size equaling 3 as "listCount"
    And , save type of "<listsJson>" List containing "middle" as "listType"
    And , save string of "<listsJson>" List containing "middle" as "listString"

    Then , verify "<listFirst>" equals "alpha"
    And , verify "<listLast>" equals "omega"
    And , verify "<listSize>" equals 3
    And , verify "<listCount>" equals 3
    And , verify "<listType>" equals "List"
    And , verify "<listString>" equals "[alpha, middle, omega]"

    # every 2nd applies stride after filtering and expands only in iteration mode.
    When , for every 2nd "<listsJson>" List:
    : * , save "<value[0]>" as "lastEverySecondList"
    Then , verify "<lastEverySecondList>" equals "delta"

  Scenario: Convert a JSON object with array values to an ordered Multimap
    Given CLEAR SAVED VALUES

    When , save "<data:Data element native fixtures.Structured sources.multimapSource>" JSON Data as "multimapJson"

    When , save key of "<multimapJson>" Multimap as "multimapKeys"
    And , save values of "<multimapJson>" Multimap as "multimapValues"
    And , save size of "<multimapJson>" Multimap as "multimapSize"
    And , save count of "<multimapJson>" Multimap as "multimapCount"
    And , save first of "<multimapJson>" Multimap as "multimapFirst"
    And , save last of "<multimapJson>" Multimap as "multimapLast"
    And , save type of "<multimapJson>" Multimap as "multimapType"
    And , save string of "<multimapJson>" Multimap as "multimapString"

    Then , verify "<multimapKeys[0]>" equals "status"
    And , verify "<multimapKeys[1]>" equals "status"
    And , verify "<multimapKeys[2]>" equals "status"
    And , verify "<multimapKeys[3]>" equals "owner"
    And , verify "<multimapValues[0]>" equals "ready"
    And , verify "<multimapValues[1]>" equals "ready"
    And , verify "<multimapValues[2]>" equals "pending"
    And , verify "<multimapValues[3]>" equals "team"
    And , verify "<multimapSize>" equals 4
    And , verify "<multimapCount>" equals 4
    And , verify "<multimapFirst>" equals "ready"
    And , verify "<multimapLast>" equals "team"
    And , verify "<multimapType>" equals "Multimap"
    And , verify "<multimapString>" equals "{status=ready, status=ready, status=pending, owner=team}"

    # Filtering on one projection and returning another keeps the whole candidate.
    When , save key of "<multimapJson>" Multimap with value equaling "pending" as "keysFromValueFilter"
    Then , verify "<keysFromValueFilter[0]>" equals "status"
    And , verify "<keysFromValueFilter[3]>" equals "owner"

    # Native Gherkin currently has no producer for an actual java.util.Set.
    # A JSON array is intentionally not coerced to Set, so Set/ Sets behavior
    # remains covered by the Java API checks until Pickleball exposes a native producer.
