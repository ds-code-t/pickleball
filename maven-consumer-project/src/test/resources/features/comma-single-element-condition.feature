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
