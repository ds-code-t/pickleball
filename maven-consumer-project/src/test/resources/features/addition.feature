Feature: Addition

  Scenario: ss2
    Then print aaa
  @[DEBUG] Then print aaa
    Given set $(CHROME)
#    * navigate to: URL.w3
#    Then , I click the "Log In" Button
    Then , from the IframeResult, I select "Opel" in the "cars" Dropdown
#    * , from the IframeResult, I enter "dd" in the "Last name:" Textbox
#    * , click "Essential QA Service" Qqq


  Scenario: b test2
    Then print aaa
  @[DEBUG] Then print aaa
#    Then SET TABLE VALUES
#      | suffix |
#      | usera  |
    Given set $(CHROME)
#    * navigate to: URL.google
#    * navigate to: URL.bad
#    Then , click "Gmail" Link, and wait 1 seconds

#    Then print aaa
#    Given get browser $(CHROME)
#      | http://google.com |
#    Then , wait 4 seconds


  Scenario: parm test 7
    * dataTable
    * string
        """
    aa
    """
    * string

    * map
      | A | bqqw  |
      | 1 | qqqw1 |

    * map


    * list
      | Alice |
      | Bob   |
      | Carol |
    * list


  Scenario: parm test 6
    * dataTable
      | A | bqqw  |
      | 1 | qqqw1 |
    * string
    """
    aa
    """
    * map
      | A | bqqw  |
      | 1 | qqqw1 |
    * list
      | Alice |
      | Bob   |
      | Carol |


  Scenario: nestTest
    * zprint printing== <A>
  : * zprint printing== <A>
  : : * zprint printing== <A>

  Scenario: table Test
    * zdatatable Adatatabela
      | A | bqqw  |
      | 1 | qqqw1 |
      | 2 | qqqw2 |

  Scenario: row Test
    * zdatatable Adatatabela
      | A | bqqw  |
      | 1 | qqqw1 |
      | 2 | qqqw2 |
    Then For every ROW in DATA TABLE
  : * print printing== <A>
#     * print printing== <B>
    * DATA TABLE
      | A | bqqw  |
      | 1 | qqqw1 |
      | 2 | qqqw2 |

  Scenario: Chrome test
    * DATA TABLE
      | A | bqqw  |
      | 1 | qqqw1 |
      | 2 | qqqw2 |

    Given I launch Chrome with options:
      | argument    | option |
      | --incognito |        |
    When I navigate to:
      | https://example.com |
    * , I click "Learn more" Link

  Scenario: tes targ
    Given xQQQ2sss2
#
    Then print Arrrrrrrrr
    Given zargt1 333

  Scenario: sStart Run component
    Then print Arrrrrrrrr
    * RUN SCENARIOS: %wtest33

  @%wtest33
  Scenario: aaa
    Then print Awwwwwwwww
    Given QQQ
    Given QQQ2ss2

  @test1 @sc1 @smoke @%fg
  Scenario Outline: conditionals2
    * IF: 1 + 1 < 0
  : Then print A
    * ELSE-IF: 1 + 1 > 0
  : Then print B<B>
    * ELSE-IF: 1 + 1 < 5
  : Then print C
    * ELSE:
  : Then print D

    @sc2
    Examples:
      | Scenario Tags | B  |
      | @t1estz3q2    | 22 |


  @%t1est3q2
  Scenario: qq
    Given I have numbers 2 and 3
    Given QQQ
    Given QQQ2ss2
#
#  @Tag1
#  Scenario: Add two numbers
#    Given QQQ
#    Given QQQ2ss2
#    Given I have numbers 2 and 3
#    When I add them
##    * thrdow error
#    Then the result should be 5
#
##
#  @Tag1
#  Scenario Outline: outline Add two numbers <A>
##    Given I have numbers <A> and 3
#    When I add them
#    Then print <A>
#    Examples:
#      | A |
#      | 2 |