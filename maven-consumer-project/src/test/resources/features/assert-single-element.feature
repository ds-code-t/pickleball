@all @assert-single-element
Feature: A single-element assert is truthy or falsy

  Scenario: A single-element ASSERT passes and a later step runs
    * ASSERT: "abc"
    * ASSERT: 7
    * ASSERT: true
    * , save "after" as "hardAssert"
    * , ensure "<hardAssert>" equals "after"

  Scenario: A single-element SOFT ASSERT does not throw and a later step runs
    * SOFT ASSERT: false
    * SOFT ASSERT: ""
    * , save "after" as "softAssert"
    * , ensure "<softAssert>" equals "after"
