Feature: Step state elements

  @all @step-state @step-state-repetition
  Scenario: A block until runs the body three times
    Step Repetition is the pass being evaluated. The check that reads 4 stops, so the body runs exactly 3 times.
    * reset branch counts
    * , until the Step Repetition is greater than 3:
      : * count branch repetition-body
    * branch count "repetition-body" equals "3"

  @all @step-state @step-state-child
  Scenario: A child step reads its own Step Repetition
    * , save "untouched" as "childRep"
    * , until the Step Repetition is greater than 2:
      : * , if the Step Repetition is greater than 1:
        :: * , save "parent-count" as "childRep"
    * , ensure "<childRep>" equals "untouched"

  @all @step-state @step-state-if
  Scenario: An if reads the current step Step Repetition
    * , save "untouched" as "ifRep"
    * , save "untouched" as "ifRepHigh"
    * , if the Step Repetition is greater than 0:
      : * , save "ran" as "ifRep"
    * , if the Step Repetition is greater than 1:
      : * , save "high" as "ifRepHigh"
    * , ensure "<ifRep>" equals "ran"
    * , ensure "<ifRepHigh>" equals "untouched"

  @all @step-state @step-state-or
  Scenario: A missing element or Step Repetition stops by count
    * navigate to: URL.home
    * reset branch counts
    * , until the "No Such Step State" Button is displayed, or the Step Repetition is greater than 3:
      : * count branch missing-or-count
    * branch count "missing-or-count" equals "3"

  @all @step-state @step-state-duration
  Scenario: A block until stops when Step Duration is greater than 1 second
    * reset branch counts
    * , until the Step Duration is greater than 1 second:
      : * count branch duration-body
    * branch count "duration-body" is from "1" through "6"

  @all @step-state @step-state-save
  Scenario: Saving Step Duration stores an ISO-8601 duration
    * , save the Step Duration as "d"
    * , ensure "<d>" matches "PT.*S"

  @all @step-state @step-state-java
  Scenario: Reserved Step names, Url as HTML, and custom category warnings are observed
    * RUN STEP STATE ELEMENT JAVA TESTS
