@all @syntax-since-2.1.14
Feature: Syntax added since 2.1.14

  Scenario: A normal step inherits its data table
    * the recorded city is saved as "inheritedCity"
      | city  |
      | Tempe |
    * , verify "<inheritedCity>" equals "Tempe"
    * , verify "<inheritedCityMarker>" equals ""

  Scenario: A DT marker overrides the step data table
    * the recorded city is saved as "overrideCity" DT:::city|Paris|
      | city  |
      | Tempe |
    * , verify "<overrideCity>" equals "Paris"
    * , verify "<overrideCityMarker>" equals "DT"

  Scenario: An IF branch inherits the line data table
    * IF: 1 == 1 THEN: the recorded city is saved as "ifCity" ELSE-IF: 1 == 0 THEN: the recorded city is saved as "ifElseIfMiss" ELSE: the recorded city is saved as "ifElseMiss"
      | city  |
      | Tempe |
    * , verify "<ifCity>" equals "Tempe"

  Scenario: An ELSE-IF branch inherits the line data table
    * IF: 1 == 0 THEN: the recorded city is saved as "elseIfMiss" ELSE-IF: 1 == 1 THEN: the recorded city is saved as "elseIfCity" ELSE: the recorded city is saved as "elseIfElseMiss"
      | city  |
      | Tempe |
    * , verify "<elseIfCity>" equals "Tempe"

  Scenario: An ELSE branch inherits the line data table
    * IF: 1 == 0 THEN: the recorded city is saved as "elseThenMiss" ELSE-IF: 1 == 0 THEN: the recorded city is saved as "elseElseIfMiss" ELSE: the recorded city is saved as "elseCity"
      | city  |
      | Tempe |
    * , verify "<elseCity>" equals "Tempe"

  Scenario: An IF branch inherits the line doc string
    * IF: 1 == 1 THEN: the recorded note is saved as "ifNote" ELSE-IF: 1 == 0 THEN: the recorded note is saved as "ifNoteElseIf" ELSE: the recorded note is saved as "ifNoteElse"
      """
      hello from the if line
      """
    * , verify "<ifNote>" equals "hello from the if line"
    * , verify "<ifNoteMarker>" equals ""

  Scenario: An ELSE-IF branch inherits the line doc string
    * IF: 1 == 0 THEN: the recorded note is saved as "elseIfNoteMiss" ELSE-IF: 1 == 1 THEN: the recorded note is saved as "elseIfNote" ELSE: the recorded note is saved as "elseIfNoteElse"
      """
      hello from the else if line
      """
    * , verify "<elseIfNote>" equals "hello from the else if line"

  Scenario: An ELSE branch inherits the line doc string
    * IF: 1 == 0 THEN: the recorded note is saved as "elseNoteMiss" ELSE-IF: 1 == 0 THEN: the recorded note is saved as "elseNoteElseIf" ELSE: the recorded note is saved as "elseNote"
      """
      hello from the else line
      """
    * , verify "<elseNote>" equals "hello from the else line"

  Scenario: A branch DT marker overrides only that branch
    * IF: 1 == 1 THEN: the recorded city is saved as "branchOverride" DT:::city|Paris| ELSE: the recorded city is saved as "branchSkipped"
      | city  |
      | Tempe |
    * , verify "<branchOverride>" equals "Paris"

  Scenario: A branch without DT keeps the inherited table
    * IF: 1 == 0 THEN: the recorded city is saved as "branchSkippedTable" DT:::city|Paris| ELSE: the recorded city is saved as "branchKept"
      | city  |
      | Tempe |
    * , verify "<branchKept>" equals "Tempe"

  Scenario: A peeled marker stays off the earlier identical branch
    * IF: 1 == 1 THEN: the recorded city is saved as "peeledFirst" ELSE: the recorded city is saved as "peeledFirst" DT:::city|Paris|
      | city  |
      | Tempe |
    * , verify "<peeledFirst>" equals "Tempe"

  Scenario: A peeled marker returns on the last identical branch
    * IF: 1 == 0 THEN: the recorded city is saved as "peeledLast" ELSE: the recorded city is saved as "peeledLast" DT:::city|Paris|
      | city  |
      | Tempe |
    * , verify "<peeledLast>" equals "Paris"

  Scenario: An earlier branch keeps its own DT marker
    * IF: 1 == 1 THEN: the recorded city is saved as "earlyOwn" DT:::city|London| ELSE: the recorded city is saved as "earlyOwnLast" DT:::city|Paris|
      | city  |
      | Tempe |
    * , verify "<earlyOwn>" equals "London"

  Scenario: The last branch still receives the peeled marker
    * IF: 1 == 0 THEN: the recorded city is saved as "lateOwn" DT:::city|London| ELSE: the recorded city is saved as "latePeeled" DT:::city|Paris|
      | city  |
      | Tempe |
    * , verify "<latePeeled>" equals "Paris"

  Scenario: Nested DT steps under a block conditional
    * IF: 1 == 1:
      : * the recorded city is saved as "nestedBlockInherit"
        | city  |
        | Tempe |
      : * the recorded city is saved as "nestedBlock" DT:::city|Paris|
        | city  |
        | Tempe |
    * , verify "<nestedBlockInherit>" equals "Tempe"
    * , verify "<nestedBlock>" equals "Paris"
    * , verify "<nestedBlockMarker>" equals "DT"

  Scenario: Nested DT steps under a regular conditional
    * , if 1 == 1:
      : * the recorded city is saved as "nestedCommaInherit"
        | city  |
        | Tempe |
      : * the recorded city is saved as "nestedComma" DT:::city|Paris|
        | city  |
        | Tempe |
    * , verify "<nestedCommaInherit>" equals "Tempe"
    * , verify "<nestedComma>" equals "Paris"
    * , verify "<nestedCommaMarker>" equals "DT"

  Scenario: Another inline type stays stored and does not become a table
    * the recorded note is saved as "noted" NOTE:::hello|
      """
      kept doc string
      """
    * , verify "<noted>" equals "kept doc string"
    * , verify "<notedMarker>" equals "NOTE"

  Scenario: RunIf skips false rows and later rows still run
    * , save "hello" as "A"
    * , save "" as "EmptyA"
    * RUN
      | RunType   | pkb_tags          | token      | RunIf                | RunBackground |
      | SCENARIO  | @run-row-bomb     | falseRow   | false                | true          |
      | SCENARIO  | @run-row-bomb     | blankRow   |                      | true          |
      | SCENARIO  | @run-row-bomb     | nullRow    | null                 | true          |
      | SCENARIO  | @run-row-bomb     | missingRow | <no-such-run-flag>   | true          |
      | SCENARIO  | @run-row-bomb     | boolFalse  | FALSE                | true          |
      | SCENARIO  | @run-row-bomb     | emptyRow   | <EmptyA> has value   | true          |
      | SCENARIO  | @run-row-bomb     | ltRow      | 4 > 5                | true          |
      | SCENARIOS | @run-row-multi    | multiSkip  | false                | true          |
      | SCENARIO  | @run-row-visit    | trueRow    | true                 | true          |
      | SCENARIO  | @run-row-visit    | boolRow    | TRUE                 | false         |
      | SCENARIO  | @run-row-visit    | valueRow   | <A> has value        | true          |
      | SCENARIO  | @run-row-visit    | gtRow      | 4 > 3                | true          |
      | SCENARIOS | @run-row-multi-ok | multiRun   | true                 | true          |
    * , verify "<trueRowMark>" equals "with-background"
    * , verify "<trueRowRunIf>" equals "hidden"
    * , verify "<trueRowRunBackground>" equals "hidden"
    * , verify "<boolRowMark>" equals "without-background"
    * , verify "<boolRowRunIf>" equals "hidden"
    * , verify "<boolRowRunBackground>" equals "hidden"
    * , verify "<valueRowMark>" equals "with-background"
    * , verify "<valueRowRunIf>" equals "hidden"
    * , verify "<gtRowMark>" equals "with-background"
    * , verify "<gtRowRunIf>" equals "hidden"
    * , verify "<multiAlpha>" equals "alpha"
    * , verify "<multiBeta>" equals "beta"
    * , verify "<multiRunMark>" equals "with-background"
    * , verify "<multiRunRunIf>" equals "hidden"
    * , verify "<multiRunRunBackground>" equals "hidden"

  Scenario: RunBackground uses the same evaluation
    * , save "hello" as "A"
    * , save "" as "EmptyA"
    * RUN
      | RunType  | pkb_tags       | token     | RunIf | RunBackground        |
      | SCENARIO | @run-row-visit | bgTrue    | true  | true                 |
      | SCENARIO | @run-row-visit | bgFalse   | true  | false                |
      | SCENARIO | @run-row-visit | bgBlank   | true  |                      |
      | SCENARIO | @run-row-visit | bgNull    | true  | null                 |
      | SCENARIO | @run-row-visit | bgMissing | true  | <no-such-run-flag>   |
      | SCENARIO | @run-row-visit | bgBool    | true  | TRUE                 |
      | SCENARIO | @run-row-visit | bgBoolNo  | true  | FALSE                |
      | SCENARIO | @run-row-visit | bgValue   | true  | <A> has value        |
      | SCENARIO | @run-row-visit | bgEmpty   | true  | <EmptyA> has value   |
      | SCENARIO | @run-row-visit | bgGt      | true  | 4 > 3                |
      | SCENARIO | @run-row-visit | bgLt      | true  | 4 > 5                |
    * , verify "<bgTrueMark>" equals "with-background"
    * , verify "<bgFalseMark>" equals "without-background"
    * , verify "<bgBlankMark>" equals "without-background"
    * , verify "<bgNullMark>" equals "without-background"
    * , verify "<bgMissingMark>" equals "without-background"
    * , verify "<bgBoolMark>" equals "with-background"
    * , verify "<bgBoolNoMark>" equals "without-background"
    * , verify "<bgValueMark>" equals "with-background"
    * , verify "<bgEmptyMark>" equals "without-background"
    * , verify "<bgGtMark>" equals "with-background"
    * , verify "<bgLtMark>" equals "without-background"
    * , verify "<bgTrueRunBackground>" equals "hidden"
    * , verify "<bgValueRunIf>" equals "hidden"

  Scenario: A missing RunIf column runs every row and backgrounds stay off
    * RUN
      | RunType   | pkb_tags          | token    |
      | SCENARIO  | @run-row-visit    | noColumn |
      | SCENARIOS | @run-row-multi-ok | noMulti  |
    * , verify "<noColumnMark>" equals "without-background"
    * , verify "<noColumnRunIf>" equals "hidden"
    * , verify "<noColumnRunBackground>" equals "hidden"
    * , verify "<multiAlpha>" equals "alpha"
    * , verify "<multiBeta>" equals "beta"
    * , verify "<noMultiMark>" equals "without-background"

  Scenario: A missing RunBackground column ignores called backgrounds
    * RUN
      | RunType  | pkb_tags       | token | RunIf |
      | SCENARIO | @run-row-visit | noBg  | true  |
    * , verify "<noBgMark>" equals "without-background"
    * , verify "<noBgRunIf>" equals "hidden"
    * , verify "<noBgRunBackground>" equals "hidden"

  Scenario: Component scenario rows honor RunIf and RunBackground
    * RUN
      | RunType            | pkb_name               | pkb_componentpath           | token         | RunIf | RunBackground |
      | COMPONENT SCENARIO | ^Run row child bomb$   | src/test/resources/features | componentSkip | false | true          |
      | COMPONENT SCENARIO | ^Run row child visit$  | src/test/resources/features | component     | true  | true          |
    * , verify "<componentMark>" equals "with-background"
    * , verify "<componentRunIf>" equals "hidden"
    * , verify "<componentRunBackground>" equals "hidden"

  Scenario: Service call rows honor RunIf and RunBackground
    * RUN
      | RunType      | pkb_name              | pkb_callpath                | token       | RunIf | RunBackground |
      | SERVICE CALL | ^Run row child bomb$  | src/test/resources/features | serviceSkip | false | true          |
      | SERVICE CALL | ^Run row child visit$ | src/test/resources/features | service     | true  | true          |
    * , verify "<serviceMark>" equals "with-background"
    * , verify "<serviceRunIf>" equals "hidden"
    * , verify "<serviceRunBackground>" equals "hidden"

  Scenario: A partial scenario name selects one example row
    * RUN
      | RunType  | pkb_name          | pkb_example |
      | SCENARIO | Scan partial name | 2           |
    * , verify "<exampleRow>" equals "2"

  Scenario: resolve-runvars is the launcher JVM
    * resolve-runvars reports the launcher JVM

  @area-a @area-plain-branch-args
  Scenario: A truthy plain IF inherits the line table and doc string and a DT marker overrides
    * IF: "abc" THEN: the recorded city is saved as "plainIfCity" ELSE: the recorded city is saved as "plainIfElseMiss"
      | city  |
      | Tempe |
    * , verify "<plainIfCity>" equals "Tempe"
    * IF: "abc" THEN: the recorded note is saved as "plainIfNote" ELSE: the recorded note is saved as "plainIfNoteElse"
      """
      hello plain if
      """
    * , verify "<plainIfNote>" equals "hello plain if"
    * IF: "abc" THEN: the recorded city is saved as "plainOverride" DT:::city|Paris| ELSE: the recorded city is saved as "plainOverrideElse"
      | city  |
      | Tempe |
    * , verify "<plainOverride>" equals "Paris"

  @area-a @area-plain-branch-args-falsy
  Scenario: A falsy plain IF lets ELSE inherit the line table and doc string
    * IF: "" THEN: the recorded city is saved as "plainElseMiss" ELSE: the recorded city is saved as "plainElseCity"
      | city  |
      | Tempe |
    * , verify "<plainElseCity>" equals "Tempe"
    * IF: "" THEN: the recorded note is saved as "plainElseNoteMiss" ELSE: the recorded note is saved as "plainElseNote"
      """
      hello plain else
      """
    * , verify "<plainElseNote>" equals "hello plain else"
    * IF: "" THEN: the recorded city is saved as "plainFalsyOverride" DT:::city|Paris| ELSE: the recorded city is saved as "plainFalsyKept"
      | city  |
      | Tempe |
    * , verify "<plainFalsyKept>" equals "Tempe"

  @area-a @area-runif-plain
  Scenario: A plain RunIf cell uses IF truthiness
    * , save "hello" as "A"
    * RUN
      | RunType  | pkb_tags       | token      | RunIf              |
      | SCENARIO | @run-row-visit | plainAbc   | "abc"              |
      | SCENARIO | @run-row-bomb  | plainZeroQ | "0"                |
      | SCENARIO | @run-row-bomb  | plainZero  | 0                  |
      | SCENARIO | @run-row-visit | plainOne   | <{ 1 }>            |
      | SCENARIO | @run-row-bomb  | plainZeroE | <{ 0 }>            |
      | SCENARIO | @run-row-visit | plainHello | <A>                |
      | SCENARIO | @run-row-bomb  | plainMiss  | <no-such-run-flag> |
    * , verify "<plainAbcMark>" equals "without-background"
    * , verify "<plainOneMark>" equals "without-background"
    * , verify "<plainHelloMark>" equals "without-background"

  @area-a @area-stress-block
  Scenario: A depth-3 block mixes operators a skipped side a table comma else-if and RunIf
    * reset branch counts
    * , save "untouched" as "stressInner"
    * , save "untouched" as "stressQ"
    * IF: <{ (!false && (true ? 1 : <$count branch stress-untaken>)) || <MISSING> ? }>:
      : * IF: "abc":
        :: * IF: "":
          ::: * , save "no" as "stressInner"
        :: * ELSE-IF: 0:
          ::: * , save "no" as "stressInner"
        :: * ELSE:
          ::: * the recorded city is saved as "stressCity" DT:::city|Paris|
            | city  |
            | Tempe |
          ::: * , save "yes" as "stressInner"
      : * ELSE:
        :: * , save "no" as "stressInner"
    * ELSE:
      : * , save "no" as "stressInner"
    * , if "", save "no" as "stressQ", else if "abc", save "yes" as "stressQ", else, save "else" as "stressQ"
    * RUN
      | RunType  | pkb_tags       | token      | RunIf              |
      | SCENARIO | @run-row-visit | stressRun  | "abc"              |
      | SCENARIO | @run-row-bomb  | stressSkip | <no-such-run-flag> |
    * , ensure "<stressInner>" equals "yes"
    * , verify "<stressCity>" equals "Paris"
    * , ensure "<stressQ>" equals "yes"
    * branch count "stress-untaken" equals "0"
    * , verify "<stressRunMark>" equals "without-background"

