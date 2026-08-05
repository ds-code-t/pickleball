@all @regression @data @mapping @type-preservation @data-elements
Feature: MappingSteps value type preservation
  Scenario: mapDocString values retain their types when resaved by mapValues
    Given CLEAR SAVED VALUES
    And MAP "SOURCE_OBJECT" OBJECT VALUE
      """json
      {
        "profile": {
          "name": "Ada",
          "age": 37,
          "active": true,
          "settings": {
            "theme": "dark",
            "retryCount": 3
          }
        },
        "tags": ["alpha", "beta"]
      }
      """
    And MAP "SOURCE_TEXT" TEXT VALUE
      """
      literal-text-value
      """
    Then RUN MAP PATH "SOURCE_OBJECT" HAS PRESERVED TYPE "ObjectNode"
    And RUN MAP PATH "SOURCE_OBJECT.profile.settings" HAS PRESERVED TYPE "ObjectNode"
    And RUN MAP PATH "SOURCE_OBJECT.tags" HAS PRESERVED TYPE "ArrayNode"
    And RUN MAP PATH "SOURCE_OBJECT.profile.name" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "SOURCE_OBJECT.profile.age" HAS PRESERVED TYPE "Integer"
    And RUN MAP PATH "SOURCE_OBJECT.profile.active" HAS PRESERVED TYPE "Boolean"
    And RUN MAP PATH "SOURCE_TEXT" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "SOURCE_TEXT" HAS VALUE "literal-text-value"
    And RUN MAP QUERY "SOURCE_OBJECT.tags[]" RETURNS TYPE "ArrayNode"
    And RUN MAP QUERY "SOURCE_OBJECT.tags" RETURNS TYPE "ArrayNode"
    When MAP "COPIES" TABLE VALUES
      | wholeObject   | <SOURCE_OBJECT>                   |
      | nestedObject  | <SOURCE_OBJECT.profile.settings> |
      | arrayValue    | <SOURCE_OBJECT.tags[]>           |
      | textValue     | <SOURCE_TEXT>                     |
      | nestedString  | <SOURCE_OBJECT.profile.name>     |
      | nestedInteger | <SOURCE_OBJECT.profile.age>      |
      | nestedBoolean | <SOURCE_OBJECT.profile.active>   |
    Then RUN MAP PATH "COPIES.wholeObject" HAS PRESERVED TYPE "ObjectNode"
    And RUN MAP PATH "COPIES.nestedObject" HAS PRESERVED TYPE "ObjectNode"
    And RUN MAP PATH "COPIES.arrayValue" HAS PRESERVED TYPE "ArrayNode"
    And RUN MAP PATH "COPIES.textValue" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "COPIES.nestedString" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "COPIES.nestedInteger" HAS PRESERVED TYPE "Integer"
    And RUN MAP PATH "COPIES.nestedBoolean" HAS PRESERVED TYPE "Boolean"
    And RUN MAP QUERY "COPIES.arrayValue[]" RETURNS TYPE "ArrayNode"
    And RUN MAP QUERY "COPIES.arrayValue" RETURNS TYPE "ArrayNode"
    And RUN MAP PATH "COPIES.textValue" HAS VALUE "literal-text-value"
    And RUN MAP PATH "COPIES.nestedString" HAS VALUE "Ada"
    And RUN MAP PATH "COPIES.nestedInteger" HAS VALUE "37"
    And RUN MAP PATH "COPIES.nestedBoolean" HAS VALUE "true"
    And RUN MAP PATH "COPIES.wholeObject.profile.name" HAS VALUE "Ada"
    And RUN MAP PATH "COPIES.nestedObject.theme" HAS VALUE "dark"
    And RUN MAP PATH "COPIES.nestedObject.retryCount" HAS PRESERVED TYPE "Integer"
    When MAP "SECOND_COPIES" TABLE VALUES
      | wholeObject   | <COPIES.wholeObject>   |
      | nestedObject  | <COPIES.nestedObject>  |
      | arrayValue    | <COPIES.arrayValue[]>  |
      | textValue     | <COPIES.textValue>     |
      | nestedString  | <COPIES.nestedString>  |
      | nestedInteger | <COPIES.nestedInteger> |
      | nestedBoolean | <COPIES.nestedBoolean> |
    Then RUN MAP PATH "SECOND_COPIES.wholeObject" HAS PRESERVED TYPE "ObjectNode"
    And RUN MAP PATH "SECOND_COPIES.nestedObject" HAS PRESERVED TYPE "ObjectNode"
    And RUN MAP PATH "SECOND_COPIES.arrayValue" HAS PRESERVED TYPE "ArrayNode"
    And RUN MAP PATH "SECOND_COPIES.textValue" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "SECOND_COPIES.nestedString" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "SECOND_COPIES.nestedInteger" HAS PRESERVED TYPE "Integer"
    And RUN MAP PATH "SECOND_COPIES.nestedBoolean" HAS PRESERVED TYPE "Boolean"
    And RUN MAP QUERY "SECOND_COPIES.arrayValue[]" RETURNS TYPE "ArrayNode"
    And RUN MAP QUERY "SECOND_COPIES.arrayValue" RETURNS TYPE "ArrayNode"
    And RUN MAP PATH "SECOND_COPIES.wholeObject.profile.age" HAS VALUE "37"
    And RUN MAP PATH "SECOND_COPIES.nestedObject.theme" HAS VALUE "dark"

  Scenario: mapValues preserves an ObjectNode returned by a dynamic step
    Given CLEAR SAVED VALUES
    When MAP TABLE VALUES
      | DYNAMIC_OBJECT | <$RETURN TYPE TEST OBJECT NODE> |
    Then RUN MAP PATH "DYNAMIC_OBJECT" HAS PRESERVED TYPE "ObjectNode"
    And RUN MAP PATH "DYNAMIC_OBJECT.kind" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "DYNAMIC_OBJECT.kind" HAS VALUE "dynamic-object"
    And RUN MAP PATH "DYNAMIC_OBJECT.nested" HAS PRESERVED TYPE "ObjectNode"
    And RUN MAP PATH "DYNAMIC_OBJECT.nested.count" HAS PRESERVED TYPE "Integer"
    And RUN MAP PATH "DYNAMIC_OBJECT.nested.count" HAS VALUE "7"
    And RUN MAP PATH "DYNAMIC_OBJECT.nested.active" HAS PRESERVED TYPE "Boolean"
    And RUN MAP PATH "DYNAMIC_OBJECT.nested.active" HAS VALUE "true"

  @data-table @data-row
  Scenario: a native DataTable loops with a different Data Row context for every iteration
    Given CLEAR SAVED VALUES
    And SET "NATIVE_TABLE" DATA TABLE
      | captureKey  | rowName | actual | expected | summary      |
      | nativeFirst | first   | alpha  | alpha    | first:alpha  |
      | nativeLast  | last    | beta   | beta     | last:beta    |
    Then RUN MAP QUERY "NATIVE_TABLE" RETURNS TYPE "DataTable"
    When , in the "<NATIVE_TABLE>" Data Table, for every Data Row:
    : * , ensure "<actual>" equals "<expected>"
    : * , ensure "<rowName>:<actual>" equals "<summary>"
    : * , save "<actual>" as "<captureKey>"
    Then RUN MAP PATH "nativeFirst" HAS VALUE "alpha"
    And RUN MAP PATH "nativeLast" HAS VALUE "beta"
    When , save "NATIVE_TABLE" Data as "NATIVE_TABLE_DATA"
    Then RUN MAP QUERY "NATIVE_TABLE_DATA" RETURNS TYPE "ArrayNode"
    And RUN MAP PATH "NATIVE_TABLE_DATA[0].rowName" HAS VALUE "first"
    And RUN MAP PATH "NATIVE_TABLE_DATA[0].actual" HAS VALUE "alpha"
    And RUN MAP PATH "NATIVE_TABLE_DATA[1].rowName" HAS VALUE "last"
    And RUN MAP PATH "NATIVE_TABLE_DATA[1].actual" HAS VALUE "beta"

  @data-table @data-row @json-conversion
  Scenario: an ArrayNode converts to a DataTable and nested JSON cells remain compact text
    Given CLEAR SAVED VALUES
    And MAP "JSON_ROWS" OBJECT VALUE
      """json
      [
        {
          "captureKey": "jsonObjectCell",
          "rowName": "object row",
          "actual": "Ada",
          "expected": "Ada",
          "details": {
            "active": true,
            "meta": {
              "score": 2
            }
          },
          "expectedDetails": "{\"active\":true,\"meta\":{\"score\":2}}"
        },
        {
          "captureKey": "jsonArrayCell",
          "rowName": "array row",
          "actual": "Grace",
          "expected": "Grace",
          "details": [
            "compiler",
            2,
            {
              "stable": true
            }
          ],
          "expectedDetails": "[\"compiler\",2,{\"stable\":true}]"
        }
      ]
      """
    Then RUN MAP QUERY "JSON_ROWS" RETURNS TYPE "ArrayNode"
    When , save "JSON_ROWS" Data as "JSON_ROWS_DATA"
    Then RUN MAP QUERY "JSON_ROWS_DATA" RETURNS TYPE "ArrayNode"
    When , save "JSON_ROWS_DATA" Data Table as "JSON_ROWS_TABLE"
    Then RUN MAP QUERY "JSON_ROWS_TABLE" RETURNS TYPE "DataTable"
    When , in the "<JSON_ROWS_TABLE>" Data Table, for every Data Row:
    : * , ensure "<actual>" equals "<expected>"
    : * , ensure "<details>" equals "<expectedDetails>"
    : * , ensure "<rowName>:<actual>" contains "<actual>"
    : * , save "<details>" as "<captureKey>"
    Then RUN MAP PATH "jsonObjectCell" HAS TEXT VALUE
      """text
      {"active":true,"meta":{"score":2}}
      """
    And RUN MAP PATH "jsonArrayCell" HAS TEXT VALUE
      """text
      ["compiler",2,{"stable":true}]
      """

  @json-composition @json-conversion
  Scenario: separate JsonNode containers compose a nested object as values and JSON strings
    Given CLEAR SAVED VALUES
    And MAP "PROFILE" OBJECT VALUE
      """json
      {
        "name": "Ada",
        "attributes": {
          "active": true,
          "level": 3
        }
      }
      """
    And MAP "ADDRESS" OBJECT VALUE
      """json
      {
        "city": "Phoenix",
        "coordinates": {
          "lat": 33.4484,
          "lon": -112.074
        }
      }
      """
    And MAP "TAGS" OBJECT VALUE
      """json
      ["mapping", "json", "data"]
      """
    And MAP "ORDERS" OBJECT VALUE
      """json
      [
        {
          "id": "A-100",
          "items": [
            {
              "sku": "P-1",
              "qty": 2
            }
          ]
        },
        {
          "id": "A-200",
          "items": []
        }
      ]
      """
    And MAP "MATRIX" OBJECT VALUE
      """json
      [[1, 2], [3, 4]]
      """
    When , save "PROFILE" Data as "SAVED_PROFILE"
    And , save "ADDRESS" Data as "SAVED_ADDRESS"
    And , save "TAGS" Data as "SAVED_TAGS"
    And , save "ORDERS" Data as "SAVED_ORDERS"
    And , save "MATRIX" Data as "SAVED_MATRIX"
    Then RUN MAP QUERY "SAVED_PROFILE" RETURNS TYPE "ObjectNode"
    And RUN MAP QUERY "SAVED_ADDRESS" RETURNS TYPE "ObjectNode"
    And RUN MAP QUERY "SAVED_TAGS" RETURNS TYPE "ArrayNode"
    And RUN MAP QUERY "SAVED_ORDERS" RETURNS TYPE "ArrayNode"
    And RUN MAP QUERY "SAVED_MATRIX" RETURNS TYPE "ArrayNode"
    When MAP "PROFILE_JSON_TEXT" TEXT VALUE
      """text
      <SAVED_PROFILE>
      """
    And , save "<SAVED_TAGS>" as "TAGS_JSON_TEXT"
    Then RUN MAP PATH "PROFILE_JSON_TEXT" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "TAGS_JSON_TEXT" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "PROFILE_JSON_TEXT" HAS TEXT VALUE
      """text
      {"name":"Ada","attributes":{"active":true,"level":3}}
      """
    And RUN MAP PATH "TAGS_JSON_TEXT" HAS TEXT VALUE
      """text
      ["mapping","json","data"]
      """
    When MAP "COMPOSITE" OBJECT VALUE
      """json
      {
        "customer": {
          "profile": "<SAVED_PROFILE~unquote>",
          "address": "<SAVED_ADDRESS~unquote>"
        },
        "collections": {
          "tags": "<SAVED_TAGS~unquote>",
          "orders": "<SAVED_ORDERS~unquote>",
          "matrix": "<SAVED_MATRIX~unquote>"
        },
        "serialized": {
          "profile": "<SAVED_PROFILE>",
          "tags": "<SAVED_TAGS>",
          "orders": "<SAVED_ORDERS>",
          "matrix": "<SAVED_MATRIX>",
          "profileViaSavedText": "<PROFILE_JSON_TEXT>",
          "tagsViaSavedText": "<TAGS_JSON_TEXT>"
        },
        "messages": {
          "profile": "profile=<SAVED_PROFILE>",
          "orders": "orders=<SAVED_ORDERS>"
        }
      }
      """
    Then RUN MAP QUERY "COMPOSITE" RETURNS TYPE "ObjectNode"
    And RUN MAP QUERY "COMPOSITE.customer.profile" RETURNS TYPE "ObjectNode"
    And RUN MAP QUERY "COMPOSITE.customer.address" RETURNS TYPE "ObjectNode"
    And RUN MAP QUERY "COMPOSITE.collections.tags" RETURNS TYPE "ArrayNode"
    And RUN MAP QUERY "COMPOSITE.collections.orders" RETURNS TYPE "ArrayNode"
    And RUN MAP QUERY "COMPOSITE.collections.matrix" RETURNS TYPE "ArrayNode"
    And RUN MAP PATH "COMPOSITE.customer.profile.name" HAS VALUE "Ada"
    And RUN MAP PATH "COMPOSITE.customer.profile.attributes.level" HAS VALUE "3"
    And RUN MAP PATH "COMPOSITE.customer.address.city" HAS VALUE "Phoenix"
    And RUN MAP QUERY "COMPOSITE.collections.orders[0].items" RETURNS TYPE "ArrayNode"
    And RUN MAP PATH "COMPOSITE.collections.orders[0].items[0].sku" HAS VALUE "P-1"
    And RUN MAP PATH "COMPOSITE.serialized.profile" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "COMPOSITE.serialized.tags" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "COMPOSITE.serialized.orders" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "COMPOSITE.serialized.matrix" HAS PRESERVED TYPE "String"
    And RUN MAP PATH "COMPOSITE.serialized.profile" HAS TEXT VALUE
      """text
      {"name":"Ada","attributes":{"active":true,"level":3}}
      """
    And RUN MAP PATH "COMPOSITE.serialized.tags" HAS TEXT VALUE
      """text
      ["mapping","json","data"]
      """
    And RUN MAP PATH "COMPOSITE.serialized.orders" HAS TEXT VALUE
      """text
      [{"id":"A-100","items":[{"sku":"P-1","qty":2}]},{"id":"A-200","items":[]}]
      """
    And RUN MAP PATH "COMPOSITE.serialized.matrix" HAS TEXT VALUE
      """text
      [[1,2],[3,4]]
      """
    And RUN MAP PATH "COMPOSITE.serialized.profileViaSavedText" HAS TEXT VALUE
      """text
      {"name":"Ada","attributes":{"active":true,"level":3}}
      """
    And RUN MAP PATH "COMPOSITE.serialized.tagsViaSavedText" HAS TEXT VALUE
      """text
      ["mapping","json","data"]
      """
    And RUN MAP PATH "COMPOSITE.messages.profile" HAS TEXT VALUE
      """text
      profile={"name":"Ada","attributes":{"active":true,"level":3}}
      """
    And RUN MAP PATH "COMPOSITE.messages.orders" HAS TEXT VALUE
      """text
      orders=[{"id":"A-100","items":[{"sku":"P-1","qty":2}]},{"id":"A-200","items":[]}]
      """
    When , save "SAVED_PROFILE" Data Table as "PROFILE_TABLE"
    Then RUN MAP QUERY "PROFILE_TABLE" RETURNS TYPE "DataTable"
    When , in the "<PROFILE_TABLE>" Data Table, for every Data Row:
    : * , ensure "<name>" equals "Ada"
    : * , ensure "<attributes>" equals "{\"active\":true,\"level\":3}"
