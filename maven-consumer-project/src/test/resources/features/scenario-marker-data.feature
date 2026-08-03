Feature: Scenario marker data extraction

  @all @regression @scenario-data
  Scenario: Read marker data without executing the component scenario
    * VERIFY SCENARIO DATA: FEATURE: Scenario marker data extraction SCENARIO: Marker data component START: marker stored example-row
      | passedValue |
      | stored      |
    * , verify "the component was not executed" equals "the component was not executed"

  @component-definition
  Scenario Outline: Marker data component
    * ^^^
      | A |
      | 1 |
    * ---marker <passedValue> <exampleValue>
    * , verify "this component must not execute" equals "data extraction only"

    Examples:
      | exampleValue |
      | example-row  |
