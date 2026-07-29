@mapping @type-preservation
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

    # The new query syntax is tested separately from raw storage type.
    And RUN MAP QUERY "SOURCE_OBJECT.tags[]" RETURNS TYPE "List"
    And RUN MAP QUERY "SOURCE_OBJECT.tags" RETURNS TYPE "String"
    And RUN MAP PATH "SOURCE_OBJECT.tags" HAS VALUE "beta"

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

    And RUN MAP QUERY "COPIES.arrayValue[]" RETURNS TYPE "List"
    And RUN MAP QUERY "COPIES.arrayValue" RETURNS TYPE "String"
    And RUN MAP PATH "COPIES.arrayValue" HAS VALUE "beta"

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

    And RUN MAP QUERY "SECOND_COPIES.arrayValue[]" RETURNS TYPE "List"
    And RUN MAP QUERY "SECOND_COPIES.arrayValue" RETURNS TYPE "String"
    And RUN MAP PATH "SECOND_COPIES.arrayValue" HAS VALUE "beta"

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
