Feature: Addition


  Scenario: "untils "ssdf4"
    * , save "</runconfigs/profiles/user1>" Data
    * , save "</runconfigs/profiles/user1.browser>" Data
#    * , until 3==1:
#  : * , save "A"


  Scenario: untils ssdf4
    * , save "A"
    * , until 3==1:
  : * , save "A"


  Scenario: display TExt test2
    * , verify "Second Value:" Textbox is displayed
    * , verify "First Value:" , and "Second Value:" Textboxes are displayed
#    * , verify "First Value:" Textbox is displayed
#    * , verify "Second Value:" Textbox is displayed


  Scenario: tterts

    * , save "A", and save "B"
    * , if 1 equals 3 , save "C"


  Scenario Outline:
    *  IF:  3 == 4:
  : * SET "ngd" DATA TABLE
    | A |
    | 1 |
    | 2 |
    | 3 |
    *  ELSE:
  : * SET "ngd" DATA TABLE
    | A |
    | 1 |
    | 2 |
    | 3 |

    Examples:
      | Scenario Tags |
      | %compT3       |


  Scenario: context testifsdg3
  @[DEBUG,##]
    * , from "Search" Table, verify 1st Row equals "A"

  Scenario: testdfdfdfd
    * , if   { 1 == 2   }:  , save "1"

  Scenario: testifsdg3
  @[DEBUG,##]
    * , if    "true" :
  :  * , save "YES"

    * , if    true :
  :  * , save "YES"

    * , if    "A" == "A" :
  :  * , save "YES"
    * , if   { 1 == 2   }:  , save "1"
    * , if   { "1" == "2"   } :  , save "2"
##  * , if   "{ 1 == {1 + 1} }":
#  * , if   2 == {1 + 2} || 3 == 1 || 2 == 1 || 3 == {2 * 4}  :
#   :  * , save "NO"
#
#
#    * , if   2 == {1 + 2} || 3 == 1 || 2 == 1 || 3 == {2 * 4}  :
#  :  * , save "YES"

    * , if    "A" == "B" :
  :  * , save "YES"

    * , if    3 == {1 + 2} || (3 == 3 && ( 2 == 1 || 8 == {2 * 4}))  :
  :  * , save "YES"


  Scenario: dfsdfd55
    * IF:  1  THEN: , save "1"
    * IF: 2 == {1 + 2} || 3 == 1 || 2 == 1 || 8 == {2 * 4} THEN: , save "2"
    * IF: 3 == {1 + 2} && 3 == 3 && ( 2 == 1 || 8 == {2 * 4}) THEN: , save "3"


  Scenario: testif3sds3 d
#    * , save "AA" as "QQ"
    * , if 3 == 3  , save "6"
    * IF:  3 == 3 THEN: , save "6"

  Scenario: testif33
#    * , save "AA" as "QQ"
    * IF:  3 == 3 THEN: , save "6"
    * IF:  12 == 2  THEN: , save "1"
    * IF: 2 == 2 THEN: , save "2"
    * IF: 3 ==  5 THEN: , save "3"
    * IF: 3 == 3 THEN: , save "4"
    * IF: 3 == 3 THEN: , save "5"
    * IF:  3 ==  3 THEN: , save "5"

    * IF:  3 == 3  THEN: , save "6"


  Scenario: testif dsf emit test
#    * , save "AA" as "QQ"
    * IF:  3 == {1 + 2} || (3 == 3 && ( 2 == 1 || 8 == {2 * 4})) THEN: , save "6"


  Scenario: testif
#    * , save "AA" as "QQ"
    * IF:  3 == {1 + 2} || (3 == 3 && ( 2 == 1 || 8 == {2 * 4})) THEN: , save "6"
    * IF:  2 == {1 + 2} || 3 == 1 || 2 == 1 || 8 == {2 * 4}  THEN: , save "1"
    * IF: 2 == {1 + 2} || 3 == 1 || 2 == 1 || 8 == {2 * 4} THEN: , save "2"
    * IF: 3 == {1 + 2} && 3 == 3 && ( 2 == 1 || 8 == {2 * 4}) THEN: , save "3"
    * IF: 3 == {1 + 2} && 3 == 3 && ( 2 == 1 || 3 == {2 * 4}) THEN: , save "4"
    * IF: 3 == {1 + 2} || 3 == 3 && ( 2 == 1 || 3 == {2 * 4}) THEN: , save "5"
    * IF:  3 == {1 + 2} || (3 == 3 && ( 2 == 1 || 2 == {2 * 4})) THEN: , save "5"
    * IF:  3 == {1 + 2} || (3 == 3 && ( 2 == 1 || 8 == {2 * 4})) THEN: , save "6"


  Scenario: until test1
    * , save "1" as "X"
    * , save "<X>" as "A"
    * , if "<A>" == "111":
  : * , save "<q>" as "A"
    * , until "<A>" == "111":
  :   * , save "<A><X>" as "A"
    * , save "qqq" as "A"
    * , save "qqq" as "A"

  @testr
  Scenario: nesting log test 12
    When , if 1 == 2 , save "BBB"
    When , if 1,2,3, or 4 is 3?
  :  When , if 5,6,7, or 8 is 5?
  ::  * , save "A", and "B"

  @testr
  Scenario: nesting log test 13
    When , if 1,2,3, or 4 is 1?
  :  When , if 5,6,7, or 8 is 8?
  ::  * , save "A", and "B"


  @testr
  Scenario: nesting log test 12sss
    When , if 1,2,3, or 4 is 3?
  :  When , if 5,6,7, or 8 is 5?
  ::  * , save "A", and "B"

  Scenario: Extended comma separated assertion phrase combinationss CORRECTED

    When , if 1,2,3, or 4 is 5?
  : * , save "NO!!"

    When , if 1,2,3, or 4 is 3?
  : * , save "YES!!"

    When , if 1,2,3, and 4 is 5?
  : * , save "NO!!"

    When , if 1,2,3, and 4 is 3?
  : * , save "NO!!"

    When , if 0,1,2, or 3 is 9?
  : * , save "NO!!"

    When , if 0,0,0, or 7 is 7?
  : * , save "YES!!"

    When , if 0,0,0, or 7 is 8?
  : * , save "NO!!"

    When , if 1 is 1,0,2, and 3?
  : * , save "NO!!"

    When , if 1 is 1,5 is 5,2, and 3 is 3?
  : * , save "NO!!"

    When , if 1 is 2,5,6 is 6, or 0?
  : * , save "YES!!"

    When , if 1 is 2,0,6 is 7, or 0?
  : * , save "NO!!"

    When , if 9,8 is 8,7, and 6 is 6?
  : * , save "NO!!"

    When , if 9,8 is 7,7, and 6 is 6?
  : * , save "NO!!"

    When , if 4 is 4,0,2 is 2, or 0?
  : * , save "YES!!"

    When , if 4 is 5,0,2 is 3, or 0?
  : * , save "NO!!"

    When , if 0,3 is 3,0, and 8?
  : * , save "NO!!"

    When , if 5,3 is 3,0, or 8?
  : * , save "YES!!"

    When , if 0,1 is 1,2 is 2, and 3?
  : * , save "NO!!"

    When , if 0,1 is 1,2 is 2, or 3?
  : * , save "YES!!"

    When , if 6 is 6,7 is 8,9, or 0?
  : * , save "YES!!"

    When , if 6 is 7,7 is 8,0, or 0?
  : * , save "NO!!"

    When , if 10,20 is 20,30 is 31, and 40?
  : * , save "NO!!"

    When , if 10,20 is 20,30 is 31, or 40?
  : * , save "YES!!"

    When , if 0,0 is 0,1 is 1, and 2 is 2?
  : * , save "YES!!"

    When , if 0,0 is 0,1 is 1, or 2 is 2?
  : * , save "YES!!"

    When , if 3 is 3,2,1, and 0 is 1?
  : * , save "NO!!"

    When , if 3 is 3,2,1, or 0 is 1?
  : * , save "YES!!"

    When , if 8,7 is 7,6 is 6, and 5?
  : * , save "NO!!"

    When , if 8,7 is 0,6 is 6, and 5?
  : * , save "NO!!"

    When , if 0,4 is 4,0, or 9 is 10?
  : * , save "YES!!"

    When , if 0,4 is 5,0, or 9 is 10?
  : * , save "NO!!"

    When , if 12 is 12,0,13 is 13, and 14 is 14?
  : * , save "NO!!"

    When , if 12 is 12,15,13 is 13, and 14 is 14?
  : * , save "NO!!"

    When , if 2 is 3,4 is 4,0, or 6?
  : * , save "YES!!"

    When , if 2 is 3,4 is 5,0, or 0?
  : * , save "NO!!"

    When , if 1,2 is 2,0 is 0, and 3?
  : * , save "NO!!"

    When , if 1,2 is 2,0 is 0, or 3?
  : * , save "YES!!"

    When , if 0,11,12 is 12, and 13 is 13?
  : * , save "NO!!"

    When , if 0,11,12 is 12, or 13 is 13?
  : * , save "YES!!"

    When , if 5 is 5,6 is 6,7 is 7, and 8 is 8?
  : * , save "YES!!"

    When , if 5 is 5,6 is 0,7 is 7, and 8 is 8?
  : * , save "NO!!"

    When , if 5 is 6,6 is 0,7 is 8, or 8 is 8?
  : * , save "YES!!"

    When , if 5 is 6,6 is 0,7 is 8, or 8 is 9?
  : * , save "NO!!"


  Scenario: rfere
    When ,  1 is 4 , 2 is 4 , "true" , or  3 is 4?
  : * , save "YES!!"
    When ,  1 is 4 , 2 is 4 , or  3 is 4?
  : * , save "YES!!"

  Scenario: Chain testing sing passed forwardsf
    When , if 1,2,3, or 4 is 5?
  : * , save "YES!!"

    When , if 1,2,3, or 4 is 3?
  : * , save "YES!!"

    When , if 1,2,3, and 4 is 5?
  : * , save "NO!!"

    When , if 1,2,3, and 4 is 3?
  : * , save "NO!!"

  Scenario: chainasfd aw
    When ,  1 is 1 , 2 is 2 ,  3 is 3 , and {"true"} ?
  : * , save "YES!!"


  Scenario: Chain testing
    When ,  1 is 1 , 2 is 2 ,  and 3 is 2?
  : * , save "NO!!"

    When ,  1 is 1 , 2 is 2 ,  3 is 3 , and "true"?
  : * , save "YES!!"

    When ,  1 is 2 , 2 is 2 , or 3 is 3?
  : * , save "YES!!1"

    When ,  if 1 is 1 , and 2 is 2:
  : * , save "YES!!!"

    When ,  if 1 is 2 , or 2 is 2:
  : * , save "YES!!!!"

    When ,  1 is 1 , 2 is 3 , 3 is 3 , and 4 is 4?
  : * , save "NO!!!!"

    When ,  if 1 is 2 , 2 is 3 , or 3 is 3:
  : * , save "YES"

    When ,  1 is 1 , and 2 is 3?
  : * , save "NO!!!!"

    When ,  if 1 is 1 , 2 is 2 , 3 is 3 , or 4 is 5:
  : * , save "YES"

    When ,  1 is 2 , 2 is 2 , 3 is 3 , or 4 is 5?
  : * , save "YES!!!!"

    When ,  if 1 is 1 , 2 is 3 , and 3 is 3:
  : * , save "NO!!!!"


  Scenario: ssif test1sdads
    * , if "111" is "111", and "3222" is "222":
  :   * , save "<A><X>" as "A"
    * , if "222" is "111", or "222" is "222":
  :   * , save "<A><X>" as "A"

  Scenario: save concurrent test
#    * , save "1" as "A"
    * , save "<A>1" as "A"
    * , save "<A>1" as "A"
    * , save "<A>1" as "A"

  Scenario: ssif test1
    * , until "2225" , or "3222" is "222":
  :   * , save "<A><X>" as "A"
  : * , until "222" is "111", or "3222" is "222":
  ::   * , save "<A><X>" as "A"

  Scenario: if test1
    * , if "222" is "111", or "2s22" is "222":
  :   * , save "<A><X>" as "A"


  Scenario: if until test2
    * , save "2" as "X"
    * , save "<X>" as "A"
    * , if "<A>" is "11111", or "<A>" is "222":
  :   * , save "<A><X>" as "A"

  Scenario: until test2
    * , save "2" as "X"
    * , save "<X>" as "A"
    * , until "<A>" is "11111", or "<A>" is "222" , save "<A><X>" as "A"
  :   * , save "<A><X>" as "A"

  Scenario: Chain testing1
    * , save "2" as "X"
    * , save "<X>" as "A"
#    * , until "<A>" is "111":
    * , if "<A>" is "111", or "<A>" is "222":
  :   * , save "qqqqq" as "Q"
    * , until "<A>" is "111", or "<A>" is "222":
#    * , until "<A>" is "11":
  :   * , wait 1 second
  :   * , save "<A><X>" as "A"

  Scenario: Chain testingw3
    When ,  1 is 1 , 2 is 23 ,  and 3 is 3?
  : * , save "NO!!"

  Scenario: Chain testingw
    When ,  1 is 1 , 2 is 2 ,  and 3 is 2?
  : * , save "NO!!"


  Scenario: Chain testing sing
    When ,  3 is 2?
  : * , save "NO!!"

    When ,  1 is 1 ?
  : * , save "YES!!"

    When ,  3 is 3?
  : * , save "YES!!1"

    When ,  if 2 is 2:
  : * , save "YES!!!"

    When ,  if 2 is 2:
  : * , save "YES!!!!"

    When ,   2 is 3 ?
  : * , save "NO!!!!"

    When ,  if  3 is 3:
  : * , save "YES"

    When ,  2 is 3?
  : * , save "NO!!!!"

    When ,  if 1 is 1 :
  : * , save "YES"

    When ,   3 is 3 ?
  : * , save "YES!!!!"

    When ,  if  2 is 3 :
  : * , save "NO!!!!"


  Scenario: condtion nesting testing1
    * , from the Top Panel:
  : * IF: "A" equals "A":
  :: * , save "x1"
  : * ELSE-IF: "A" equals "A":
  :: * , save "x2"

  Scenario: condtion nesting testing2
    * , from the Top Panel:
  : * , if "A" equals "A":
  :: * , save "x1"
  : * , else if "A" equals "A":
  :: * , save "x2"

  Scenario: condtion nesting testing2.5

    * , if "A" equals "A":
  : * , save "x1"
    * , else if "A" equals "A":
  : * , save "x2"


  Scenario: condtion nesting testing3
    * IF: "A" equals "A":
  : * , save "x1"
    * ELSE-IF: "A" equals "A":
  : * , save "x2"


  Scenario: calling Test
    * test2

#    * , select the last Option "Choose a car:" Dropdown

  @T122
  Scenario: j test1ssszzz
  @[DEBUG]
#    * , verify the "Choose a car:" Dropdown is displayed
    * , select the last Option "Choose a car:" Dropdown

    * test2 JAVA_HOME
    * test2 NVM_HOME
    * dataTableTest1 dataTableTestxx
      | a | b |
      | 1 | 2 |
#    * navigate to: URL.select
#    * , verify "Subscribe" Checkbox is on, and "Subscribe" Checkbox is true
##    * , verify Button is not displayed, and wait 10 seconds
#    * test2 Q



  Scenario Outline: dynamic scenario run test <Scenario>
    * test2 test1

    Examples:
      | Scenario  |
      | Scenario1 |
      | Scenario2 |
      | Scenario3 |


  Scenario Outline: zzsStart Run component
    * , if "1" is "1":
  : * , save "a" as "A"
#    * , wait 6 seconds
    * , if "1" is "1":
  : * , save "a" as "A"
    Examples:
      | Scenario Tags |
      | %zer1         |

  @AAAs
  Scenario: sdf
    * ,  save any 3rd `\d` Match from "123456789"
    * ,  save  3rd `\d` Match from "12312321"
#    * ,  save 3rd "Case ID" Match from "12312321"
    * ,  save 3rd "stateID" Match from "abcdEFghHIjkLMnoPQ"
    * ,  save 2nd 'stateID' Match from "abcdEFghHIjkLMnoPQ"
#    * asdsdgfdsf

#    * , save "A1" as "B1" ,
#    * , save "A2" as "B2"
#    * , save "xA1" as "B" , save  "xC1" as "D" ,
#    * , save "xA2" as "B" , save  "xC2" as "D"
#    * , save "yA1" as "B" , save  "yC1" as "D" ,
#    * , save "yA2" as "B" , save  "yC2" as "D"

  @zz22
  Scenario: sente test1
#  @[DEBUG,##]
    * navigate to: URL.textbox
    * Scenario Log: exlog1 a= '<A>' , c= '<C>'
    * Scenario Log: exlog2 a= '<A>' , c= '<C>'
    * , wait 1 seconds


  Scenario: test path
#    * test2 <any>
#    * , save "A" as "A"
#    * , save "</configs>" as "A"
    * , save "</configs/yamlDrivers/configs2/yamlDrivers/chrome.connection>" as "A"
    * , save "</configs/yamlDrivers/Configs2.yamlDrivers/chrome.connection>" as "A"
    * , save "</configs/yamlDrivers/configs2.yamlDrivers/Chrome.connection>" as "A"
#


  Scenario: debug run Scena
    * Scenario Log: starting scenarios
    * RUN SCENARIOS:
      | Tags   | A   | B   |
      | %tag99 | 111 | 222 |


  Scenario Outline: called scenario11 a= '<A>' , c= '<C>'
    * Scenario Log: exlog1 a= '<A>' , c= '<C>'
    * Scenario Log: exlog2 a= '<A>' , c= '<C>'
    Examples:
      | Scenario Tags | A   | C   |
      | %tag99        | xxx | zzz |


  Scenario Outline: sfdd
    * Scenario Log: called scenario1 <A>
    * Scenario Log: called scenario2 <A>
    Examples:
      | @NotBlank Scexnario Tags | A      |
      | %xcztag99                | ~skip~ |


  Scenario: debug tEst2
  @[DEBUG,nobase,##]
    * , verify  "Text Input:" Textbox is displayed
    * IF: "zText Input:" Textbox is displayed:
  :   * , save "A" as "A"
    * ELSE-IF: "Text Input:" Textbox is displayed:
  :   * , save "B" as "B"
#      * , if Alert is displayed, verify Button is displayed
#    * navigate to: URL.select
#  * , in the Column Header, click the 1st Icon
#    * , verify  Elm is displayed
#      * , "Example Domain" Text is displayed
#    * , from the Top Panel:
#    * , verify   "Dropdown:" Ddd is displayed
#    * , verify  "Dropdown:" Dropdown is displayed
#    * , select the last Option "Choose a car:" Dropdown

  @T1
  Scenario: j test1sss
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

  Scenario: data test ssC

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
    * IF: 1 + 1 < 0:
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