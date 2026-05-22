Feature: nested Feature File


  Scenario: dfsfd
    Then , I save "1" as "Var"
    Then , until "<Var>" is not equal to "1":
    : * , I wait 1 second, then I save "Test" as "Var"

  Scenario: scenario 2
    * , verify if "Bananas" Checkbox is selected
#    * , verify if 2nd Checkbox is selected
#    * , verify if 3rd Checkbox is selected


  Scenario: ssda
    Given DateTime:05/02/2026 format: MM/dd/yyyy

  Scenario: test23
  * , save 3 as "ABcasdsdf_NdsfsOfOsdfsd"
    * IF: 3 <= q <ABcasdsdf_NdsfsOfOsdfsd>:
    : * , save "A"
    * , if 3 <= <ABcasdsdf_NdsfsOfOsdfsd>:
   : * , save "A"


    Scenario: untilt test 5sds

#      * , verify 'ZZZZ' Text is "zzzz"
#      * , verify 'ZZZZ' Text is not "zzzz"

#      * , verify "zzzz" Text is displayed
#      * , verify "zzzz" Text is not displayed
#
#      * , verify "zzzz" Text is "zzzz"
#      * , verify "zzzz" Text not equals "zzzz"
#      * , verify "zzzz" Text not is "zzzz"
#      * , verify "zzzz" Text is not "zzzz"

      * , verify "zzxxzz" Text is "zzzz"
      * , verify "zzxxzz" Text is not "zzzz"


#      * , verify 'ZZZZ' Text is displayed
#      * , verify 'ZZZZ' Text is not displayed
#
#      * , verify "zzzz" Text is displayed
#      * , verify "zzzz" Text is not displayed

#      * , until "ZZzZZ" Text is not displayed:
#      : * , save "bbbb"
#
#      * , verify "ZZZZ" Text is displayed
#      * , verify "ZZZZ" Text is not displayed
#
#      * , until "ZZZZ" Text is not displayed:
#    : * , save "aaaaa"


  Scenario: textdfs bdebug test
    * , save "A"
    * , save "B"
  @[DEBUG]
    * , save "C"
    * , save "D"


  Scenario: textdfs bws

    * , from the Top Panel:
  : * , verify "First Value:" Textbox is displayed
  : * , verify "wFirst Value:" Textbox is displayed
  : * , verify "First Value:" Textbox is not displayed
  : * , verify "wFirst Value:" Textbox is not displayed

#    : * , verify "wFirst Value:" Textbox is "A"
#    : * , verify value of "wFirst Value:" Textbox is "A"


#    : * , verify "First Value:" Textbox is displayed
#    : * , verify "Second Value:" Textbox is not displayed
#    : * , verify "Third Value:" Textbox is not displayed
#
#    : * , verify "First Value:" Textbox is displayed
#    : * , verify "Second Value:" Textbox is not displayed
#    : * , verify "Third Value:" Textbox is not displayed




  Scenario: click testsdas 344
  @[DEBUG]
    * , from the Top Panel:
  : * , verify "Apples" Checkbox is displayed
  : * , verify "Apples" Checkbox is not displayed
  : * , verify "Applesss" Checkbox is displayed
  : * , verify "Applesss" Checkbox is not displayed
