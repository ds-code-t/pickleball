Feature: Area B control flow

  @all @area-b @area-b-comma
  Scenario: A comma chains two saves
    * , save "one" as "areaBComma", save "two" as "areaBComma2"
    * , ensure "<areaBComma>" equals "one"
    * , ensure "<areaBComma2>" equals "two"

  @all @area-b @area-b-semicolon
  Scenario: A semicolon chains two saves
    * , save "one" as "areaBSemi"; save "two" as "areaBSemi2"
    * , ensure "<areaBSemi>" equals "one"
    * , ensure "<areaBSemi2>" equals "two"

  @all @area-b @area-b-period
  Scenario: A period splits sentences and the colon child sees both saves
    * , save "untouched" as "areaBPeriodSeen"
    * , save "a" as "areaBX". save "b" as "areaBY":
      : * , save "<areaBX>-<areaBY>" as "areaBPeriodSeen"
    * , ensure "<areaBPeriodSeen>" equals "a-b"

  @all @area-b @area-b-nested-if
  Scenario: A nested colon runs a truthy parent and skips a falsy parent
    * , save "untouched" as "areaBNestedYes"
    * , save "untouched" as "areaBNestedNo"
    * , if "abc":
      : * , save "yes" as "areaBNestedYes"
    * , if "":
      : * , save "no" as "areaBNestedNo"
    * , save "sibling" as "areaBNestedSibling"
    * , ensure "<areaBNestedYes>" equals "yes"
    * , ensure "<areaBNestedNo>" equals "untouched"
    * , ensure "<areaBNestedSibling>" equals "sibling"

  @all @area-b @area-b-question
  Scenario: A question-mark parent runs a truthy child and skips a falsy child
    * , save "untouched" as "areaBQuestionYes"
    * , save "untouched" as "areaBQuestionNo"
    * , if "abc"?
      : * , save "yes" as "areaBQuestionYes"
    * , if ""?
      : * , save "no" as "areaBQuestionNo"
    * , ensure "<areaBQuestionYes>" equals "yes"
    * , ensure "<areaBQuestionNo>" equals "untouched"

  @all @area-b @area-b-deep-nest
  Scenario: Nesting colons run depth 3 and a falsy middle level saves nothing
    * , save "untouched" as "areaBDeepNo"
    * , save "untouched" as "areaBDeepYes"
    * , if "abc":
      : * , if "":
        :: * , save "no" as "areaBDeepNo"
      : * , if "abc":
        :: * , if "abc":
          ::: * , save "yes" as "areaBDeepYes"
    * , ensure "<areaBDeepNo>" equals "untouched"
    * , ensure "<areaBDeepYes>" equals "yes"

  @all @area-b @area-b-block-branch
  Scenario: Same-level block if else-if else takes the first match only
    * , save "untouched" as "areaBBranch"
    * , save "untouched" as "areaBFirst"
    * IF: "":
      : * , save "if" as "areaBBranch"
    * ELSE-IF: "abc":
      : * , save "else-if" as "areaBBranch"
    * ELSE:
      : * , save "else" as "areaBBranch"
    * IF: "abc":
      : * , save "if" as "areaBFirst"
    * ELSE-IF: "abc":
      : * , save "else-if" as "areaBFirst"
    * ELSE:
      : * , save "else" as "areaBFirst"
    * , ensure "<areaBBranch>" equals "else-if"
    * , ensure "<areaBFirst>" equals "if"

  @all @area-b @area-b-block-until
  Scenario: A block until loops until the condition is true on the second pass
    * , save "" as "areaBUntilFlag"
    * , until "<areaBUntilFlag>" equals "done":
      : * count branch areaBUntil
      : * , save "done" as "areaBUntilFlag"
    * branch count "areaBUntil" equals "1"
    * , ensure "<areaBUntilFlag>" equals "done"

  @all @area-b @area-b-inline-until
  Scenario: An inline until is a one-shot if
    * , save "untouched" as "areaBInlineUntil"
    * , save "untouched" as "areaBInlineUntilNo"
    * , until "abc", save "once" as "areaBInlineUntil"
    * , until "", save "again" as "areaBInlineUntilNo"
    * , ensure "<areaBInlineUntil>" equals "once"
    * , ensure "<areaBInlineUntilNo>" equals "untouched"

  @all @area-b @area-b-times
  Scenario: Times saves the same value three times
    * , save "tick" as "areaBTimes" 3 times
    * the number of saves to "areaBTimes" is saved as "areaBTimesCount"
    * , ensure "<areaBTimes>" equals "tick"
    * , ensure "<areaBTimesCount>" equals "3"

  @all @area-b @area-b-flags
  Scenario: RETRY and unimplemented flag steps are passing no-ops
    * , save "untouched" as "areaBAfterFlags"
    * RETRY: not implemented
    * IGNORE FAILURES
    * LOG FAILURES BUT CONTINUE SCENARIO
    * RUN IF SCENARIO FINISHED
    * , save "continued" as "areaBAfterFlags"
    * , ensure "<areaBAfterFlags>" equals "continued"

  @all @area-b @area-b-end-scenario
  Scenario: END SCENARIO skips the rest of the scenario
    * , save "ran" as "areaBBeforeEnd"
    * , ensure "<areaBBeforeEnd>" equals "ran"
    * END SCENARIO
    * FAIL: this step should have been skipped

  @all @area-b @area-b-child-arguments
  Scenario: A colon child keeps its table and doc string and only DT becomes a table
    * , if "abc":
      : * the recorded city is saved as "areaBChildCity"
        | city  |
        | Tempe |
      : * the recorded note is saved as "areaBChildNote"
        """
        child note
        """
    * , ensure "<areaBChildCity>" equals "Tempe"
    * , ensure "<areaBChildNote>" equals "child note"
    * , if "abc":
      : * the recorded city is saved as "areaBChildOverride" DT:::city|Paris|
        | city  |
        | Tempe |
    * , ensure "<areaBChildOverride>" equals "Paris"
    * , ensure "<areaBChildOverrideMarker>" equals "DT"
    * , if "abc":
      : * the recorded note is saved as "areaBChildDs" DS:::hello|
        """
        kept child note
        """
    * , ensure "<areaBChildDs>" equals "kept child note"
    * , ensure "<areaBChildDsMarker>" equals "DS"

  @all @area-b @area-b-run-background
  Scenario: RunBackground is opt-in and SCENARIO always runs the called background
    * , save "absent" as "areaBBg"
    * , save "absent" as "areaBBgSeen"
    * , save "absent" as "areaBBody"
    * RUN
      | RunType  | pkb_featurename     | pkb_name                     | RunBackground |
      | SCENARIO | Area B control flow | ^Area B background target$   | false         |
    * , ensure "<areaBBody>" equals "from-body"
    * , ensure "<areaBBg>" equals "absent"
    * , ensure "<areaBBgSeen>" equals "absent"
    * , save "absent" as "areaBBg"
    * , save "absent" as "areaBBgSeen"
    * RUN
      | RunType  | pkb_featurename     | pkb_name                     | RunIf | RunBackground |
      | SCENARIO | Area B control flow | ^Area B background target$   | true  | true          |
    * , ensure "<areaBBg>" equals "from-background"
    * , ensure "<areaBBgSeen>" equals "from-background"
    * , ensure "<areaBBody>" equals "from-body"
    * , save "absent" as "areaBBg"
    * , save "absent" as "areaBBgSeen"
    * SCENARIO: Area B control flow.Area B background target
    * , ensure "<areaBBg>" equals "from-background"
    * , ensure "<areaBBgSeen>" equals "from-background"

  @all @area-b @area-b-default-marker
  Scenario: The default start marker keeps depth 2 and 3 inside the body
    * , verify "before default" equals "skipped"
    : * ---startstep
    : * , save "depth1" as "areaBDefaultDepth1"
    :: * , save "depth2" as "areaBDefaultDepth2"
    ::: * , save "depth3" as "areaBDefaultDepth3"
    : * , ensure "<areaBDefaultDepth2>" equals "depth2"
    : * , ensure "<areaBDefaultDepth3>" equals "depth3"
    * ---endstep
    * , verify "after default" equals "skipped"

  @all @area-b @area-b-custom-marker
  Scenario: A custom start marker keeps depth 2 and 3 inside the called body
    * SCENARIO: Area B control flow.Area B custom marker target.area b marker
    * , ensure "<areaBCustomDepth2>" equals "custom2"
    * , ensure "<areaBCustomDepth3>" equals "custom3"

  @all @area-b @area-b-outline
  Scenario Outline: A root outline runs both rows
    * , save "<row>" as "areaBOutline"
    * , ensure "<areaBOutline>" equals "<row>"
    Examples:
      | row |
      | one |
      | two |

  @all @area-b @area-b-example
  Scenario: pkb_example selects one outline row
    * RUN
      | RunType  | pkb_featurename     | pkb_name                  | pkb_example |
      | SCENARIO | Area B control flow | ^Area B example selector$ | 2           |
    * , ensure "<areaBExampleRow>" equals "2"

  @all @area-b @area-b-java-checks
  Scenario: Exhausted until, element wait, empty comma, SOFT FAIL, and dynamic step text are observed
    * RUN AREA B CONTROL FLOW JAVA TESTS

  @all @area-b @area-b-stress-nest
  Scenario: A depth-3 nest keeps a falsy else an until a table a doc string and a called marker
    * , save "untouched" as "aBStressFalsy"
    * , save "untouched" as "aBStressUntil"
    * , save "" as "aBStressFlag"
    * , save "absent" as "areaBBg"
    * , save "untouched" as "aBStressMarker"
    * IF: "abc":
      : * IF: "":
        :: * , save "no" as "aBStressFalsy"
      : * ELSE:
        :: * , save "left" as "aBStressSemi"; save "right" as "aBStressSemi2"
        :: * , until "<aBStressFlag>" equals "go":
          ::: * , save "went" as "aBStressUntil"
          ::: * the recorded note is saved as "aBStressNote"
            """
            depth three note
            """
          ::: * , save "go" as "aBStressFlag"
        :: * the recorded city is saved as "aBStressCity" DT:::city|Paris|
          | city  |
          | Tempe |
    * ELSE:
      : * , save "no" as "aBStressFalsy"
    * RUN
      | RunType  | pkb_featurename     | pkb_name                        | RunIf | RunBackground | Step_Marker   |
      | SCENARIO | Area B control flow | ^Area B stress bomb$            | false | true          |               |
      | SCENARIO | Area B control flow | ^Area B background target$      | true  | true          |               |
      | SCENARIO | Area B control flow | ^Area B stress marker target$   | true  | true          | area b stress |
    * , ensure "<aBStressFalsy>" equals "untouched"
    * , ensure "<aBStressUntil>" equals "went"
    * , ensure "<aBStressNote>" equals "depth three note"
    * , ensure "<aBStressCity>" equals "Paris"
    * , ensure "<aBStressCityMarker>" equals "DT"
    * , ensure "<aBStressSemi>" equals "left"
    * , ensure "<aBStressSemi2>" equals "right"
    * , ensure "<areaBBg>" equals "from-background"
    * , ensure "<aBStressMarker>" equals "inside"

  @all @area-b @area-b-stress-soft
  Scenario: ALWAYS RUN cleanup an ASSERT and a failing SOFT ASSERT still reach a hard ensure
    * , save "untouched" as "areaBAlways"
    * , save "untouched" as "areaBHardStill"
    * ALWAYS RUN
    * , save "cleaned" as "areaBAlways"
    * ASSERT: 1 < 2
    * SOFT ASSERT: 1 == 2 | "A" equals "B"
    * , save "still" as "areaBHardStill"
    * , ensure "<areaBAlways>" equals "cleaned"
    * , ensure "<areaBHardStill>" equals "still"

  Rule: Area B called targets

    Background:
      * , save "from-background" as "areaBBg"

    Scenario: Area B background target
      * , save "<areaBBg>" as "areaBBgSeen"
      * , save "from-body" as "areaBBody"

    Scenario: Area B custom marker target
      * , verify "before custom" equals "skipped"
      : * ---area b marker
      : * , save "custom1" as "areaBCustomDepth1"
      :: * , save "custom2" as "areaBCustomDepth2"
      ::: * , save "custom3" as "areaBCustomDepth3"
      : * , ensure "<areaBCustomDepth2>" equals "custom2"
      : * , ensure "<areaBCustomDepth3>" equals "custom3"
      * ---endstep
      * , verify "after custom" equals "skipped"

    Scenario: Area B stress marker target
      * , verify "outside stress" equals "skipped"
      : * ---area b stress
      : * , save "inside" as "aBStressMarker"
      * ---endstep
      * , verify "after stress" equals "skipped"

    Scenario: Area B stress bomb
      * FAIL: stress row should have been skipped

  Scenario Outline: Area B example selector
    * , save "<row>" as "areaBExampleRow"
    * , verify "<row>" equals "2"
    Examples:
      | row |
      | 1   |
      | 2   |
      | 3   |
