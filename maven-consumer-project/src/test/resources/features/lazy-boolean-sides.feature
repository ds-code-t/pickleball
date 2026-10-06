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

  Scenario: A skipped ternary arm with a missing reference does not throw
    * , save "true" as "Flag"
    * , save "6" as "Taken"
    * resolving "<Flag> ? <Taken> : <MISSING>" is recorded as "ternarySkip" and the info contains "-> true ? 6 : <MISSING> -> 6"
    * , ensure "<ternarySkipStatus>" equals "ok"
    * , ensure "<ternarySkip>" equals "6"
    * , ensure "<ternarySkipLogOk>" equals "yes"

  Scenario: A skipped ternary arm keeps nested operators and a dollar call unpasted
    * reset branch counts
    * resolving "false ? (<MISSING> && <NOPE>) : 1" is recorded as "ternaryNestedSkip" and the info contains "-> false ? (<MISSING> && <NOPE>) : 1 -> 1"
    * , ensure "<ternaryNestedSkipStatus>" equals "ok"
    * , ensure "<ternaryNestedSkip>" equals "1"
    * , ensure "<ternaryNestedSkipLogOk>" equals "yes"
    * IF: <{ false ? <$count branch ternary-skip> : true }> THEN: , save "yes" as "ternaryDollar"
    * , ensure "<ternaryDollar>" equals "yes"
    * branch count "ternary-skip" equals "0"

  Scenario: A taken ternary arm pastes and still throws
    * resolving "true ? ((( : <MISSING>" fails as "ternaryTaken" and the info contains "-> true ? ((( : <MISSING> ->"
    * , ensure "<ternaryTakenStatus>" equals "threw"

  Scenario: A trailing question mark still means boolean
    * , save "true" as "A"
    * resolving "<A>?" is recorded as "trailingTrue" and the info contains "-> true -> true"
    * , ensure "<trailingTrueStatus>" equals "ok"
    * , ensure "<trailingTrue>" equals "true"
    * , ensure "<trailingTrueLogOk>" equals "yes"
    * resolving "1 == 0?" is recorded as "trailingFalse" and the info contains "-> 1 == 0 -> false"
    * , ensure "<trailingFalse>" equals "false"

  Scenario: And and or still paste only the side that runs
    * , save "true" as "A"
    * resolving "<A> || <MISSING>" is recorded as "orStill" and the info contains "-> true || <MISSING> -> true"
    * , ensure "<orStillStatus>" equals "ok"
    * , ensure "<orStill>" equals "true"
    * , ensure "<orStillLogOk>" equals "yes"
    * , save "false" as "A"
    * resolving "<A> && <MISSING>" is recorded as "andStill" and the info contains "-> false && <MISSING> -> false"
    * , ensure "<andStill>" equals "false"
    * , ensure "<andStillLogOk>" equals "yes"

  Scenario: A plain expression logs the filled text and the result
    * , save "6" as "A"
    * resolving "<A> + 1" is recorded as "plainSum" and the info contains "-> 6 + 1 -> 7"
    * , ensure "<plainSumStatus>" equals "ok"
    * , ensure "<plainSum>" equals "7"
    * , ensure "<plainSumLogOk>" equals "yes"

