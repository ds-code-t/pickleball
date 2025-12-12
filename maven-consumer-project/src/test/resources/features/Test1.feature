Feature: Addition


  Scenario: branch test1 testqwq
  @[DEBUG]
#    * navigate to: URL.buttons
    * , from the Top Panel:
#  : * , for any Button containing "Rounder", I save  Text as "A"

  : * , for any Button containing "Rounder":
#  :: * print sss
  :: * , I save  Text as "A"
  :: * , I verify "<A>" equals "Q"

  Scenario: testqwq
  @[DEBUG]
#    Then , I select "Opel" in the "cars" Dropdown
#    Then IF: "Asdd Rdfds "   THEN: , I select "Opel" in the "cars" Dropdown
#    Then , IF: "Asdd Rdfds "   THEN: , I select "Opel" in the "cars" Dropdown, and click the Submit Button
#  * , verify "The select element" Text is displayed
#  * , verify Text containing "The select element"  is displayed
#  * , verify Text containing "The select element"  is displayed
    * , from the Top Panel:
#  : * , I click the  Link containing " own "
  : * , for any Button containing "Rounder", I save  Text as "A"
  : * , I verify "<A>" equals "Q"
  : * , I verify "<A #0>" equals "Q"
  : * , I verify "<A #1>" equals "Q"
  : * , I verify "<A #2>" equals "Q"
  : * , I verify "<A #3>" equals "Q"
  : * , I verify "<A #4>" equals "Q"
  : * , I verify "<A #5>" equals "Q"
  : * , I verify "<A #6>" equals "Q"
#  : * , for any  Link containing " own ", I save  Link containing " own " as "A"
#  : * , I save  Link containing " own " as "Q"
#  : * , I verify "<Q>" equals "Q"
#  : * , I verify "<Q>" equals "1Q"
#  : * IF: "<Opel sd>" THEN: , I verify  "Get your own website" Link matches with "^Gset.*$"

  Scenario: ss3
    Then print aaa
    Then print aaa
  @[DEBUG]

    Given set CHROME
    * navigate to: URL.select
    * , from the Top Panel:
#  : * , I click the "AAA" Link
#  : * , I click the Toggle Left Navigation button
#  : * , I verify  "Run ❯" Button not equal "AA"
#  : * , I verify  "Get your own website" Link equal "AA"
#  : * , I verify "Get your own website" Link equal "AA"
  : * , I verify  length of value of "Get your own website" Link equal "AA"
#  : * , I click the "Run ❯" Button
#  : * IF: true THEN: , I click the "Get your own website" Link
#  : *  , I verify the "Get your own website" Link is displayed
