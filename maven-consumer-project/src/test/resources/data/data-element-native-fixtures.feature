Feature: Data element native fixtures

  # This feature lives under the data path and is lookup-only.
  # The scenarios provide native Cucumber marker arguments to other features.

  Scenario: Cucumber sources
    * ---records
      | id | status   | status   | score | captureKey |
      | r1 | ready    | pending  | 10    | row1Seen   |
      | r2 | blocked  | ready    | 20    | row2Seen   |
      | r3 | ready    | complete | 30    | row3Seen   |
      | r4 | complete | archived | 40    | row4Seen   |

    * ---roundtrip
      | id | owner | active | score |
      | a1 | Ada   | true   | 11    |
      | a2 | Ben   | false  | 22    |
      | a3 | Cara  | true   | 33    |

  Scenario: Structured sources
    * ---jsonDocument
      """json
      {
        "name": "Ada",
        "active": true,
        "score": 42,
        "address": {
          "city": "Phoenix"
        },
        "roles": ["admin", "author"]
      }
      """

    * ---yamlDocument
      """yaml
      name: Grace
      active: true
      score: 27
      address:
        city: Tempe
      roles:
        - reviewer
        - editor
      """

    * ---xmlDocument
      """xml
      <person>
        <id>7</id>
        <name>Lin</name>
        <active>true</active>
        <score>31</score>
      </person>
      """

    * ---mapCollection
      """json
      [
        {
          "id": "one",
          "status": "ready"
        },
        {
          "code": "two",
          "status": "pending"
        },
        {
          "id": "three",
          "status": "complete"
        }
      ]
      """

    * ---listCollection
      """json
      [
        ["alpha", "middle", "omega"],
        ["beta", "tail"],
        ["gamma", "center", "finish"],
        ["delta", "end"]
      ]
      """

    * ---multimapSource
      """json
      {
        "status": ["ready", "ready", "pending"],
        "owner": ["team"]
      }
      """
