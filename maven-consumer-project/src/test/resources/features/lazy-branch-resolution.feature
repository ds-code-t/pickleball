@all @lazy-branch-resolution
Feature: Conditional branches resolve references only when taken

  Scenario: A skipped THEN does not call DateTime or resolve an unresolved reference
    * reset branch counts
    * , save "alive" as "afterSkippedThen"
    * IF: 1 == 0 THEN: save "<$DateTime:now <MISSING> format: uuuu>"
    * IF: false THEN: save "<$count branch skipped-then>"
    * , ensure "<afterSkippedThen>" equals "alive"
    * branch count "skipped-then" equals "0"

  Scenario: A true IF does not evaluate a later ELSE-IF with an unresolved reference
    * , save "6" as "A"
    * IF: <A> > 5 THEN: , save "a" as "picked" ELSE-IF: <B> > 5 THEN: , save "b" as "picked"
    * , ensure "<picked>" equals "a"

  Scenario: An expression side effect runs only on the taken branch and only once
    * reset branch counts
    * IF: 1 == 0 THEN: , save "<{ <$count branch expr-skipped> }>" as "exprSkipped" ELSE: , save "<{ <$count branch expr-taken> }>" as "exprTaken"
    * , ensure "<exprTaken>" equals "1"
    * branch count "expr-taken" equals "1"
    * branch count "expr-skipped" equals "0"

  Scenario: A multi-step block resolves only the chosen branch
    * reset branch counts
    * IF: 1 == 1:
      : * , save "if-ran" as "blockBranch"
      : * , save "<$count branch block-if>" as "blockIfCount"
    * ELSE-IF: <B> > 5:
      : * , save "<$DateTime:now <MISSING> format: uuuu>" as "blockNope"
      : * , save "<$count branch block-else-if>" as "blockElseIfCount"
    * ELSE:
      : * , save "<$count branch block-else>" as "blockElseCount"
    * , ensure "<blockBranch>" equals "if-ran"
    * , ensure "<blockIfCount>" equals "1"
    * branch count "block-if" equals "1"
    * branch count "block-else-if" equals "0"
    * branch count "block-else" equals "0"

  Scenario: A false multi-step block does not resolve the skipped steps
    * reset branch counts
    * IF: 1 == 0:
      : * , save "<$DateTime:now <MISSING> format: uuuu>" as "skippedBlockStamp"
      : * , save "<$count branch block-skipped>" as "skippedBlockCount"
    * ELSE:
      : * , save "else-ran" as "falseBlockBranch"
    * , ensure "<falseBlockBranch>" equals "else-ran"
    * branch count "block-skipped" equals "0"

  Scenario: A dynamic-step inline conditional uses the same timing
    * reset branch counts
    * , save "6" as "A"
    * , if <A> > 5, save "a" as "dynPicked", else if <B> > 5, save "b" as "dynPicked"
    * , ensure "<dynPicked>" equals "a"
    * , if 1 == 0, save "<$DateTime:now <MISSING> format: uuuu>" as "dynNope", else, save "<{ <$count branch comma-else> }>" as "commaElse"
    * , ensure "<commaElse>" equals "1"
    * branch count "comma-else" equals "1"