#  : * , click "aaa" Link, ensure  "Get your own website" Link is displayed
#  : * , ensure  "Get your own website" Link is displayed
#  : * , ensure  "Get your own website" Link is displayed

  Scenario: fgdfereg
    * , ,if 1==3, then save "A" , else if 1==1:
  : * , save "QQ"

  Scenario: fgdfg
    * , save 1 as "n"
    * , if <n>==1  , then  save "A" , else if <n>==2   , then  save "B"  , else if <n>==3    , then save "C" , else save "D"
    * , save 2 as "n"
    * , if <n>==1  , then  save "A" , else if <n>==2   , then  save "B"  , else if <n>==3    , then save "C" , else save "D"


  Scenario: tezstfgsds inline conditionals
    * , save 1 as "n"
    * IF: <n>==1 THEN: , save "A" ELSE-IF: <n>==2   THEN: , save "B"   ELSE-IF: <n>==3   THEN:  , save "C"  ELSE: , save "D"
    * , save 2 as "n"
    * IF: <n>==12 THEN: , save "A" ELSE-IF: <n>==2   THEN: , save "B"   ELSE-IF: <n>==3   THEN:  , save "C"  ELSE: , save "D"
    * , save 3 as "n"
    * IF: <n>==13 THEN: , save "A" ELSE-IF: <n>==2   THEN: , save "B"   ELSE-IF: <n>==3   THEN:  , save "C"  ELSE: , save "D"
    * , save 4 as "n"
    * IF: <n>==14 THEN: , save "A" ELSE-IF: <n>==2   THEN: , save "B"   ELSE-IF: <n>==3   THEN:  , save "C"  ELSE: , save "D"

  Scenario: ss tezstfgsds inline conditionals
    * , save 1 as "n"
    * IF: <n>==1 THEN: , save "A" ELSE-IF: <n>==2   THEN: , save "B"   ELSE-IF: <n>==3   THEN:  , save "C"  ELSE: ,  if 1==6:"
  : * , save "Q111"
    * , save 2 as "n"
    * IF: <n>==1 THEN: , save "A" ELSE-IF: <n>==2   THEN: , save "B"   ELSE-IF: <n>==3   THEN:  , save "C"  ELSE: ,  if 1==6:"
  : * , save "Q222"
    * , save 3 as "n"
    * IF: <n>==1 THEN: , save "A" ELSE-IF: <n>==2   THEN: , save "B"   ELSE-IF: <n>==3   THEN:  , save "C"  ELSE: , if 1==6:"
  : * , save "Q333"
    * , save 77 as "n"
    * IF: <n>==1 THEN: , save "A" ELSE-IF: <n>==2   THEN: , save "B"   ELSE-IF: <n>==3   THEN:  , save "C"  ELSE: , if 1==1:"
  : * , save "Q444"

  Scenario: tezsts
    * , save "<A>" as  "B"
    * , if "<A>" has value , save "AAA"

  Scenario: inline IF A
    * , save "A" as "z"
    * , if "<z>" == "A" , save "22"
    * IF: "<z>" == "A" THEN: , save "22"
    * IF: "<z>" == "A" THEN: , print "22"

  Scenario: inline IF B
    * , save "A" as "z"
    * , if "<z>" is equal to "A"  , save "11"
#    * IF: "<z>" is equal to "A" THEN:   , save "11"
    * IF: "<z>" is equal to "A" THEN:   tprint "11"

  Scenario: cod1
    * , if "AA"=="AA":
  : * print "AAAAA"
    * , else:
  : * print "BBBBB"

  Scenario: cod
    * IF: "AA"=="AsA":
  : * print "AAAAA"
    * ELSE:
  : * print "BBBBB"

  Scenario: ffdsfdfgrt4
    * , verify "WWQ3" Text, and  "QWERGJH" Text are not displayed

  Scenario: test save
    * , save "A111" as "Data Table"
    * , save "B111" as "Data Table"
    * , save "<Data Table>"
    * , save "<Data Table #1>"
    * , save "<Data Table #2>"


  Scenario Outline: Data table set test
    * IF: 4 == <Z>:
    : * test1 <any>
    : * RUN SCENARIOS
      | Run Tags |
      | %compT3  |
    * IF: 4 < <X>:
  : * RUN SCENARIOS
    | Run Tags |
    | %compT3  |

    Then , in the "Name Change" Data Table, for every Data Row:
  : * , save "<A>"
    * IF: "A" is "A":
  : * , save "22"
    : * RUN SCENARIOS
      | Run Tags |
      | %compT2  |

    Examples:
      | Z | X |
      | 5 | 5 |
      | 4 | 4 |


  Scenario Outline:
    * SET "Name Change" DATA TABLE
      | A |
      | 1 |
      |   |
      | 3 |

    * SET "eee" DATA TABLE
      | A |
      | 1 |
      | 2 |
      | 3 |


    Examples:
      | Scenario Tags |
      | %compT2       |


  Scenario: data  on topsasd
    * , save "not equals" as "A"
    * , verify "1" <A> 2
    * , verify 1 not equals 2
#    * , verify "XDFRSD" Text <A> displayed
#    * , verify "XDFRSD" Text is not displayed


  Scenario: data  on top
    * , in "YYY" Data Table, for every "y1" Data Row:
  :  * , for the every Textbox,   save "<B>" as "Z"  ,  save value of Textbox as "Z"
#  :  * , for the every Textbox:
#  :: * ,  verify "<B>"  equals value of Textbox
#  :: * ,  save "<B>" as "Z"
#  :: * ,  save value of Textbox as "Z"

#    :   * , save "<A>-<B>" as "WW"
    * "YYY" DATA TABLE
      | A  | B    | C  |
      | y1 | a2q  | a3 |
      | y2 | b2-1 | b3 |
      | y2 | b2-2 | b3 |
      | y2 | b2-3 | b3 |
      | y2 | b2-4 | b3 |
      | y1 | b2   | b3 |
      | y3 | b2   | b3 |

  Scenario:  table on top
    * , for the every Textbox:
  :  * ,  in "YYY" Data Table, for every "y1" Data Row ,   save "<B>" as "Z"  ,  save value of Textbox as "Z"
