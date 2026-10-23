@until-pass-timing
Feature: Until pass timing

  Scenario: A literal until runs twenty passes
    * , until "0" equals "1":
      : * , save "tick" as "untilTick"
