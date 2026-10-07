@all @map-ref-plain-forms
Feature: Plain file and ampersand references

  Scenario: A file reference reads customers.yaml
    * , ensure "<file:files/customers #1.name>" equals "Ava"
    * , ensure "<file:files/customers #1.city>" equals "Phoenix"
    * , ensure "<file:files/customers #1.tier>" equals "Premium"
    * , ensure "<file:files/customers[0].name>" equals "Ava"
    * , ensure "<file:files/customers #2.name>" equals "Ben"
    * , ensure "<file:files/customers #2.city>" equals "Tempe"

  Scenario: A skipped file reference does not throw
    * , save "untouched" as "skippedFile"
    * IF: false && <file:files/no-such-file> THEN: , save "no" as "skippedFile"
    * , ensure "<skippedFile>" equals "untouched"

  Scenario: A taken missing file does not throw
    * , save "untouched" as "takenMissingFile"
    * IF: true && <file:files/no-such-file> THEN: , save "no" as "takenMissingFile"
    * , ensure "<takenMissingFile>" equals "untouched"

  Scenario: A deprecated ampersand reference still resolves
    * , save "ava" as "key"
    * , ensure "<&key>" equals "ava"
