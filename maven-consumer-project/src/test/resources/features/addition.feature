Feature: Addition


  Scenario: debug tEst2
#    @[DEBUG]
#    * navigate to: URL.select
  @[DEBUG]
    * , select the last Option "Choose a car:" Dropdown

  @T1
  Scenario: j test1
#    * test2 JAVA_HOME
#    * test2 NVM_HOME
    * dataTableTest1 dataTableTestxx
      | a | b |
      | 1 | 2 |
    * navigate to: URL.select
    * , verify "Subscribe" Checkbox is on, and "Subscribe" Checkbox is true
#    * , verify Button is not displayed, and wait 10 seconds
    * test2 Q
#    * test2 ~INT~:3

  Scenario: data test C

#    * , in "YYY" Data Table,  for every "B" Data Cell:
#    * , in Data Table, for every Data Row:
    * FOR EVERY DATA ROW IN THE DATA TABLE:
#    : * print "cellhere"
  :   * , save "<A>-<B>" as "WW"
    * DATA TABLE
      | A  | B    | C  |
      | y1 | a2   | a3 |
      | y2 | b2-1 | b3 |
      | y2 | b2-2 | b3 |
      | y2 | b2-3 | b3 |
      | y2 | b2-4 | b3 |
      | y1 | b2   | b3 |
      | y3 | b2   | b3 |


  @logTest
  Scenario Outline: loggingTest <A>
    * , wait 1 second, save "<A>" as "B", wait 1 second, save "<A>" as "C"
    * IF: 1 equals 1 :
  : * , wait 1 second, save "<A>" as "B", wait 1 second, save "<A>" as "C"
  : * , verify <A> equals 2

    Examples:
      | A |
      | 1 |
      | 2 |
      | 3 |

  Scenario: data test C

#    * , in "YYY" Data Table,  for every "B" Data Cell:
    * , in "YYY" Data Table, for every "y2" Data Row, for every Data Cell:
#    : * print "cellhere"
  :   * , save "<A>-<B>" as "WW"
    * "YYY" DATA TABLE
      | A  | B    | C  |
      | y1 | a2   | a3 |
      | y2 | b2-1 | b3 |
      | y2 | b2-2 | b3 |
      | y2 | b2-3 | b3 |
      | y2 | b2-4 | b3 |
      | y1 | b2   | b3 |
      | y3 | b2   | b3 |

  Scenario: data test B
    * "XXX" DATA TABLE
      | A  | B  | C  |
      | x1 | a2 | a3 |
      | x2 | b2 | b3 |
      | x3 | b2 | b3 |

    * "YYY" DATA TABLE
      | A  | B  | C  |
      | y1 | a2 | a3 |
      | y2 | b2 | b3 |

    * , save "123" as "z"
    * , in the Data Table, for any Data Row:
  : * , save "123" as "z"

