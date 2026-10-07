@all @expression-plain-forms
Feature: Plain expression forms select a branch without throwing

  Scenario: A leading bang and comparisons select the branch
    * , save "untouched" as "notFalse"
    * , save "untouched" as "notTrue"
    * , save "untouched" as "notText"
    * , save "untouched" as "ne"
    * , save "untouched" as "leEq"
    * , save "untouched" as "ge"
    * , save "untouched" as "leSkip"
    * IF: !false THEN: , save "yes" as "notFalse"
    * IF: !true THEN: , save "no" as "notTrue"
    * IF: !"abc" THEN: , save "no" as "notText"
    * IF: 1 != 0 THEN: , save "yes" as "ne"
    * IF: 1 <= 1 THEN: , save "yes" as "leEq"
    * IF: 2 >= 1 THEN: , save "yes" as "ge"
    * IF: 2 <= 1 THEN: , save "no" as "leSkip"
    * , ensure "<notFalse>" equals "yes"
    * , ensure "<notTrue>" equals "untouched"
    * , ensure "<notText>" equals "untouched"
    * , ensure "<ne>" equals "yes"
    * , ensure "<leEq>" equals "yes"
    * , ensure "<ge>" equals "yes"
    * , ensure "<leSkip>" equals "untouched"

  Scenario: A tilde expression runs and a missing reference does not throw
    * , save "untouched" as "tilde"
    * , save "untouched" as "tildeTrueMissing"
    * , save "untouched" as "tildeFalseMissing"
    * IF: ~[~{ 1 == 1 }~]~ THEN: , save "yes" as "tilde"
    * IF: ~[~{ true && <missingRef> }~]~ THEN: , save "no" as "tildeTrueMissing"
    * IF: ~[~{ false && <missingRef> }~]~ THEN: , save "no" as "tildeFalseMissing"
    * , ensure "<tilde>" equals "yes"
    * , ensure "<tildeTrueMissing>" equals "untouched"
    * , ensure "<tildeFalseMissing>" equals "untouched"

  @area-a @area-trailing-q
  Scenario: A trailing question mark is the boolean marker
    * , save "untouched" as "qTrue"
    * , save "untouched" as "qFalse"
    * , save "untouched" as "qYes"
    * , save "untouched" as "qOne"
    * , save "untouched" as "qZero"
    * IF: <{ true? }> THEN: , save "yes" as "qTrue"
    * IF: <{ false? }> THEN: , save "no" as "qFalse"
    * IF: <{ "yes"? }> THEN: , save "yes" as "qYes"
    * IF: <{ 1? }> THEN: , save "yes" as "qOne"
    * IF: <{ "0"? }> THEN: , save "no" as "qZero"
    * , ensure "<qTrue>" equals "yes"
    * , ensure "<qFalse>" equals "untouched"
    * , ensure "<qYes>" equals "yes"
    * , ensure "<qOne>" equals "yes"
    * , ensure "<qZero>" equals "untouched"

  @area-a @area-bool
  Scenario: bool is truthy or falsy inline and in a block
    * , save "untouched" as "boolFalse"
    * , save "untouched" as "boolBlockSkip"
    * IF: <{ bool(true) }> THEN: , save "yes" as "boolTrue"
    * IF: <{ bool(false) }> THEN: , save "no" as "boolFalse"
    * IF: <{ bool(true) }>:
      : * , save "yes" as "boolBlock"
    * IF: <{ bool(false) }>:
      : * , save "no" as "boolBlockSkip"
    * , ensure "<boolTrue>" equals "yes"
    * , ensure "<boolFalse>" equals "untouched"
    * , ensure "<boolBlock>" equals "yes"
    * , ensure "<boolBlockSkip>" equals "untouched"

  @area-a @area-block-expression
  Scenario: A block expression condition uses isTruthy
    * , save "untouched" as "blockExpr"
    * , save "untouched" as "blockExprSkip"
    * IF: <{ 1? && !false }>:
      : * , save "yes" as "blockExpr"
    * IF: <{ "0"? || false }>:
      : * , save "no" as "blockExprSkip"
    * , ensure "<blockExpr>" equals "yes"
    * , ensure "<blockExprSkip>" equals "untouched"

  @area-a @area-first-not
  Scenario: firstNotBlank firstNotEmpty firstNotNull and getBool select the branch
    * , save "untouched" as "fnbNo"
    * , save "untouched" as "fneNo"
    * , save "untouched" as "fnnNo"
    * , save "untouched" as "gbNo"
    * IF: <{ firstNotBlank("", "  ", "abc") }> THEN: , save "yes" as "fnb"
    * IF: <{ firstNotBlank("", "  ") }> THEN: , save "no" as "fnbNo"
    * IF: <{ firstNotEmpty("", "abc") }> THEN: , save "yes" as "fne"
    * IF: <{ firstNotEmpty("", "") }> THEN: , save "no" as "fneNo"
    * IF: <{ firstNotNull("<NULL>", "kept") }> THEN: , save "yes" as "fnn"
    * IF: <{ firstNotNull("<NULL>", "<gone>") }> THEN: , save "no" as "fnnNo"
    * IF: <{ getBool("yes") }> THEN: , save "yes" as "gb"
    * IF: <{ getBool(false) }> THEN: , save "no" as "gbNo"
    * , ensure "<fnb>" equals "yes"
    * , ensure "<fnbNo>" equals "untouched"
    * , ensure "<fne>" equals "yes"
    * , ensure "<fneNo>" equals "untouched"
    * , ensure "<fnn>" equals "yes"
    * , ensure "<fnnNo>" equals "untouched"
    * , ensure "<gb>" equals "yes"
    * , ensure "<gbNo>" equals "untouched"

  @area-a @area-string-truth
  Scenario: false123 and no1 stay truthy and a spaced no stays falsy
    * , save "untouched" as "spacedNo"
    * , save "untouched" as "spacedNoExpr"
    * IF: "false123" THEN: , save "yes" as "false123"
    * IF: "no1" THEN: , save "yes" as "no1"
    * IF: " no " THEN: , save "no" as "spacedNo"
    * IF: <{ "false123"? }> THEN: , save "yes" as "false123Expr"
    * IF: <{ "no1"? }> THEN: , save "yes" as "no1Expr"
    * IF: <{ " no "? }> THEN: , save "no" as "spacedNoExpr"
    * , ensure "<false123>" equals "yes"
    * , ensure "<no1>" equals "yes"
    * , ensure "<spacedNo>" equals "untouched"
    * , ensure "<false123Expr>" equals "yes"
    * , ensure "<no1Expr>" equals "yes"
    * , ensure "<spacedNoExpr>" equals "untouched"

  @area-a @area-stress-expr
  Scenario: One expression mixes bool firstNotBlank a nested ternary a pipe and a trailing question mark
    * reset branch counts
    * , save "untouched" as "mixInline"
    * , save "untouched" as "mixBlock"
    * IF: <{ (bool(firstNotBlank("", "abc")) && (0 ? <$count branch mix-skip> : 1 | 0))? }> THEN: , save "yes" as "mixInline"
    * IF: <{ (bool(firstNotBlank("", "abc")) && (0 ? <$count branch mix-skip> : 1 | 0))? }>:
      : * , save "yes" as "mixBlock"
    * , ensure "<mixInline>" equals "yes"
    * , ensure "<mixBlock>" equals "yes"
    * branch count "mix-skip" equals "0"
