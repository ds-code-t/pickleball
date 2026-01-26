Feature: Addition


  @par1
  Scenario Outline: outlineTest <A>
    * , save "<A>" as "zzzzzzdd<C>"
    * navigate to: URL.select
#    * error
    * , wait <B> seconds
    * , save "<A>" as "<C>"

    Examples:
      | A | B | C    |
      | 1 | 5 | qqqq |
      | 2 | 5 | tttt |
      | 3 | 5 | yyyy |
      | 4  | 5 |   |
      | 5  | 5 |   |
      | 6  | 1 |   |
      | 7  | 1 |   |
      | 8  | 1 |   |
      | 9  | 1 |   |
      | 10 | 1 |   |
#      | 1  | 3 |   |
#      | 2  | 3 |   |
#      | 3  | 3 |   |
#      | 4  | 3 |   |
#      | 5  | 3 |   |

  Scenario: sssst5ff55
  @[DEBUG]
    * , from the Top Panel:
  : * , I click "Get your own website" Link

  Scenario: f test5ff55
  @[DEBUG]
#    * , verify "Show File-select Fields" Text is  displayed
    * , from the Top Panel:
#  : * , I verify "Run ❯" Button is displayed
  : * , I verify "Run ❯" Button is displayed
#  : * , I verify "Get your own website" Link is displayed
#  : * , I verify "Get your own website" Link is not displayed, and "Get your own website" Link is displayed
#  : * , I verify "Get your own website" Link is displayed


  Scenario:  test555

  @[DEBUG]



    * print A

    * print B


    * print C

  Scenario:  test22
    * print ddd
  @[DEBUG]
    Then END SCENARIO
    Then , from the Frame, I click the "Create" Button

  Scenario: if test
    * IF: "Merwe Fadasd - WKM Reremkdfj - Msdfdsgfh Dfgd Rg" THEN: ,  select "CheckList" from "cars" Dropdown
#    * IF: "a" THEN:   print ssss ELSE: print 44

  Scenario: ss3
    Then print aaa
    Then print aaa
    Given set CHROME
#    * , from the Top Panel:
#  : * , I click the "Get your own website" Link

#    * config
    * navigate to: URL.select
#    Then , click the "Create" Link
#    Then , from the Frame, I select "Opel" in the "cars" Dropdown
#    Then , from the IframeResult, I select "Opel" in the "cars" Dropdown
#    Then , from the IframeResult, I select "Opel" in the "cars" Dropdown, and click the Submit Button
#    Then , I select "Opel" in the "cars" Dropdown, and select "Volvo" in the "cars" Dropdown

    Then , I select "Opel" in the "cars" Dropdown, and click the Submit Button
#    * , from the IframeResult, I enter "dd" in the "Last name:" Textbox
  @[DEBUG]
    * , from the Top Panel:
  : * , I click the "Run ❯" Button
#    * , I enter "dd" in the "Last name:" Textbox
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