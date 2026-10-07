@all @single-element-condition
Feature: A single-element IF or ELSE-IF is truthy or falsy and does not throw

  Scenario: Inline IF/THEN runs a truthy single element and skips a falsy one
    * , save "abc" as "textRef"
    * , save "true" as "trueRef"
    * , save "false" as "falseRef"
    * , save "" as "blankRef"
    * , save "untouched" as "emptyQuote"
    * , save "untouched" as "falseRefResult"
    * , save "untouched" as "blankRefResult"
    * , save "untouched" as "missingRefResult"
    * , save "untouched" as "bareFalse"
    * , save "untouched" as "zero"
    * , save "untouched" as "exprFalse"
    * IF: "abc" THEN: , save "yes" as "doubleQuote"
    * IF: 'abc' THEN: , save "yes" as "singleQuote"
    * IF: "" THEN: , save "no" as "emptyQuote"
    * IF: <textRef> THEN: , save "yes" as "textRefResult"
    * IF: <trueRef> THEN: , save "yes" as "trueRefResult"
    * IF: <falseRef> THEN: , save "no" as "falseRefResult"
    * IF: <blankRef> THEN: , save "no" as "blankRefResult"
    * IF: <missingRef> THEN: , save "no" as "missingRefResult"
    * IF: true THEN: , save "yes" as "bareTrue"
    * IF: false THEN: , save "no" as "bareFalse"
    * IF: 7 THEN: , save "yes" as "number"
    * IF: 0 THEN: , save "no" as "zero"
    * IF: <{ "abc" }> THEN: , save "yes" as "exprText"
    * IF: <{ 0 }> THEN: , save "no" as "exprFalse"
    * , ensure "<doubleQuote>" equals "yes"
    * , ensure "<singleQuote>" equals "yes"
    * , ensure "<emptyQuote>" equals "untouched"
    * , ensure "<textRefResult>" equals "yes"
    * , ensure "<trueRefResult>" equals "yes"
    * , ensure "<falseRefResult>" equals "untouched"
    * , ensure "<blankRefResult>" equals "untouched"
    * , ensure "<missingRefResult>" equals "untouched"
    * , ensure "<bareTrue>" equals "yes"
    * , ensure "<bareFalse>" equals "untouched"
    * , ensure "<number>" equals "yes"
    * , ensure "<zero>" equals "untouched"
    * , ensure "<exprText>" equals "yes"
    * , ensure "<exprFalse>" equals "untouched"

  Scenario: Inline ELSE-IF and ELSE use the same single-element rule
    * , save "abc" as "textRef"
    * , save "false" as "falseRef"
    * IF: "" THEN: , save "then" as "picked" ELSE-IF: 0 THEN: , save "zero" as "picked" ELSE-IF: 'abc' THEN: , save "else-if" as "picked" ELSE: , save "else" as "picked"
    * IF: false THEN: , save "then" as "falsyChain" ELSE-IF: <falseRef> THEN: , save "else-if" as "falsyChain" ELSE: , save "else" as "falsyChain"
    * IF: <missingRef> THEN: , save "then" as "missingChain" ELSE-IF: "" THEN: , save "else-if" as "missingChain" ELSE: , save "else" as "missingChain"
    * , ensure "<picked>" equals "else-if"
    * , ensure "<falsyChain>" equals "else"
    * , ensure "<missingChain>" equals "else"

  Scenario: A block IF runs nested steps only when its single element is truthy
    * , save "abc" as "textRef"
    * , save "untouched" as "blockEmpty"
    * , save "untouched" as "blockZero"
    * , save "untouched" as "blockFalse"
    * IF: "abc":
      : * , save "yes" as "blockDouble"
    * IF: "":
      : * , save "no" as "blockEmpty"
    * IF: <textRef>:
      : * , save "yes" as "blockText"
    * IF: 0:
      : * , save "no" as "blockZero"
    * IF: false:
      : * , save "no" as "blockFalse"
    * IF: <{ 4 }>:
      : * , save "yes" as "blockExpr"
    * , ensure "<blockDouble>" equals "yes"
    * , ensure "<blockEmpty>" equals "untouched"
    * , ensure "<blockText>" equals "yes"
    * , ensure "<blockZero>" equals "untouched"
    * , ensure "<blockFalse>" equals "untouched"
    * , ensure "<blockExpr>" equals "yes"

  Scenario: A block ELSE-IF with a single element selects that branch
    * , save "untouched" as "blockElseIfSkip"
    * IF: "":
      : * , save "no" as "blockElseIf"
    * ELSE-IF: "abc":
      : * , save "yes" as "blockElseIf"
    * ELSE:
      : * , save "else" as "blockElseIf"
    * IF: false:
      : * , save "no" as "blockElseIfSkip"
    * ELSE-IF: 0:
      : * , save "no" as "blockElseIfSkip"
    * ELSE:
      : * , save "else" as "blockElseIfSkip"
    * , ensure "<blockElseIf>" equals "yes"
    * , ensure "<blockElseIfSkip>" equals "else"

  Scenario: An operator and an element check still select the branch
    * , save "abc" as "textRef"
    * , save "untouched" as "operator"
    * IF: 1 == 0 THEN: , save "no" as "operator" ELSE-IF: 2 > 1 THEN: , save "yes" as "operator"
    * IF: <textRef> has value THEN: , save "yes" as "elementCheck"
    * , ensure "<operator>" equals "yes"
    * , ensure "<elementCheck>" equals "yes"

  Scenario: A skipped single-element branch is not resolved
    * reset branch counts
    * IF: "" THEN: , save "<{ <$count branch single-element-skip> }>" as "skippedCount" ELSE: , save "else-ran" as "lazyElse"
    * IF: "abc" THEN: , save "taken" as "lazyIf" ELSE-IF: <missingLater> THEN: , save "<$DateTime:now <MISSING> format: uuuu>" as "lazyBomb"
    * , ensure "<lazyElse>" equals "else-ran"
    * , ensure "<lazyIf>" equals "taken"
    * branch count "single-element-skip" equals "0"

  @area-a @area-nest-depth-1
  Scenario: Block IF ELSE-IF and ELSE at depth 1 use plain single elements
    * , save "untouched" as "d1abc"
    * , save "untouched" as "d1empty"
    * , save "untouched" as "d1zero"
    * , save "untouched" as "d1false"
    * , save "untouched" as "d1missing"
    * IF: "abc":
      : * , save "yes" as "d1abc"
    * ELSE:
      : * , save "else" as "d1abc"
    * IF: "":
      : * , save "no" as "d1empty"
    * ELSE-IF: "abc":
      : * , save "yes" as "d1empty"
    * ELSE:
      : * , save "else" as "d1empty"
    * IF: 0:
      : * , save "no" as "d1zero"
    * ELSE:
      : * , save "else" as "d1zero"
    * IF: false:
      : * , save "no" as "d1false"
    * ELSE:
      : * , save "else" as "d1false"
    * IF: <missingDepth1>:
      : * , save "no" as "d1missing"
    * ELSE:
      : * , save "else" as "d1missing"
    * , ensure "<d1abc>" equals "yes"
    * , ensure "<d1empty>" equals "yes"
    * , ensure "<d1zero>" equals "else"
    * , ensure "<d1false>" equals "else"
    * , ensure "<d1missing>" equals "else"

  @area-a @area-nest-depth-2
  Scenario: A depth-2 block runs the inner IF only when the parent is truthy
    * , save "untouched" as "d2"
    * , save "untouched" as "d2skipEmpty"
    * , save "untouched" as "d2skipZero"
    * , save "untouched" as "d2skipFalse"
    * , save "untouched" as "d2skipMissing"
    * IF: "abc":
      : * IF: "":
        :: * , save "no" as "d2"
      : * ELSE-IF: 0:
        :: * , save "no" as "d2"
      : * ELSE-IF: false:
        :: * , save "no" as "d2"
      : * ELSE-IF: <missingDepth2>:
        :: * , save "no" as "d2"
      : * ELSE-IF: "abc":
        :: * , save "yes" as "d2"
      : * ELSE:
        :: * , save "else" as "d2"
    * IF: "":
      : * IF: "abc":
        :: * , save "no" as "d2skipEmpty"
    * IF: 0:
      : * IF: "abc":
        :: * , save "no" as "d2skipZero"
    * IF: false:
      : * IF: "abc":
        :: * , save "no" as "d2skipFalse"
    * IF: <missingDepth2Outer>:
      : * IF: "abc":
        :: * , save "no" as "d2skipMissing"
    * , ensure "<d2>" equals "yes"
    * , ensure "<d2skipEmpty>" equals "untouched"
    * , ensure "<d2skipZero>" equals "untouched"
    * , ensure "<d2skipFalse>" equals "untouched"
    * , ensure "<d2skipMissing>" equals "untouched"

  @area-a @area-nest-depth-3
  Scenario: A depth-3 block runs the inner IF only when every parent is truthy
    * , save "untouched" as "d3"
    * , save "untouched" as "d3yes"
    * , save "untouched" as "d3skipEmpty"
    * , save "untouched" as "d3skipZero"
    * , save "untouched" as "d3skipFalse"
    * , save "untouched" as "d3skipMissing"
    * , save "untouched" as "d3skipMid"
    * IF: "abc":
      : * IF: "abc":
        :: * IF: "":
          ::: * , save "no" as "d3"
        :: * ELSE-IF: 0:
          ::: * , save "no" as "d3"
        :: * ELSE-IF: false:
          ::: * , save "no" as "d3"
        :: * ELSE:
          ::: * , save "else" as "d3"
    * IF: "abc":
      : * IF: "abc":
        :: * IF: "abc":
          ::: * , save "yes" as "d3yes"
    * IF: "":
      : * IF: "abc":
        :: * IF: "abc":
          ::: * , save "no" as "d3skipEmpty"
    * IF: 0:
      : * IF: "abc":
        :: * IF: "abc":
          ::: * , save "no" as "d3skipZero"
    * IF: false:
      : * IF: "abc":
        :: * IF: "abc":
          ::: * , save "no" as "d3skipFalse"
    * IF: <missingDepth3>:
      : * IF: "abc":
        :: * IF: "abc":
          ::: * , save "no" as "d3skipMissing"
    * IF: "abc":
      : * IF: "":
        :: * IF: "abc":
          ::: * , save "no" as "d3skipMid"
    * , ensure "<d3>" equals "else"
    * , ensure "<d3yes>" equals "yes"
    * , ensure "<d3skipEmpty>" equals "untouched"
    * , ensure "<d3skipZero>" equals "untouched"
    * , ensure "<d3skipFalse>" equals "untouched"
    * , ensure "<d3skipMissing>" equals "untouched"
    * , ensure "<d3skipMid>" equals "untouched"

  @area-a @area-inline-inside-block
  Scenario: An inline IF THEN inside a block IF follows the outer branch
    * , save "untouched" as "innerThen"
    * , save "untouched" as "innerSkip"
    * IF: "abc":
      : * IF: "abc" THEN: , save "yes" as "innerThen"
    * IF: "":
      : * IF: "abc" THEN: , save "no" as "innerSkip"
    * IF: 0:
      : * IF: "abc" THEN: , save "no" as "innerSkip"
    * IF: false:
      : * IF: "abc" THEN: , save "no" as "innerSkip"
    * IF: <missingInlineOuter>:
      : * IF: "abc" THEN: , save "no" as "innerSkip"
    * , ensure "<innerThen>" equals "yes"
    * , ensure "<innerSkip>" equals "untouched"

  @area-a @area-else-after-plain
  Scenario: A plain step between IF and ELSE does not hide a false IF
    * , save "untouched" as "betweenInner"
    * , save "untouched" as "betweenElse"
    * , save "untouched" as "betweenTakenElse"
    * IF: false:
      : * , save "no" as "betweenInner"
    * , save "plain" as "betweenPlain"
    * ELSE:
      : * , save "else" as "betweenElse"
    * IF: "abc":
      : * , save "yes" as "betweenTaken"
    * , save "plain" as "betweenTakenPlain"
    * ELSE:
      : * , save "else" as "betweenTakenElse"
    * , ensure "<betweenInner>" equals "untouched"
    * , ensure "<betweenPlain>" equals "plain"
    * , ensure "<betweenElse>" equals "else"
    * , ensure "<betweenTaken>" equals "yes"
    * , ensure "<betweenTakenPlain>" equals "plain"
    * , ensure "<betweenTakenElse>" equals "untouched"
