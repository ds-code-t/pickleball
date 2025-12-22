Feature: Addition

  Scenario: ssatesss
  @[DEBUG]
#    * navigate to: URL.buttons
    * , from the Top Panel:
    : * , click the  Link containing "Previous"
#  : * ,  if Link containing "Previous" is displayed, click it
#    * , from the Top Panel:
#  : * IF: Link containing "Previous" is displayed THEN:
#  :: * ,  click the  Link containing "Previous"


  Scenario: stesss
  @[DEBUG]
#    * navigate to: URL.buttons
    * , from the Top Panel:
  : * IF: Link containing "Previosus" is displayed THEN:
  :: * ,  click the  Link containing "Previous"


#  : * , if  Link containing "Sigsn" is displayed :
#  :: * ,  click the  Link containing "Sign"

#  : * IF: Link containing "Sign" is displayed THEN:
#  :: * ,  click the  Link containing "Sign"


  Scenario: tesss
  @[DEBUG]
    * , from the Top Panel:
  : * IF: Link containing "Sigsn" is displayed THEN:  , save "Button" as "Element" ELSE-IF: Link containing "Sign" is displayed THEN: , save "Link" as "Element"
  : * ,  click the  <Element> containing "Sign"


  Scenario: test243hy
  @[DEBUG]
    * , from the Top Panel:
  : * , if Link containing "Sign" is displayed?
  :: * , save "Button" as "Element"
  :: * , save "Link" as "Element" ,  click the  <Element> containing "Sign"

#
#  : * , Button containing "Sign" is displayed?
#  :: * , I click the  Link containing "Sign"
#  : * , else click the Link containing "Certified"

#    * , from the Top Panel:
#    : * , verify Button containing "Rounder" is displayed
#    : * , if Button containing "Rounder" is displayed, click Button containing "Rounder"

  Scenario: test2334
#  * , save "AA" as "Q"
    * , save "AA" as "Q Q"
#  * , verify "<Q>" equals "AA"
    * , verify "<Q Q>" equals "AA"


  Scenario Outline: branch test1 testqwq
    @[DEBUG]
    * navigate to: URL.buttons
    * , from the Top Panel:
#  : * , for any Button containing "Rounder", I save  Text as "A"

  : * , for any Button containing "Rounder":
  :: * print a
  :: * print sss
  :: * print a
  :: * print a <A>
#  :: * , I save  Text as "A"
#  :: * , I verify "<A>" equals "Q"
    Examples:
      | A |
      | 1 |
#      | 2 |


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
