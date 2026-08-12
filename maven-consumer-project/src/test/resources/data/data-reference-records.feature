Feature: Data reference records

  Scenario: Customer record
    * ------ --payload
      | Key1 | Key2 |
      | qq   | ww   |
      | ee   | rr   |
      | tt   | yy   |
    * ---message
      """text
      marker doc string
      """
    * , verify "this data scenario must not execute" equals "lookup only"
