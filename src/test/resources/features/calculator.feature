Feature: Tiny calculator

  Scenario Outline: Test2
    * For every ROW in DATA TABLE
  : Then print "aa <ROWS[<x>].K1>"
  : Then print "aa x: <x> , ROW.x: <ROW.x>  , x #1: <x #1> , x[0]: <x[0]> , K1: <K1> , ROW: <ROW> ,   ROW.K1: <ROW.K1> ,  ROW[0].K1: <ROW[0].K1> , ROW #1.K1: <ROW #1.K1>"
  : Then print "bb  ROWS[1].K1: <ROWS[1].K1> ,  ROWS #2.K1: <ROWS #2.K1>"
  : Then print "<x>  <K1> <K2> <K3>"
  :: Then print "<x>  <K1> <K2> <K3>"
    * DATA TABLE
      | K1 | x |
      | Z1 | 4 |
      | Z2 | 1 |
      | Z3 | 2 |

    Examples:
      | K1 | K2 | K3 |
      | A1 | A2 | 1  |
      | B1 | B2 | 2  |
      | C1 | C2 | 3  |

  Scenario Outline: Test
    Then print "<K1>"
    * For every ROW in DATA TABLE
  : Then print "aa <ROWS[2]>"
#  : Then print "aa x: <x> , ROW.x: <ROW.x>  , x #1: <x #1> , x[0]: <x[0]> , K1: <K1> , ROW: <ROW> ,   ROW.K1: <ROW.K1> ,  ROW[0].K1: <ROW[0].K1> , ROW #1.K1: <ROW #1.K1>"
#  : Then print "bb  ROWS[1].K1: <ROWS[1].K1> ,  ROWS #2.K1: <ROWS #2.K1>"
#  : Then print "<x>  <K1> <K2> <K3>"
#  :: Then print "<x>  <K1> <K2> <K3>"
    * DATA TABLE
      | K1 | x  |
      | Z1 | 11 |
      | Z2 | 22 |
      | Z3 | 33 |

    Then print "<K1> <K2> <K3>"

#    Examples:
#      | aK1 | aK2 | aK3 |
#      | A1 | A2 | A3 |


    Examples:
      | K1 | K2 | K3 |
      | A1 | A2 | A3 |



#  Scenario: d test datatabletest
#    Then a is 1 and b is 1
#    * datatabletest"Sd"
#    Then a is 2 and b is 6
#
#  Scenario: d test nodatatabletest
#    Then a is 1 and b is 1
#    * nodatatabletest"Sd"
#      | d |
#      | 1 |
#
#    Then a is 2 and b is 6


  Scenario: Calling Scenario
    Then a is 2 and b is 6
    * RUN COMPONENT SCENARIO
      | Tags   |
      | @test3 |
      |        |
      | @test5 |
    Then a is 1 and b is 6


  @test1 @sc1
  Scenario Outline: conditionals2
    * IF: 1 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE-IF: 1 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE-IF: 1 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE:
  : Then a is 2 and b is 3

    @sc2
    Examples:
      | Component Tags | Scenario Tags |
      | test3          | @test4        |

  @test1 @sc1
  Scenario Outline: cosss
    * IF: 1 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE-IF: 5 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE-IF: 1 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE:
  : Then a is 2 and b is 3

    @sc2
    Examples:
      | Component Tags | Scenario Tags |
      | test5          | @test4        |

  @test1 @sc1
  Scenario Outline: conditionals3
    * IF: 1 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE-IF: 5 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE-IF: 1 + 1 > 5
  : Then a is 1 and b is 6
    * ELSE:
  : Then a is 2 and b is 3

    @sc2
    Examples:
      | Scenario Tags |
      | @zztest4      |