#  :   * , in "YYY" Data Table, for every "y1" Data Row:
#  :: * ,  verify "<B>"  equals value of Textbox
#  :: * ,  save "<B>" as "Z"
#  :: * ,  save value of Textbox as "Z"

#    :   * , save "<A>-<B>" as "WW"
    * "YYY" DATA TABLE
      | A  | B    | C  |
      | y1 | a2q  | a3 |
      | y2 | b2-1 | b3 |
      | y2 | b2-2 | b3 |
      | y2 | b2-3 | b3 |
      | y2 | b2-4 | b3 |
      | y1 | b2   | b3 |
      | y3 | b2   | b3 |

  Scenario: data context test 345454
    * , in "YYY" Data Table, for every "y1z" Data Row:
  : * , for the every Textbox:
  ::  * , if "<A>" == "y1z":
  :::  * , save "<B>" as "Z"
  :::  * ,  enter "<A><B><C>" in it


#    :   * , save "<A>-<B>" as "WW"
    * "YYY" DATA TABLE
      | A   | B    | C  |
      | y1z | a2q  | a3 |
      | y2  | b2-1 | b3 |
      | y2  | b2-2 | b3 |
      | y2  | b2-3 | b3 |
      | y2  | b2-4 | b3 |
      | y1  | b2   | b3 |
      | y3  | b2   | b3 |

  Scenario: data cont5
#    * , verify 2nd "zzzz" Text is displayed

#  * , click the "Bananas" Checkbox
#  * , click the 3rd Checkbox

    * , after the 3rd Checkbox ,  click the 2nd "Bananas" Checkbox
#    * , after the 3rd Checkbox ,  click the "Bananas" Checkbox

  Scenario: data context test 5
#    @[DEBUG,##]
    * , after the 2nd "zzzz" Text:
  :  * ,  click the "Bananas" Checkbox
#  :  * , enter "rr"
  :  * , in "YYY" Data Table, for every "y1z" Data Row:
  ::  * ,  click the 2nd "Bananas" Checkbox
#  ::  * , if "<A>" == "y1z":
#  :::  * , save "<B>" as "Z"
#  :::  * ,  enter "<A><B><C>"
    * "YYY" DATA TABLE
      | A   | B    | C  |
      | y1z | a2q  | a3 |
      | y2  | b2-1 | b3 |
      | y2  | b2-2 | b3 |
      | y2  | b2-3 | b3 |
      | y2  | b2-4 | b3 |
      | y1  | b2   | b3 |
      | y3  | b2   | b3 |


  Scenario: data context test 34
#    * , for every Textbox, enter "AA"
#    * , for every Checkbox, click it
#    * , if Checkbox is displayed , click it
    * , for the every Textbox, enter "AA"


  Scenario: data context test 3b
#    @[DEBUG,##]
    * , for the every Textbox:
  :  * ,  enter "bb" in it
##  :  * , enter "rr"
#  :  * , in "YYY" Data Table, for every "y1z" Data Row:
#  ::  * , if "<A>" == "y1z":
#  :::  * , save "<B>" as "Z"
#  :::  * ,  enter "<A><B><C>"


  Scenario: data context test 3
#    @[DEBUG,##]
    * , for the every Textbox:
#  :  * , enter "rr"
  :  * , in "YYY" Data Table, for every "y1z" Data Row:
  ::  * , if "<A>" == "y1z":
  :::  * , save "<B>" as "Z"
  :::  * ,  enter "<A><B><C>" in it


#    :   * , save "<A>-<B>" as "WW"
    * "YYY" DATA TABLE
      | A   | B    | C  |
      | y1z | a2q  | a3 |
      | y2  | b2-1 | b3 |
      | y2  | b2-2 | b3 |
      | y2  | b2-3 | b3 |
      | y2  | b2-4 | b3 |
      | y1  | b2   | b3 |
      | y3  | b2   | b3 |


  Scenario: data context test 2
    * , after "BBBB" Text:

  :  * , in "YYY" Data Table, for every "y1z" Data Row:
  ::  * , if "<A>" == "y1z":
  ::  * , save "<B>" as "Z"
  ::  * ,  enter "<A><B><C>" in the Textbox
#  ::  * ,  click the "Bananas" Checkbox


#    :   * , save "<A>-<B>" as "WW"
    * "YYY" DATA TABLE
      | A   | B    | C  |
      | y1z | a2q  | a3 |
      | y2  | b2-1 | b3 |
      | y2  | b2-2 | b3 |
      | y2  | b2-3 | b3 |
      | y2  | b2-4 | b3 |
      | y1  | b2   | b3 |
      | y3  | b2   | b3 |


  Scenario: data context test 1