#   * FOR EVERY DATA ROW IN THE "ZZ" DATA TABLE:
  : * , in "YYY" Data Table, for every Data Row:
  :: * , save "<A>" as "WW"

  :  * "AAA" DATA TABLE
    | A          |
    | rrrrrrrrrr |
    | yyyyyyyyyy |

    * "BBB" DATA TABLE
      | A  | B  | C  |
      | a1 | a2 | a3 |
      | b1 | b2 | b3 |


    * "ZZZ" DATA TABLE
      | A  | B  | C  |
      | z1 | a2 | a3 |
      | z2 | b2 | b3 |

  Scenario: dfsdf
    * "B" DATA TABLE
      | A  | B  | C  |
      | a1 | a2 | a3 |
      | b1 | b2 | b3 |

  Scenario: table test a
  @[DEBUG,nobase,##]
    * , save "" as "A"
#    * , for every Table , save "<A>1" as "A"
    * , for any Dropdown , save "<A>1" as "A"
    * , for any Dropdown:
  : * , save "<A>1" as "A"


  Scenario: data test
#    * , save "123" as "Data Table"
    * , save "111" as "1", in the Data Table, for every Data Row:
  :  * , save "222" as "2",  in Data Table, for every Data Row:
#  :: * , save "<A>" as "WW"
  :: * , save "<Data Table>" as "dt"
  :: * , save "<Data Table #1>" as "dt1"
  :: * , save "<Data Table #2>" as "dt2"
  :: * , save "<Data Table_A>" as "dta"
  :: * , save "<Data Table_B>" as "dtb"

  :  * "A" DATA TABLE
    | A          |
    | rrrrrrrrrr |
    | yyyyyyyyyy |

    * "B" DATA TABLE
      | A  | B  | C  |
      | a1 | a2 | a3 |
      | b1 | b2 | b3 |
#      | c1 | c2 | c3 |

  Scenario: Scenario test cell2
  @[DEBUG,nobase,##]
    * , verify "Product" Cell equals "z1"

  Scenario: Scenario test cell
  @[DEBUG,nobase,##]
    * , verify 2nd "Id" Cell equals "z1"
    * , verify 3rd "Id" Cell equals "z1"
    * , verify 2nd "Product" Cell equals "z1"
    * , verify 3rd "Product" Cell equals "z1"
    * , verify 2nd "Outer Id" Cell equals "z1"
    * , verify 3rd "Outer Id" Cell equals "z1"
    * , verify 5th "Name" Cell equals "z1"
    * , verify last "Name" Cell equals "z1"
    * , verify 3rd "Notes" Cell equals "z1"
    * , verify 2nd "Notes" Cell equals "z1"

#    * , verify "Name" Cell equals "1"
#    * , verify 1st "Name" Cell equals "1"
#    * , verify 2nd "Name" Cell equals "1"


  @DDD
  Scenario Outline: nav tes6 <A>
    * navigate to: URL.select
    * , verify "Subscribe" Checkbox is on, and "Subscribe" Checkbox is true
#    * , wait 30 seconds
    Examples:
      | Scenario Tags | A | B | C |
      | %taga         | 1 | 5 |   |
      | %taga         | 2 | 5 |   |
      | %taga         | 3 | 5 |   |
      | %taga         | 4 | 5 |   |
#      | %taga         | 6 | 5 |   |
#      | %taga         | 7 | 5 |   |
#      | %taga         | 8 | 5 |   |
#      | %taga         | 9 | 5 |   |


  Scenario: gdfgdfss
  @[DEBUG,nobase,##]
    * , verify "Subscribe" Checkbox is on
    * , verify "Subscribe" Checkbox is true
    * , verify "Subscribe" Checkbox is selected
    * , verify "Subscribe" Checkbox is checked
    * , verify "Subscribe" Checkbox is not displayed
    * , verify "Text Input:" Textbox is not blank
    * , verify "Subscribe" Checkbox is not blank
    * , verify "Subscribe" Dropdown is not blank



#    * test2
#    * , verify "Status" Dropdown equals "A"
#    * , verify 2nd "Status" Cell equals "A"
#    * , verify "Status" Text is displayed
#    * test2

  Scenario: zzsStart Run component
  @[DEBUG]
    Then print Arrrrrrrrr
    * RUN SCENARIOS:
      | Tags  |
      | %taga |


  @par1
  Scenario Outline: outlineTest <A>
    * print <A>
    * print <B>
    Examples:
    Examples:
      | Scenario Tags | A | B | C |
      | %taga         | 2 | 5 |   |


  Scenario: trewtr

    * RU

#      | 3 | 5 | yyyy |
#      | 4  | 5 |   |
#      | 5  | 5 |   |
#      | 6  | 1 |   |
#      | 7  | 1 |   |
#      | 8  | 1 |   |
#      | 9  | 1 |   |
#      | 10 | 1 |   |
#      | 1  | 3 |   |
#      | 2  | 3 |   |
#      | 3  | 3 |   |
#      | 4  | 3 |   |
#      | 5  | 3 |   |

  Scenario: sssst5ff55
  @[DEBUG,##]
#    * navigate to: URL.select
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
    Then FOR EVERY ".*" DATA ROW IN THE ".*" DATA TABLE:
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



#    Given QQQ
#    Given QQQ2ss2

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
      | #t1estz3q2    | 22 |


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