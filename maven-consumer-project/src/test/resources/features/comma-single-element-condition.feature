@all @comma-single-element-condition
Feature: A comma if with one element is truthy or falsy and does not throw

  Scenario: A comma if runs a truthy single element and skips a falsy one
    * , save "untouched" as "q"
    * , save "untouched" as "f"
    * , save "untouched" as "z"
    * , save "untouched" as "m"
    * , save "prior" as "b"
    * , if "abc", save "yes" as "q"
    * , if false, save "no" as "f", else, save "else" as "f"
    * , if 0, save "no" as "z", else, save "else" as "z"
    * , if <missingRef>, save "no" as "m", else, save "else" as "m"
    * , if "", save "no" as "b"
    * , ensure "<q>" equals "yes"
    * , ensure "<f>" equals "else"
    * , ensure "<z>" equals "else"
    * , ensure "<m>" equals "else"
    * , ensure "<b>" equals "prior"

  @area-a @area-comma-else-if
  Scenario: A comma else if uses a plain element
    * , save "untouched" as "q"
    * , if "", save "no" as "q", else if "abc", save "yes" as "q", else, save "else" as "q"
    * , ensure "<q>" equals "yes"

  @area-a @area-comma-block
  Scenario: A comma block if runs one nested save
    * , save "untouched" as "commaNest"
    * , if "abc":
      : * , save "yes" as "commaNest"
    * , ensure "<commaNest>" equals "yes"

  @area-a @area-until-as-if
  Scenario: A comma until selects a branch the way if does
    * , save "untouched" as "untilYes"
    * , save "untouched" as "untilNo"
    * , until "abc", save "yes" as "untilYes"
    * , until "", save "no" as "untilNo"
    * , ensure "<untilYes>" equals "yes"
    * , ensure "<untilNo>" equals "untouched"