#    * , in "YYY" Data Table,  for every "B" Data Cell:



    * , in "YYY" Data Table, for every "y2" Data Row:
  :  * , if "<A>" == "y2":
  : * , save "<B>" as "Z"
#    :   * , save "<A>-<B>" as "WW"
    * "YYY" DATA TABLE
      | A  | B    | C  |
      | y1 | a2   | a3 |
      | y2 | b2-1 | b3 |
      | y2 | b2-2 | b3 |
      | y2 | b2-3 | b3 |
      | y2 | b2-4 | b3 |
      | y1 | b2   | b3 |
      | y3 | b2   | b3 |


  Scenario: short test 1


# Test 1: Wrap two When-Else Statements in an outer When
    When ,  1 == 1:
  : When , 1 ==2 :
  : : Then , print "Inner 1, If"
  : When , else:
  : : Then , print "Inner 1, Else"


  Scenario: test conditionasd 3

# Setup
    Then , I save "T" as "Outer"
    Then , I save "F" as "Inner1"
    Then , I save "F" as "Inner2"

# Test 1: Wrap two When-Else Statements in an outer When
    When , "<Outer>" is equal to "T":
  : When , "<Inner1>" is equal to "T":
  : : Then , print "Inner 1, If"
  : When , else:
  : : Then , print "Inner 1, Else"
  : When , if "<Inner2>" is equal to "T":
  : : Then , print "Inner 2, If"
  : When , else:
  : : Then , print "Inner 2, Else"

# Test 2: Two When-Else statements back to back
    When , "<Inner1>" is equal to "T":
  : Then , print "Inner 1, If"
    When , else:
  : Then , print "Inner 1, Else"
    When , "<Inner2>" is equal to "T":
  : Then , print "Inner 2, If"
    When , else:
  : Then , print "Inner 2, Else"