#  Scenario: conditionals
#    * IF: true THEN: "a is 1 and b is 6" ELSE: d
#    * IF: true THEN: "a is 1 and b is 6" ELSE: d
#    * IF: true THEN: "a is 1 and b is 6" ELSE-IF: s THEN: s ELSE-IF: s THEN:
#    * ELSE:  THEN: "a is 1 and b is 6"
#    * IF: ss
#    * ELSE: sd
#    * ELSE-IF: sd
#  : Given a is 4 and b is 6
##  :: Given a is 22 and b is 6
#    Given a is 9 and b is 6
##    * IF: HAVE-VALUE: [6,0,0,0] THEN: I am running a testlzz 1111zzaa\"aa and s ELSE: s
#
#  Scenario: ssss 323
#    Given a is 1 and b is 6
#  : Given a is 11 and b is 6
#  :: Given a is 22 and b is 6
#    Given RUN IF SCENARIO FAILED
#  : Given a is 3 and b is 6
#  : Given a is 4 and b is 6
#  :: Given a is 5 and b is 6
#    Given a is 66 and b is 6
#
#
#  Scenario: calling scenario 1s
#
##    * SET TABLE VALUES
##      | A  | A  | A  | D  | E  |
##      | a1 | b1 | c1 | d1 | e1 |
##      | a2 | b2 | c2 | d2 | e2 |
##      | a3 | b3 | c3 | d3 | e3 |
#
#
#    * save "zzzzzzzzzzz-1" as "A"
#    * save "zzzzzzzzzzz-2" as "A"
#    * save "zzzzzzzzzzz-3" as "A"
#    * save "zzzzzzzzzzz-4" as "A"
##    * save "c-2" as "C"
##    * save "c-3" as "C"
##    * save "c-4" as "C"
##    * save "c-5" as "C"
#
##    Given the string "<C #2>" is attached as "A"
#
#    Given the string "2221qqqq" is attached as "<A[-2]>"
##    Given the string "<ROW[2] .A as-LIST>" is attached as "<A[-2]>"
##    Given the string "<ROW[1] .A as-LIST>" is attached as "A"
##    Given the string "<ROW[*] .A as-LIST>" is attached as "A"
##    Given the string "<A #1> <B #2> <C #3> <C #3> <C #4> <C #5> <C #6>" is attached as "A"
##    Given the string "<A #1> <B #1> <C #1> <D #1>" is attached as "<B>"
##    Given the string "<A #2> <B #2> <C #2> <D #2>" is attached as "<B>"
##    Given the string "<A #3> <B #3> <C #3> <D #3>" is attached as "<B>"
##    Given the string "<A> <B> <C> <D>" is attached as "<B>"
##    Given the string "s1.1" is attached as "<B>"
##
##    Then RUN SCENARIOS
##      | Scenario Tags | A  | B  |
##      | @cc           | 77 | 88 |
##
##    Given the string "s1.2" is attached as "<B>"
#
#  @cc
#  Scenario: called Scenario2 <Scenario Tags> <A> , <B>
#    Given the string "2.1" is attached as "<B> , tags: <Scenario Tags>"
#    Given the string "2.2" is attached as "<B> , tags: <Scenario Tags>"
#
#
#  Scenario Outline: wwnew  <A> , <B>
#    Given a is 1 and b is 2
#    Given a is <A> and b is <B>
#    Given a is 2 and b is 2
#
#
#    Examples:
#      | Scenario Tags | A  | B  |
#      | @aa           | 22 | 33 |
#      | @aa           | 44 | 55 |
#
#  Scenario Outline: line <A> , <B>
#    Given the string "<A>" is attached as "<B>"
#
#    Then RUN SCENARIOS
#      | Scenario Tags | A  | B  | D  |
#      | @aea          | 77 | 88 | 99 |
##      | <Tags>        | 77 | 88 | 99 |
#
#
#    Examples:
#      | Tags | A  | B  |
#      | @aa  | 22 | 33 |
##      | @bb  | 44 | 78 |
#
#
  @bb
  Scenario Outline: new  <A> , <B>, tags:  <D>
    Given stringCheck "0 d: <D>, a: <A> , c:<C>"
    Then RUN SCENARIOS
      | Tags |
      | <D>  |

    Examples:
      | D   | A  | C    |
      | @aa | 22 | 9988 |
#      | 33  | 44 | 9966 |


  @TagK @aa
  Scenario: scenarioA12
    Given stringCheck "1 d: <D>, a: <A> , c:<C>"
    * DATA TABLE
      | Scenario | A  | B   |
      | 1        | 11 | 222 |
#    Given stringCheck "1 d: s, a: a , cc"
#  :    Given stringCheck "2 d: <D>, a: <A> , c:<C>"
#  :: Given a is 2 and b is 3
#    Given a is 4 and b is 1
##  : Given a is 4 and b is 2
#  :: Given a is 4 and b is 3
#
#
#  Scenario: scenario test dtable
#    Given test dtable
#      | Scenario | A  | B   |
#      | 1        | 11 | 222 |
#
#  Scenario: scenario test dtable2
#    Given a is 2 and b is 5
#    Given test dtable2
#      | Scenario | A  | B   |
#      | 1        | 11 | 222 |
#
#
#  Scenario: getScenarios 1
#    Then RUN SCENARIOS
#      | Scenario Tags |
#      | @aa           |


