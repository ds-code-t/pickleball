Feature: Data.reference.records

  Scenario: Customer.record
    * ---payload.marker
      """json
      {
        "source": "escaped-marker",
        "nested": {
          "value": 9
        }
      }
      """
    * , verify "this data scenario must not execute" equals "lookup only"

  Scenario: Escaped.selector fixture
    * , verify "before escaped marker" equals "skipped"
    * ---start.marker
    * , verify "escaped selector body" equals "escaped selector body"