# Test 3: Wrap two IF-ELSE Chained Conditionals in an outer When
    When , "<Outer>" is equal to "T":
  : * IF: "<Inner1>" is equal to "T":
  : : Then , print "Inner 1, If"
  : * ELSE:
  : : Then , print "Inner 1, Else"
  : * IF: "<Inner2>" is equal to "T":
  : : Then , print "Inner 2, If"
  : * ELSE:
  : : Then , print "Inner 2, Else"

  Scenario: test logallsteps`
#    * IF: "A" == "B":
#     * , save "!"

    *  , 1 ==1:
  : *  , 1 ==3:
  ::   * , save "a1"
    *  , else if 1 ==1:
  : *  , else if 1 ==1:
  ::   * , save "a2"

  Scenario: Render different Entry level and status combinations

    Given ProfileTest1 Compact Scenario PASS INFO
    And ProfileTest2 Spacious Scenario FAIL ERROR
    And ProfileTest3 Rounded Step WARN WARN
    And ProfileTest4 Sharp Step PASS DEBUG
    And ProfileTest5 Elevated Phrase INFO TRACE
    And ProfileTest6 Flat Phrase SKIP INFO
    And ProfileTest7 Bold Title Screenshot FAIL ERROR
    And ProfileTest8 Wide Title Screenshot WARN WARN
    And ProfileTest9 Uppercase Title Scenario UNKNOWN INFO
    And ProfileTest10 Monospace Title Step PASS DEBUG
    And ProfileTest11 Center Title Phrase INFO TRACE
    And ProfileTest12 Muted Visual Scenario WARN WARN
    And ProfileTest13 Rounded Elevated Bold Title Step FAIL ERROR
    And ProfileTest14 Compact Monospace Wide Title Phrase UNKNOWN DEBUG


    Given EntryTest1 INFO PASS
    And EntryTest2 INFO FAIL
    And EntryTest3 INFO WARN
    And EntryTest4 INFO SKIP
    And EntryTest5 INFO UNKNOWN
    And EntryTest6 ERROR FAIL
    And EntryTest7 ERROR PASS
    And EntryTest8 ERROR WARN
    And EntryTest9 WARN WARN
    And EntryTest10 WARN PASS
    And EntryTest11 WARN FAIL
    And EntryTest12 DEBUG PASS
    And EntryTest13 DEBUG FAIL
    And EntryTest14 DEBUG UNKNOWN
    And EntryTest15 TRACE PASS
    And EntryTest16 TRACE SKIP
    And EntryTest17 TRACE UNKNOWN

    Then test1 nested test


  Scenario Outline:  Conditionals:
    * print "<A>"
#    When IF: 3 <= <A>:
#    : * print "<A>"

    Examples:
      | A |
      | 2 |
      |   |
      | 4 |

  Scenario:  calling SCENARIO a sdfsdfd
#    * test2 A

    * , save "bbb" as "TableName"
    * RUN SCENARIOS
      | Run Tags   | A           | B      |
      | %Comp_MsMs | <TableName> | tablea |

#    * FOR EVERY DATA ROW IN THE "<TableName>" DATA TABLE:
    * , in the "<TableName>" Data Table, for every Data Row:
  : * , save "<C1>  <C2>  <C3>"


  Scenario Outline: called component scenario 1 <A>

    * IF: "<A>" is "ssss":
  : * SET "<A>" DATA TABLE
    | C1       | C2       | C3       |
    | A-row1c1 | A-row1c2 | A-row1c3 |
    | A-row2c1 | A-row2c2 | A-row2c3 |
    | A-row3c1 | A-row3c2 | A-row3c3 |

    * ELSE-IF: "<A>" is "bbb":
  : * SET "<A>" DATA TABLE
    | C1       | C2       | C3       |
    | B-row1c1 | B-row1c2 | B-row1c3 |
    | B-row2c1 | B-row2c2 | B-row2c3 |
    | B-row3c1 | B-row3c2 | B-row3c3 |


    Examples:
      | Scenario Tags | bdf |
      | %Comp_MsMs    | zz  |


  Scenario: eq14

    * , verify "1" less than 1
    * , verify "1" less than "1"
    * , verify "1" less than or equal 1
    * , verify 1 less than or equal "1"
    * , verify 1 less than or equal "1.0"
    * , verify "1" less than or equal 1.0
    * , verify "1" less than 1.0
    * , verify 1 less than "1.0"
    * , verify "1" greater than 1.0
    * , verify 1 greater than "1.0"

    * , verify "1" greater than or equal 1.0
    * , verify 1 greater than or equal "1.0"

    * , verify "1.1" less than 1.0
    * , verify 1.1 less than "1.0"

    * , verify "1.1" less than 1
    * , verify 1.1 less than "1"

    * , verify "1" equals 1.0
    * , verify 1 equals "1.0"

    * , verify "1" equals "1.0"

  Scenario: eq check 12
    * , verify 1 less than 1
    * , verify 1 less than or equal 1
    * , verify 1 less than or equal 1.0
    * , verify 1 less than 1.0
    * , verify "1" less than 1.0
#    * , verify {1 == "1"}
#    * , verify `1` == `1.0`
    * , verify 1 greater than 1.0
    * , verify 1 greater than or equal 1.0
    * , verify 1.1 less than 1.0
    * , verify 1.1 less than 1
    * , verify "1" equals 1.0
    * , verify "1" equals 1.0
    * , verify "1" equals "1.0"


  Scenario: click testsdas
  @[DEBUG]
    * , click the "Apples" Checkbox, the "Bananas" Checkbox, and the "Strawberries" Checkbox
    * , click the "Apples" Checkbox
    * , save "AA" as "x"
    * print :::parse { "<x>" == "AA"  }
    * , click the "Bananas" Checkbox
    * IF: "<Test>" has no value THEN: , print "!="

    * , save "Test" as "x"
    * IF: "<Test>" has no value THEN: , print "!="







#  Scenario Outline: called component scenario 1
##    * , save "Aq" as "x"
#    * print <Run Tags>
#    * print { "<Run Tags>" == "%Comp_MsMs" }
#    * IF: "<Run Tags>" == "%Comp_MsMs" THEN: , save "A" ELSE:  , save "ZZ"
#    * , if "<Run Tags>" == "%Comp_MsMs" , save "B"
#
#    * IF: "<Run Tags>" == "%Comp_MsMs":
#  :  * , save "C"
#    * , if "<Run Tags>" == "%Comp_MsMs":
#  :  * , save "D"
#
#    Examples:
#      | Scenario Tags |
#      | %Comp_MsMs    |




  Scenario:  eq test6
#    * , if "<Test>" has no value , print "!="
#    * , save "x" as "Test"
#    * , if "<Test>" has no value  , print "!="
    * , if "<Test>" has value  , print "!="
    * , save "Test" as "x"
    * , if "<Test>" has value  , print "!="


  Scenario:  eq test5
    * , if "<Test>" has no value , save "1!="
    * , if "<Test>" has value , save "2!="
    * IF: "<Test>" has no value THEN:  , save "3!="
    * "<Test>" has  value THEN:  , save "!4="
    * , save "x" as "Test"

    * , if "<Test>" has no value , save "5!="
    * , if "<Test>" has  value , save "6!="
    * "<Test>" has no value THEN: , save "7!="
    * "<Test>" has value THEN: , save "8!="








#    * , if "<SS>":
#    : * , save "A"

#    * test2 %aztag99
#  * RUN SCENARIOS: %aztag99



  Scenario Outline: aaacalled scenario11 a= '<A>' , c= '<C>'
    * Scenario Log: exlog1 a= '<A>' , c= '<C>'
    * Scenario Log: exlog2 a= '<A>' , c= '<C>'
    Examples:
      | Scenario Tags | A   | C   |
      | %aztag99      | xxx | zzz |


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

  Scenario: adfsdfdsf
  @[DEBUG]
#    * , if "Example Domain" Window is displayed, save "A" as "v"
    * "Examplse Domain" Window is displayed THEN: , save "A1" as "v"  ELSE:  , save "C1" as "v"
#    * "Example Domain" Window is displayed THEN: , save "A1" as "v" , ELSE-IF: "Google" Window is displayed THEN: , save "B1" as "v"
#    * "Example Domain" Window is displayed THEN: , save "A1" as "v" , ELSE-IF: "Google" Window is displayed THEN: , save "B1" as "v" ELSE:  , save "C1" as "v"
#    * "Google" Window is displayed THEN: , save "A2" as "v" , ELSE-IF: "Example Domain" Window is displayed THEN: , save "B2" as "v" ELSE:  , save "C2" as "v"
#    * "aa" Window is displayed THEN: , save "A3" as "v" , ELSE-IF: "Examplaae Domain" Window is displayed THEN: , save "B3" as "v" ELSE:  , save "C3" as "v"
#    * , if "Google" Window is displayed, save "A" as "v"

  Scenario: test dd match1
    * , in the "/files/items.yaml" Data , for every Data Entry:
  : * , save "<a>" as "B"
#    * , save "<files/items.yaml>" as "B"
#    * , save "</files/items.yaml>" as "B"
#    * , in the "files/items.yaml" Data Value, save "<a>" as "B"
#  @[DEBUG]
#  * test2


#  @[DEBUG,##MatchNode]
  Scenario: test match1
  @[DEBUG,##MatchNode]
    * RUN SCENARIOS
      | @test4 |
#    * , I verify  "Get your own website" Link matches "^Gxet.*$
    * ,  verify "2343242" matches "\d+"
    * ,  verify "\d+"  matches "2343242"
    * ,  verify "a" equal "a"

  @test4
  Scenario: aaasdsave2
#  @[DEBUG]
    * DateTime:now
    * zcapitalize: ss
    * , save "<$DateTime:now>"  as "A"
    * , save "<$now>"  as "A"
    Given , save "<$DateTime:today>" as "A"
    Given , save "<$DateTime:tomorrow>" as "A"
    Given , save "<$DateTime:yesterday>" as "A"

    Given , save "<$today>" as "A"
    Given , save "<$tomorrow>" as "A"
    Given , save "<$yesterday>" as "A"
    Given , save "<$now>" as "A"

#    * , save "<$capitalizeeee>"  as "A"
#    * , save "{1+1}"  as "A"

  Scenario: stttdfsdgf
  @[DEBUG,##MatchNode]
    * , verify "The input element" Text is displayed
    * , save "123" as "A"
    * , save "The input element" Text as "A"
#    * , then click the Link
#    * , then click the 3rd "Create" Link
#    * , enter "123" in 1st "First name:" Textbox
#    * , enter "456" in 2nd Textbox
#    * , enter "123" in  "First  name:" Textbox, press "TAB" , and enter "456"
#    * , enter "123" in  "First  name:" Textbox
#    * , verify "First  name:" Textbox is displayed
#    * navigate to: URL.textbox
#    * , from the Top Panel:
#  : * , verify "Your name:" Textbox is displayed

  Scenario: ttt
  @[DEBUG,##]
    * , from the Top Panel:
  : * , wait on Loading
  : * , wait on Textbox
  : * , wait on Loading
  : * , wait on Textbox
  : * , wait on Loading
  : * , wait on Textbox
#    * , save "a" as "A"
##    * , if "<A>" equals "a", save "b" as "b"
##    * , if "<A>" equals "aa", save "b" as "b"
##    * , until "<A>" equals "aaaa" , wait 1 seconds ,  save "a<A>" as "A"
#    * , until "<A>" equals "aaaa":
#  : * , wait 1 seconds ,  save "a<A>" as "A"

  @zdzzz22
  Scenario: sente test1
#  @[DEBUG,##]
    * navigate to: URL.textbox
#    * , from the Top Panel:
#  : * "aa" THEN: , click "Get your own website" Link

#    : * , click the "Run ❯" Button, and the "Get your website" Link, and wait 2 seconds
#    * , switch Window
#    * , switch Window
#    * , switch Window
#    * , save "b" as "B"
#    * , save "b" as "B".
#    * , save "a" as "A". save "b" as "B"


  Scenario: until test1
    * , save "a" as "A"
#    * , if "<A>", wait 1 second
#    * , until "<A>" equals "aaa", save "<A>a" as "A" , wait 1 second
    * , until "<A>" equals "aaaaa":
  :  * , save "<A>a" as "A" , wait 1 second
    * , if "<A>" equals "a":
  :  * , save "<A>a" as "A" , wait 1 second


  Scenario: dfsdfgh4
  @[DEBUG,nosbase,##Specificity,##xscore,##textXpath,##pseudotags,##nosrmalizexpaths]
    * , from the Top Panel:
  :  * , enter `SHIFT[a b c]`  in  "Account Number" Textbox
  :  * , enter `CONTROL + SHIFT + LEFT`  in  "Account Number" Textbox
#    :  * , enter `CONTROL + SHIFT[RIGHT]`  in  "Account Number" Textbox
#    :  * , enter `CONTROL + SHIFT[T]`
#    :  * , enter `SHIFT[a] SHIFT[A] SHIFT[7]`  in  "Account Number" Textbox
#    :  * , verify "Account Number" Text is displayed


  Scenario: dfdfdddww
#    Then , save "Q1" as "W"
#  @[DEBUG,noBase,##Specificity,##xscore,##textXpath,##]
  @[DEBUG,nobase,##Specificity,##xscore,##textXpath,##pseudotags,##nosrmalizexpaths,##]
    * , from the Top Panel:
  : * , I verify  "Get your website" Link is displayed. I verify  "Get your website" Link is displayed
#    : * , click the "Get your website" Link
  : * IF: the "Get your website" Link is displayed:
  :: * , click the "Get your website" Link



#   * , verify the "cars" Dropdown is displayed
#   * IF: the "cars" Dropdown is displayed:
#  : * , select the last Option from the  "cars" Dropdown

    #    * , from the Top Panel:
#    : * , verify the "cars" Dropdown is displayed
#    : * IF: the "cars" Dropdown is displayed:
#    :: * , select the last Option from the  "cars" Dropdown

#    * IF: "First name:" Textbox is displayed:
#    : * , enter "aa" in the "First name:" Textbox
#    : * , enter "zz" in the "Last name:" Textbox

#    * , from the Top Panel:
#  :   * , enter "aa" in the "First name:" Textbox
#  :   * , enter "zz" in the "Last name:" Textbox
#    * , verify the "cars" Menu is displayed
#    * , verify the "cars" Menu is displayed
#    * ,  verify the "cars" Dropdown is displayed
#    * ,  verify the Option is displayed
#    * , in the "cars" Dropdown, verify the Option is displayed
#    * , verify "Birth month:" Dropdown is displayed
#    * , in the "Birth month:" Dropdown, verify the last Option is displayed

#    * , select the last Option "cars" Dropdown
#    * , select the last Option "Choose a car:" Dropdown

#    * , verify  "First name:" Textbox is displayed
#    * , verify  "First name:" Text is displayed
#    * , verify  Textbox is displayed
#    * , verify "Attachments" Section is displayed
#    * , clear  "First name:" Textboxes





  Scenario: dfdf
    Then , save "Q1" as "W"
  @[DEBUG,noBsase,##Specificity,##xscore,##parsing,##parsedata,##processContextList,##]
#    * navigate to: URL.textbox
    * , in the "Attachments" Section:
  : * , Then user clicks the "Show more" Button
#    : * , clear any  "First name:" Textboxes
#    * , overwrite any  Textboxes with "z"

  @rrr1
  Scenario: ddfsdfdsf
    * IF: "z" equals "A":
  :    Then , save "Q1" as "W"
    * ELSE-IF: "B" equals "A":
  :    Then , save "Q2" as "W"
    * ELSE-IF: "C" equals "A":
  :    Then , save "Q3" as "W"
    * ELSE:
  :    Then , save "Q4" as "W"


    * IF: "A" equals "A" THEN: , save "Azzz" ELSE:
  :    Then , save "Q1" as "W"
    * ELSE-IF: "A" equals "A":
  :    Then , save "Q2" as "W"
    * ELSE-IF: "A" equals "A":
  :    Then , save "Q3" as "W"
    * ELSE:
  :    Then , save "Q4" as "W"

  @TestQ
  Scenario: tqqqq
    * , verify "A" equals "A", or "A" equal "C"

  Scenario: ddtt34fsdf
  @[DEBUG]
#    * navigate to: URL.alert
#    * , wait 1 second
#    * , from the FrameResult, verify "Tsry it" Button is displayed
#    * , from the FrameResult, verify "Try it" Button is displayed
    * , from the FrameResult, click "Try it" Button
    * IF: Alert text is displayed:
  :    Then , save Alert text  as "alert text", and dismiss the Alert
    * ELSE:
  :    Then , click "Cancel" Button


  Scenario: tt34fsdf
  @[DEBUG]
#    * navigate to: URL.alert
#    * , wait 1 second
#    * , from the FrameResult, verify "Tsry it" Button is displayed
#    * , from the FrameResult, verify "Try it" Button is displayed
    * , from the FrameResult, click "Try it" Button
    * IF: Alert text is displayed:
  :    Then , save Alert text  as "alert text", and dismiss the Alert
    * ELSE-IF: "Cancel" Button is displayed:
  :    Then , click "Cancel" Button
    * ELSE-IF: "Cancel" Button is displayed:
  :    * , verify "F" equals "AA"
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
    @[DEBUG]
#    * navigate to: URL.select
#    Then , from the FrameResult, I select "Opel" in the "cars" Dropdown, and 'volvo'  in the "cars" Dropdown
    Then , from the FrameResult, I select last Option in the "cars" Dropdown
#    Then , from the FrameResult, I select last Option in the "cars" Dropdown, and first Option in the "cars" Dropdown
#    * , from the Top Panel:
#  : * , I verify "Run ❯" Textbox is not displayed
#  : * , I verify  "Get your website" Link is displayed

    Examples:
      | s |
      | 1 |
#      | 2 |
#      | 3 |

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
#    * navigate to: URL.buttons
    * , switch to the 'Checkboxes' Window
  :  * , verify the "Apples" Checkbox is displayed, and verify the "Apples" Checkbox is displayed
#    :  * , verify the "Apples" Checkbox is displayed, and click it
#    :  * , if the "Apples" Checkbox is displayed, and click it



#    * , from the Top Panel:
#    : * , click the "Apples" Checkbox, the "Bananas" Checkbox, and the "Strawberries" Checkbox
#    * , click the "Spaces" Link, the "Teacher" Link
#    : * , click "Get your own website" Link
#  : * , if "Get your own website" Link is displayed
#  : * , if "Get your own website" Link is displayed, click it
#  : * , if "Sign In" Button is displayed, click it
#    : * IF: "Get your own website" Link is displayed THEN: , click it

  Scenario: save testsd
  @[DEBUG]
    * , from the Top Panel:
#  : * IF: "" THEN: , I verify  "Get your own website" Link matches "^Get.*$"
  : * , if "" , I verify  1 is 1:
  :: * , if "true" , I verify  2 is 2
  : * , if "" , I verify  "Get your own website" Link matches "^Get.*$"
  : * , I verify  "Get your own website" Link equals  "Get your own website"

  Scenario Outline: sssgssatesssssf

    @[DEBUG]
    * navigate to: URL.select
    * , from the Top Panel:
  : * IF: "<Opel sd>" THEN: , I verify  "Get your own website" Link matches "^Gxet.*$"
  : * , I verify  "Get your own website" Link equals  "Get your own websiteq"
#  : * , enter "zz" in the "Name" Textbox
#  : * IF: <Work Group> THEN: , enter "AA" in the "Name" Textbox
#  : * IF: "<Work Group>" THEN: , enter "BB" in the "Name" Textbox

    Examples:
      | Work Group     |
      | ASddfg Redfsdf |


  Scenario: gssatesssssfs
  @[DEBUG]
#    * navigate to: URL.select
#    * , from the Top Panel:
#  : * , enter "AA" in the "Choose a car:" Textbox
    * , enter "AA" in the "Choose a car:" Textbox


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
#  @[DEBUG,##SpecificityScore,##Xscore]
  @[DEBUG,##ContextWrapper,nobase,##processContextList,##pseudotags]
#    Then , I select "Opel" in the "cars" Dropdown
#    Then IF: "Asdd Rdfds "   THEN: , I select "Opel" in the "cars" Dropdown
#    Then , IF: "Asdd Rdfds "   THEN: , I select "Opel" in the "cars" Dropdown, and click the Submit Button
#  * , verify "The select element" Text is displayed
#  * , verify Text containing "The select element"  is displayed
#  * , verify Text containing "The select element"  is displayed
    * , from the Top Panel:
#  : * , I click the  Link containing " own "
  : * , I save  Text as "A"
  : * , verify "<A>" equals "B"
#  : * , for any Button containing "Rounder", I save  Text as "A"
#  : * , I verify "<A>" equals "Q"
#  : * , I verify "<A #0>" equals "Q"
#  : * , I verify "<A #1>" equals "Q"
#  : * , I verify "<A #2>" equals "Q"
#  : * , I verify "<A #3>" equals "Q"
#  : * , I verify "<A #4>" equals "Q"
#  : * , I verify "<A #5>" equals "Q"
#  : * , I verify "<A #6>" equals "Q"
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
