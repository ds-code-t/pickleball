@all @lazy-boolean-sides
Feature: Boolean sides resolve only when that side runs

  Scenario: A true left side does not resolve the missing right side
    * , save "true" as "A"
    * IF: <A> || <MISSING> THEN: , save "yes" as "orMissing"
    * , ensure "<orMissing>" equals "yes"

  Scenario: A missing left side falls through when the right side is true
    * , save "true" as "B"
    * IF: <MISSING> || <B> THEN: , save "yes" as "orMissingRight"
    * , ensure "<orMissingRight>" equals "yes"

  Scenario: A blank left side is false and does not break the expression
    * , save "" as "A"
    * , save "true" as "B"
    * IF: <A> || <B> THEN: , save "yes" as "blankOr"
    * , ensure "<blankOr>" equals "yes"

  Scenario: A dollar call on the skipped side does not run
    * reset branch counts
    * , save "true" as "A"
    * IF: <A> || <$count branch skipped-or> THEN: , save "yes" as "dollarOr"
    * , ensure "<dollarOr>" equals "yes"
    * branch count "skipped-or" equals "0"

  Scenario: A taken comparison pastes a numeric string
    * , save "6" as "A"
    * IF: <A> > 5 || <MISSING> THEN: , save "yes" as "numericOr"
    * , ensure "<numericOr>" equals "yes"

  Scenario: A single pipe still resolves both sides
    * reset branch counts
    * IF: (<$count branch pipe-left> | <$count branch pipe-right>) == 1 THEN: , save "yes" as "pipeBoth"
    * , ensure "<pipeBoth>" equals "yes"
    * branch count "pipe-left" equals "1"
    * branch count "pipe-right" equals "1"

  Scenario: Bad syntax on a skipped or side does not throw
    * , save "true" as "A"
    * resolving "<A> || ((((" is recorded as "skippedSyntax"
    * , ensure "<skippedSyntaxStatus>" equals "ok"
    * , ensure "<skippedSyntax>" equals "true"

  Scenario: Bad syntax on a taken side still throws
    * , save "true" as "A"
    * resolving "((( || <A>" is recorded as "takenSyntax"
    * , ensure "<takenSyntaxStatus>" equals "threw"

  Scenario: A nested skipped side is not resolved
    * reset branch counts
    * , save "true" as "A"
    * IF: <A> || (<MISSING> && <$count branch nested-and>) THEN: , save "yes" as "nestedOr"
    * , ensure "<nestedOr>" equals "yes"
    * branch count "nested-and" equals "0"
    * IF: false && (<MISSING> || <$count branch nested-or>) THEN: , save "no" as "nestedAnd" ELSE: , save "yes" as "nestedAnd"
    * , ensure "<nestedAnd>" equals "yes"
    * branch count "nested-or" equals "0"

  Scenario: Operators inside quotes are not boolean splits
    * , save "true" as "A"
    * IF: "a || b" == "a || b" || <MISSING> THEN: , save "yes" as "quotedOr"
    * , ensure "<quotedOr>" equals "yes"
