Feature: Additiontest


  Scenario: test rr
    * , verify "A" equals "A", or "A" equal "C"



  Scenario: tt34fsdf
#  @[DEBUG]
    * navigate to: URL.alert
    * , wait 1 second
#    * , from the FrameResult, verify "Tsry it" Button is displayed
#    * , from the FrameResult, verify "Try it" Button is displayed
    * , from the FrameResult, click "Try it" Button
#    * justwait
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#    * IF: "false":
#  : * , save  "Try it"  as "A"
#  : * , save  "Try it" Button as "A"
#  : * , save  "Try it" Button as "A"
#  : * , save  "Try it" Button as "A"
#  : * , wait 3 seconds
#  : * , save  "Try it" Button as "A"
#  : * , save  "Try it" Button as "A"
#  : * , wait 3 seconds
#  : * , verify  "Try it" Button is displayed
#    * , from the FrameResult, click any unchecked Checkboxes
#    * , from the FrameResult, verify the "I have a bike" Text is displayed
#    * , from the FrameResult, click the "I have a bike" Checkbox


  @Testw1
  Scenario Outline: qqtestloselect
#    @[DEBUG]
    * navigate to: URL.select
    Then , from the FrameResult, I select "Opel" in the "cars" Dropdown
    * , from the Top Panel:
  : * , I verify  "Get your own website" Link matches "^Get.*$"

    Examples:
      | s |
      | 1 |
      | 2 |
      | 3 |

  Scenario: tefsdf
  @[DEBUG]
    * logTest
    * Scenario Log: "test1"
    * Scenario Log: "test2"
      | key1 | S |
      | val1 | W |
    * logTest
    * Scenario Log: "test3"
      | key1 | S  |
      | val1 | W  |
      | val2 | W2 |

  Scenario: chckboxtest 2
#  @[DEBUG]
    * IF: "true":
  :  * ,save "Z" as "z"
    * ELSE:
  :  * ,save "X" as "x"
#    Then , I select "Opel" in the "cars" Dropdown
#    Then , I select 'opel' in the "cars" Dropdown
#    * IF: 'opel' THEN: , I select it in the "cars" Dropdown
#    Then , I select Option with a value containing "a" and with a value containing "d" in the "cars" Dropdown

#    * , switch to New Window
#    * , click any checked Checkbox

  Scenario Outline: test22
    * Set report values
      | xg | A   | B   | C   | D   | x   | y   | z   |
      |    | <A> | <B> | <C> | <D> | <A> | <A> | <A> |

    * ,verify "<A>1" equals "1"

    * Set report values
      | Q | r   |
      | z | 222 |

    Examples:
      | Q | A | B | C | D |
      |   | a | b |   |   |
      |   | a | b |   |   |
      |   |   | c | d |   |
      |   |   | c | d | e |


  Scenario: click test
  @[DEBUG]
    * , from the Top Panel:
  : * , click "aaa" Link, ensure  "Get your own website" Link is displayed
  : * , ensure  "Get your own website" Link is displayed
  : * , ensure  "Get your own website" Link is displayed

  Scenario Outline: statu test 1
    @[DEBUG]
    * , from the Top Panel:
  : * , ensure  "<linkText>" Link is displayed

    When RUN IF SCENARIO FAILED
  : * Set report values
    | A | status |
    | 1 | FAIL   |

    When RUN IF SCENARIO PASSING
  : * Set report values
    | A | status |
    | 2 | PASS   |

    Examples:
      | linkText             |
      | Get your own website |
      | zz                   |


  Scenario: test
  @[DEBUG]
    * , from the Iframe:
  : * , verify "This page is displayed in an iframe" Text is displayed

  Scenario: attach 4tt
    * , from the Top Panel:
  : * , attach "test.xlsx"


  Scenario: attach 3
    * , from the Top Panel:
  : * , attach "test.xlsx"

  Scenario: attach 3b
  @[DEBUG]
    * , from the Top Panel:
  : * , attach "test.xlsx"


  Scenario: attach test2
  @[DEBUG]
#    * , from FrameResult:
    * , in FrameResult , create and attach "eexcszdftest2.xlsx"

  Scenario Outline: so test2
    * , from the Top Panel:
  : * , click Open Menu, and Save Code
    Examples:
      | Scenario |
      | 1        |
      | 2        |

  Scenario Outline: test222
    @[DEBUG]
    * , from the Top Panel:
  : * IF: "<link1>":
  :: * , click Open Menu
  : * IF: "<link2>":
  :: * , click Save Code

    Examples:
      | Scenario | link1     | link2 |
      | 1        | Open Menu |       |
#      | 2        |           | Save Code |

#    : * , ensure Open Menu is displayed
#    : * , if Open Menu is displayed, click it


  Scenario: gene test2
  @[DEBUG]
    * , from the Top Panel:
#    : * , click "Get your own website" Link
  : * , if "Get your own website" Link is displayed, click it
#    : * IF: "Get your own website" Link is displayed THEN: , click it

  Scenario: save test
    * , save 'Azzz2A' as "B"
    * , save "<B>" as "C"


  Scenario Outline: sssgssatesssssf
#    * navigate to: URL.select
    @[DEBUG]
    * , from the Top Panel:
  : * IF: "<Opel sd>" THEN: , I verify  "Get your own website" Link matches "^Gxet.*$"
  : * , I verify  "Get your own website" Link equals  "Get your own websiteq"
#  : * , enter "zz" in the "Name" Textbox
#  : * IF: <Work Group> THEN: , enter "AA" in the "Name" Textbox
#  : * IF: "<Work Group>" THEN: , enter "BB" in the "Name" Textbox

    Examples:
      | Work Group     |
      | ASddfg Redfsdf |


  Scenario: gssatesssssf
  @[DEBUG]
    * , from the Top Panel:
  : * , enter "AA" in the "Name" Textbox


  Scenario: ssatesss
  @[DEBUG]
    * , ensure  "Get your own website" Link is displayed
#    * navigate to: URL.buttons
    * , from the Top Panel:
  : * Set report values
    | xg    | s   | f   | d  | e | f     |
    | 54345 | 222 | 333 | 44 | 5 | 66666 |

  : * Set "Rep3" report values in "sht2" sheet
    | a   | b   | cwwww | d  | e | f     |
    | 111 | 222 | 333   | 44 | 5 | 66666 |


  : * Set report values
    | xg2     | s2    | f2    | d2   | e2  | f2      |
    | zz54345 | zz222 | zz333 | zz44 | zz5 | zz66666 |

  : * Set "Rep3" report values in "sht2" sheet
    | a   | b   | c   | d  | e | f     |
    | 111 | 222 | 333 | 44 | 5 | 66666 |

#  : * , if Link containing "Previous" is displayed, click it
#    : * , if Link containing "Previous" is displayed, click the  Link containing "Previous"
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
