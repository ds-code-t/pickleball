@all @phrase-plain-forms
Feature: Plain phrase forms select a branch without throwing

  Scenario: Ends with and starts with select the branch
    * , save "untouched" as "endsNix"
    * , save "untouched" as "endsAva"
    * , save "untouched" as "startsPho"
    * , save "untouched" as "startsTem"
    * IF: "Phoenix" ends with "nix" THEN: , save "yes" as "endsNix"
    * IF: "Phoenix" ends with "Ava" THEN: , save "no" as "endsAva"
    * IF: "Phoenix" starts with "Pho" THEN: , save "yes" as "startsPho"
    * IF: "Phoenix" starts with "Tem" THEN: , save "no" as "startsTem"
    * , ensure "<endsNix>" equals "yes"
    * , ensure "<endsAva>" equals "untouched"
    * , ensure "<startsPho>" equals "yes"
    * , ensure "<startsTem>" equals "untouched"

  Scenario: A quoted value matches a whole-string Java regex
    matches compiles a Java regular expression and uses Matcher.matches, so the pattern must cover the whole value.
    A double-quoted pattern is case-sensitive. A single quote on either side is case-insensitive.
    "Ava" matches the pattern A.+ and does not match T.+.
    * , save "untouched" as "match"
    * , save "untouched" as "mismatch"
    * IF: "Ava" matches "A.+" THEN: , save "yes" as "match"
    * IF: "Ava" matches "T.+" THEN: , save "no" as "mismatch"
    * , ensure "<match>" equals "yes"
    * , ensure "<mismatch>" equals "untouched"

  Scenario: Is blank selects an empty string
    * , save "untouched" as "blank"
    * , save "untouched" as "notBlank"
    * IF: "" is blank THEN: , save "yes" as "blank"
    * IF: "abc" is blank THEN: , save "no" as "notBlank"
    * , ensure "<blank>" equals "yes"
    * , ensure "<notBlank>" equals "untouched"

  Scenario: Forms playground element states select the branch
    * navigate to: URL.forms
    * , ensure "Forms Playground" Text is displayed
    * , save "untouched" as "emailOn"
    * , save "untouched" as "phoneOff"
    * , save "untouched" as "phoneOnSkip"
    * , save "untouched" as "emailOffSkip"
    * , save "untouched" as "phoneOn"
    * , save "untouched" as "emailOff"
    * , save "untouched" as "present"
    * , save "untouched" as "required"
    * , save "untouched" as "optional"
    * , save "untouched" as "optionalRequiredSkip"
    * , save "untouched" as "requiredSkip"
    * , click the "Email" Radio Button
    * IF: "Email" Radio Button is selected THEN: , save "yes" as "emailOn"
    * IF: "Phone" Radio Button is unselected THEN: , save "yes" as "phoneOff"
    * IF: "Phone" Radio Button is selected THEN: , save "no" as "phoneOnSkip"
    * IF: "Email" Radio Button is unselected THEN: , save "no" as "emailOffSkip"
    * , click the "Phone" Radio Button
    * IF: "Phone" Radio Button is selected THEN: , save "yes" as "phoneOn"
    * IF: "Email" Radio Button is unselected THEN: , save "yes" as "emailOff"
    * IF: "Submit Form" Button is present THEN: , save "yes" as "present"
    * IF: "Required Marker" Textbox is required THEN: , save "yes" as "required"
    * IF: "Last Name" Textbox is non-required THEN: , save "yes" as "optional"
    * IF: "Last Name" Textbox is required THEN: , save "no" as "optionalRequiredSkip"
    * IF: "Required Marker" Textbox is non-required THEN: , save "no" as "requiredSkip"
    * , ensure "<emailOn>" equals "yes"
    * , ensure "<phoneOff>" equals "yes"
    * , ensure "<phoneOnSkip>" equals "untouched"
    * , ensure "<emailOffSkip>" equals "untouched"
    * , ensure "<phoneOn>" equals "yes"
    * , ensure "<emailOff>" equals "yes"
    * , ensure "<present>" equals "yes"
    * , ensure "<required>" equals "yes"
    * , ensure "<optional>" equals "yes"
    * , ensure "<optionalRequiredSkip>" equals "untouched"
    * , ensure "<requiredSkip>" equals "untouched"

  Scenario: A backtick runs and zero no null and the null marker skip
    * , save "untouched" as "tick"
    * , save "untouched" as "zero"
    * , save "untouched" as "noWord"
    * , save "untouched" as "nullWord"
    * , save "untouched" as "nullMarker"
    * IF: `abc` THEN: , save "yes" as "tick"
    * IF: "0" THEN: , save "no" as "zero"
    * IF: "no" THEN: , save "no" as "noWord"
    * IF: "null" THEN: , save "no" as "nullWord"
    * IF: <^~NULL~^> THEN: , save "no" as "nullMarker"
    * , ensure "<tick>" equals "yes"
    * , ensure "<zero>" equals "untouched"
    * , ensure "<noWord>" equals "untouched"
    * , ensure "<nullWord>" equals "untouched"
    * , ensure "<nullMarker>" equals "untouched"
