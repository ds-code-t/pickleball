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

  Scenario: A trailing question mark is the boolean marker
    * , save "untouched" as "qTrue"
    * , save "untouched" as "qFalse"
    * IF: <{ true? }> THEN: , save "yes" as "qTrue"
    * IF: <{ false? }> THEN: , save "no" as "qFalse"
    * , ensure "<qTrue>" equals "yes"
    * , ensure "<qFalse>" equals "untouched"
